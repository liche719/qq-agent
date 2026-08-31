package com.liche.wechatagent.tool;

import com.liche.wechatagent.channel.WeChatChannel;
import com.liche.wechatagent.channel.OutboundMedia;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 工具状态推送：工具调用前后主动向用户推送状态消息（如「我正在搜索相关资料…」）。
 * 当前用户/bot/通道由 Agent 编排器在处理消息线程上绑定（ThreadLocal）。
 */
@Component
public class ToolStatusService {

    private static final class StatusContext {
        private final String userId;
        private final String replyToMsgId;
        private String botId;
        private String channel;
        private int depth = 1;
        private boolean sent;

        private StatusContext(String userId, String replyToMsgId, String botId, String channel) {
            this.userId = userId;
            this.replyToMsgId = replyToMsgId;
            this.botId = botId;
            this.channel = channel;
        }
    }

    private final List<WeChatChannel> channels;
    private final ThreadLocal<StatusContext> current = new ThreadLocal<>();

    public ToolStatusService(List<WeChatChannel> channels) {
        this.channels = channels;
    }

    public void bind(String userId, String replyToMsgId, String botId, String channel) {
        StatusContext existing = current.get();
        if (existing != null && existing.userId.equals(userId)) {
            existing.depth++;
            existing.botId = botId;
            existing.channel = channel;
            return;
        }
        current.set(new StatusContext(userId, replyToMsgId, botId, channel));
    }

    public void unbind() {
        StatusContext context = current.get();
        if (context == null || --context.depth <= 0) {
            current.remove();
        }
    }

    public String currentUserId() {
        StatusContext context = current.get();
        return context == null ? null : context.userId;
    }

    public void push(String text) {
        StatusContext context = current.get();
        if (context == null || context.sent || channels.isEmpty()) {
            return;
        }
        WeChatChannel c = channelFor(context.channel);
        if (c != null) {
            c.sendTextReplyFrom(context.botId, context.userId, context.replyToMsgId, text);
            context.sent = true;
        }
    }

    public boolean sendMedia(OutboundMedia media) {
        StatusContext context = current.get();
        if (context == null || media == null) {
            return false;
        }
        WeChatChannel channel = channelFor(context.channel);
        return channel != null && channel.sendMediaReplyFrom(context.botId, context.userId,
                context.replyToMsgId, media);
    }

    private WeChatChannel channelFor(String channelName) {
        if (channelName != null) {
            for (WeChatChannel c : channels) {
                if (channelName.equals(c.channel())) {
                    return c;
                }
            }
        }
        return channels.get(0);
    }
}
