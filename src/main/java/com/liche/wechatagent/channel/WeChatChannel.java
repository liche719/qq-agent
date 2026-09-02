package com.liche.wechatagent.channel;

import com.liche.wechatagent.agent.StreamReplySink;

/** 消息通道抽象：负责「平台消息 <-> 统一 userId」转换与消息发送 */
public interface WeChatChannel {

    /** 通道名（"wechat"/"qq"/"simulator"），用于回复路由 */
    default String channel() {
        return "wechat";
    }

    void sendText(String userId, String text);

    /** 多机器人场景：指定由哪个 bot(botId) 发送；默认实现退化为 sendText（单 bot 兼容） */
    default void sendTextFrom(String botId, String userId, String text) {
        sendText(userId, text);
    }

    /**
     * Sends text and reports whether the channel accepted the request.  The
     * legacy void method remains for channel implementations that cannot expose
     * transport status.
     */
    default boolean sendTextResultFrom(String botId, String userId, String text) {
        sendTextFrom(botId, userId, text);
        return true;
    }

    /** Whether the channel can distinguish transport failure from acceptance. */
    default boolean hasReliableSendStatus() {
        return false;
    }

    /**
     * 回复指定的一条入站消息。支持引用回复的通道必须使用 replyToMsgId，避免同一用户连续发消息时
     * 把较早请求的结果错误挂到最新消息上；不支持的通道可退化为普通发送。
     */
    default void sendTextReplyFrom(String botId, String userId, String replyToMsgId, String text) {
        sendTextFrom(botId, userId, text);
    }

    /** Reply variant that reports transport acceptance when the channel supports it. */
    default boolean sendTextReplyResultFrom(String botId, String userId, String replyToMsgId, String text) {
        sendTextReplyFrom(botId, userId, replyToMsgId, text);
        return true;
    }

    default boolean sendMediaReplyFrom(String botId, String userId, String replyToMsgId, OutboundMedia media) {
        return false;
    }

    /** Best-effort removal of a bot message previously sent in this conversation. */
    default boolean deleteMessage(String botId, String userId, String messageId) {
        return false;
    }

    /** 该通道是否负责给此用户发消息（返回 botId；不属于本通道返回 null） */
    default String botIdForUser(String userId) {
        return null;
    }

    /** Whether this channel may deliver unsolicited care messages to the conversation. */
    default boolean supportsProactiveCare(String userId) {
        return true;
    }

    /**
     * 创建流式回复接收器（如 QQ stream_messages）；通道不支持流式时返回 null。
     * 返回非 null 时，AgentLoop 会把最终回复按分块推送进来（onPartial/onDone）。
     */
    default StreamReplySink createStreamSink(String userId, String msgId) {
        return null;
    }

    default void start() {
    }

    default void shutdown() {
    }
}
