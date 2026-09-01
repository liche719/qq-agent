package com.liche.wechatagent.channel.qq;

import com.fasterxml.jackson.databind.JsonNode;

/** Maps QQ gateway payloads into validated direct and group message records. */
final class QqMessageMapper {

    private QqMessageMapper() {
    }

    static DirectMessage direct(JsonNode data, String selfOpenid) {
        String openid = data.path("author").path("id").asText("");
        String messageId = data.path("id").asText("");
        String content = data.path("content").asText("");
        boolean bot = data.path("author").path("bot").asBoolean(false)
                || (selfOpenid != null && selfOpenid.equals(openid));
        return new DirectMessage(openid, messageId, content, bot, QqAttachmentParser.parse(data));
    }

    static GroupMessage group(JsonNode data, String selfOpenid) {
        String groupOpenid = data.path("group_openid").asText("");
        String memberOpenid = data.path("author").path("member_openid").asText("");
        String messageId = data.path("id").asText("");
        String content = data.path("content").asText("");
        boolean bot = data.path("author").path("bot").asBoolean(false)
                || (selfOpenid != null && selfOpenid.equals(memberOpenid));
        return new GroupMessage(groupOpenid, memberOpenid, messageId, content, bot,
                QqAttachmentParser.parse(data));
    }

    record DirectMessage(String openid, String messageId, String content, boolean bot,
                         QqAttachmentParser.Payload attachments) {
    }

    record GroupMessage(String groupOpenid, String memberOpenid, String messageId, String content,
                        boolean bot, QqAttachmentParser.Payload attachments) {
    }
}
