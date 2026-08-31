package com.liche.wechatagent.channel;

import java.util.List;

/**
 * 入站消息（网关层产物，统一往下传）。
 *
 * 用户标识约定（全系统唯一隔离键，务必遵守）：
 * - userId：由「网关层（WeChatChannel 实现）」从平台消息中提取的【唯一且不变】的用户身份
 *   （微信 = openid；QQ 私聊 = openid；QQ 群聊 = qq-group:{group_openid}）。它是行级多租户隔离的唯一键：
 *   人设 / 记忆 / 提醒 / 幂等 / 上下文 / 日志 全部按它隔离。
 * - channel：消息来自哪个通道（"wechat"/"qq"/"simulator"），用于回复时路由到对应通道。
 * - botId：消息来自哪个机器人，仅用于通道内回复路由。
 * - msgId：平台消息 id，用于幂等去重（msg_id + userId）。
 * - images：附带图片（URL 或 data URL），仅 QQ 等富媒体通道可能携带。
 */
public record InboundMessage(String msgId, String userId, String content, long timestamp, String type,
                             String botId, String channel, List<String> images, List<InboundAttachment> attachments,
                             String quotedContent, List<String> quotedImages, List<InboundAttachment> quotedAttachments) {

    public InboundMessage(String msgId, String userId, String content, long timestamp, String type,
                          String botId, String channel) {
        this(msgId, userId, content, timestamp, type, botId, channel, List.of(), List.of(), "", List.of(), List.of());
    }

    public static InboundMessage text(String msgId, String userId, String content) {
        return new InboundMessage(msgId, userId, content, System.currentTimeMillis(), "text", null, "simulator", List.of(), List.of(), "", List.of(), List.of());
    }

    public static InboundMessage text(String msgId, String userId, String content, String botId, String channel) {
        return new InboundMessage(msgId, userId, content, System.currentTimeMillis(), "text", botId, channel, List.of(), List.of(), "", List.of(), List.of());
    }

    public static InboundMessage textWithImages(String msgId, String userId, String content, String botId, String channel,
                                                List<String> images) {
        return new InboundMessage(msgId, userId, content, System.currentTimeMillis(), "text", botId, channel, images, List.of(), "", List.of(), List.of());
    }

    public static InboundMessage textWithAttachments(String msgId, String userId, String content, String botId, String channel,
                                                      List<String> images, List<InboundAttachment> attachments) {
        return new InboundMessage(msgId, userId, content, System.currentTimeMillis(), "text", botId, channel, images, attachments, "", List.of(), List.of());
    }

    public static InboundMessage textWithQuote(String msgId, String userId, String content, String botId, String channel,
                                               List<String> images, List<InboundAttachment> attachments,
                                               String quotedContent, List<String> quotedImages, List<InboundAttachment> quotedAttachments) {
        return new InboundMessage(msgId, userId, content, System.currentTimeMillis(), "text", botId, channel,
                images, attachments, quotedContent == null ? "" : quotedContent,
                quotedImages == null ? List.of() : List.copyOf(quotedImages),
                quotedAttachments == null ? List.of() : List.copyOf(quotedAttachments));
    }
}
