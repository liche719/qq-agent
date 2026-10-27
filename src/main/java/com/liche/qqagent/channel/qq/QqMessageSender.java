package com.liche.qqagent.channel.qq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.function.Supplier;

/**
 * 发一条消息（2026-10-27 从 {@code QqChannel} 拆出，第 6c 步的第一刀）。
 *
 * <p>这里只做"一次 HTTP 发送"：拼路径、拼 body、带 token。**重试/降级/额度/结果未知**
 * 这些策略仍留在 channel——它们要看被动窗口、配额、错误分类，属于编排而不是传输。
 *
 * <p>拆出来的好处：整条链上"真正把字节发给 QQ"的地方只剩这一处，排查发送问题不用在 1900 行里找。
 */
final class QqMessageSender {

    /** 传输层需要调用方提供的东西 */
    record Gateway(Supplier<String> accessToken, Supplier<String> apiBase,
                   java.util.function.Function<String, RestClient> client, Supplier<ObjectMapper> json,
                   java.util.function.IntSupplier nextSeq) {
    }

    private final Gateway gateway;

    QqMessageSender(Gateway gateway) {
        this.gateway = gateway;
    }

    /** 群消息和私聊消息走不同端点 */
    static String path(String conversationId) {
        return QqMessages.isGroup(conversationId)
                ? "/v2/groups/{openid}/messages"
                : "/v2/users/{openid}/messages";
    }

    /**
     * 发一条消息。
     *
     * @return 平台返回的原始响应（一般是 `{"id":"..."}`），失败会抛异常交给调用方分类
     */
    String post(String conversationId, String text, boolean markdown, String replyToMsgId) {
        RestClient client = gateway.client().apply(gateway.apiBase().get());
        ObjectNode body = QqMessages.body(gateway.json().get(), text, markdown, replyToMsgId, gateway.nextSeq());
        return client.post().uri(path(conversationId), QqMessages.targetOpenid(conversationId))
                .header("Authorization", "QQBot " + gateway.accessToken().get())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toString())
                .retrieve().body(String.class);
    }
}
