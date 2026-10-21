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
        return new DirectMessage(openid, messageId, content, bot, QqAttachmentParser.parse(data),
                platformTime(data));
    }

    static GroupMessage group(JsonNode data, String selfOpenid) {
        String groupOpenid = data.path("group_openid").asText("");
        String memberOpenid = data.path("author").path("member_openid").asText("");
        String messageId = data.path("id").asText("");
        String content = data.path("content").asText("");
        boolean bot = data.path("author").path("bot").asBoolean(false)
                || (selfOpenid != null && selfOpenid.equals(memberOpenid));
        return new GroupMessage(groupOpenid, memberOpenid, messageId, content, bot,
                QqAttachmentParser.parse(data), platformTime(data));
    }

    /**
     * 平台给的**发送时间**（QQ v2 事件里的 {@code timestamp}，RFC3339）。
     *
     * <p>为什么要它：一次选三张图发出去，平台会拆成**三条独立消息**、没有"批次 id"；
     * 但它们的发送时间来自同一次操作，跟"到达我们这里的时间"可能差好几秒（上传慢）。
     * 用发送时间才能判断"这些是一起发的"，而不是只靠到达间隔猜。
     *
     * @return epoch 毫秒；解析不出来返回 0（调用方回退成到达时间）
     */
    static long platformTime(JsonNode data) {
        String raw = data.path("timestamp").asText("");
        if (raw.isBlank()) {
            return 0L;
        }
        try {
            return java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli();
        } catch (RuntimeException ignored) {
            // 也可能给的是秒/毫秒时间戳字符串，下面兜底
        }
        try {
            long value = Long.parseLong(raw.trim());
            return value > 1_000_000_000_000L ? value : value * 1000L;
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    record DirectMessage(String openid, String messageId, String content, boolean bot,
                         QqAttachmentParser.Payload attachments, long platformTime) {
    }

    record GroupMessage(String groupOpenid, String memberOpenid, String messageId, String content,
                        boolean bot, QqAttachmentParser.Payload attachments, long platformTime) {
    }
}
