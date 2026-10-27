package com.liche.qqagent.channel.qq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 进度帧的 HTTP 发送（2026-10-27 从 {@code QqChannel} 拆出，第 5b 步）。
 *
 * <p><b>为什么只搬这一块</b>：进度条这块里，"发一帧 HTTP"是最独立、也最容易出错的一段
 * （body 字段、msg_seq/index 递增、首次响应里取 stream_msg_id、平台拒绝的错误码）。
 * 把它单独成类，channel 只负责"发成功了没、失败了要不要降级"。
 *
 * <p><b>结果用枚举回给调用方</b>，而不是在这里直接做降级：
 * 降级要发普通消息，会牵扯到 channel 的发送通道与额度，放在 channel 更合适。
 */
final class QqProgressFrames {

    private static final Logger log = LoggerFactory.getLogger(QqProgressFrames.class);

    /**
     * HTTP 层需要调用方提供的东西。用 record 装五个函数——调用方一行就能构造，
     * 不必写匿名类（那样把省下的行数又还回去了）。
     */
    record Gateway(Runnable ensureToken, Supplier<String> accessToken, Supplier<String> apiBase,
                   Function<String, RestClient> client, Supplier<ObjectMapper> json) {
    }

    /** 一帧的结果：正常 / 平台拒收（流式已收尾、msg_id 失效）/ 其它失败 */
    enum Result {
        OK, REJECTED, FAILED
    }

    private final Gateway gateway;

    QqProgressFrames(Gateway gateway) {
        this.gateway = gateway;
    }

    /**
     * 发一帧进度（replace 模式，index 自增）。
     *
     * @param inputState 1 = 进行中；10 = 收尾定格
     */
    Result send(String userId, String replyToMsgId, QqProgressSessions.Session session, int inputState) {
        try {
            gateway.ensureToken().run();
            RestClient client = gateway.client().apply(gateway.apiBase().get());
            ObjectMapper json = gateway.json().get();
            ObjectNode body = json.createObjectNode();
            body.put("input_mode", "replace");
            body.put("input_state", inputState);
            body.put("content_type", "markdown");
            body.put("content_raw", session.text.toString());
            if (replyToMsgId != null && !replyToMsgId.isBlank()) {
                body.put("msg_id", replyToMsgId);
            }
            body.put("msg_seq", session.msgSeq);
            body.put("index", session.index++);
            if (session.messageId != null) {
                body.put("stream_msg_id", session.messageId);
            }
            String response = client.post().uri("/v2/users/{openid}/stream_messages", userId)
                    .header("Authorization", "QQBot " + gateway.accessToken().get())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .retrieve().body(String.class);
            if (session.messageId == null && response != null) {
                String id = json.readTree(response).path("id").asText("");
                if (!id.isBlank()) {
                    session.messageId = id;
                }
            }
            return Result.OK;
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? "" : exception.getMessage();
            if (message.contains("40034019") || message.contains("40034024")) {
                // 流式已收尾 / msg_id 失效：沙箱环境常见。交给调用方决定要不要降级
                log.warn("[qq] progress stream rejected user={} reason={}", userId,
                        message.contains("40034024") ? "msg_id 失效" : "流式已收尾");
                return Result.REJECTED;
            }
            log.warn("[qq] progress frame failed user={}: {}", userId, message);
            return Result.FAILED;
        }
    }
}
