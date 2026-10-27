package com.liche.qqagent.channel.qq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.function.IntSupplier;

/**
 * 消息体构造与会话目标解析（2026-10-27 从 {@code QqChannel} 拆出，第 6b 步）。
 *
 * <p>这两件事都是纯的：给定文本/是否 markdown/回复哪条消息，产出请求体；给定会话 id，
 * 判断是私聊还是群、以及平台要的 openid。它们出错的表现很隐蔽——群消息发成私聊、
 * `msg_seq` 没带上导致被动回复失效——所以单独抽出来测。
 */
final class QqMessages {

    /** 群会话在内部用这个前缀和私聊的 openid 区分开 */
    static final String GROUP_PREFIX = "qq-group:";

    private QqMessages() {
    }

    /**
     * 消息请求体。
     *
     * <p>与拆之前一致：markdown 走 `markdown.content` + `msg_type=2`，纯文本走 `content` + `msg_type=0`；
     * 只有在带 `replyToMsgId`（被动回复）时才分配 `msg_seq`——平台按它对同一条用户消息限 5 次。
     */
    static ObjectNode body(ObjectMapper json, String text, boolean markdown, String replyToMsgId,
                           IntSupplier nextSeq) {
        ObjectNode body = json.createObjectNode();
        if (markdown) {
            body.putObject("markdown").put("content", text);
            body.put("msg_type", 2);
        } else {
            body.put("content", text);
            body.put("msg_type", 0);
        }
        if (replyToMsgId != null && !replyToMsgId.isBlank()) {
            body.put("msg_id", replyToMsgId);
            body.put("msg_seq", nextSeq.getAsInt());
        }
        return body;
    }

    static String groupConversationId(String groupOpenid) {
        return GROUP_PREFIX + groupOpenid;
    }

    static boolean isGroup(String conversationId) {
        return conversationId != null && conversationId.startsWith(GROUP_PREFIX);
    }

    static String targetOpenid(String conversationId) {
        return isGroup(conversationId) ? conversationId.substring(GROUP_PREFIX.length()) : conversationId;
    }
}
