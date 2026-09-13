package com.liche.wechatagent.tool;

import com.liche.wechatagent.channel.WeChatChannel;
import com.liche.wechatagent.channel.OutboundMedia;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 工具状态推送：工具调用前后主动向用户推送状态消息（如「我正在搜索相关资料…」）。
 * 当前用户/bot/通道由 Agent 编排器在处理消息线程上绑定（ThreadLocal）。
 */
@Component
public class ToolStatusService {

    private static final Logger log = LoggerFactory.getLogger(ToolStatusService.class);

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
    private final boolean progressEnabled;
    private final ThreadLocal<StatusContext> current = new ThreadLocal<>();

    @Autowired
    public ToolStatusService(List<WeChatChannel> channels,
                             @Value("${agent.tool-progress-enabled:false}") boolean progressEnabled) {
        this.channels = channels;
        this.progressEnabled = progressEnabled;
    }

    public ToolStatusService(List<WeChatChannel> channels) {
        this(channels, true);
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
        if (!progressEnabled || context == null || context.sent || channels.isEmpty()) {
            return;
        }
        WeChatChannel c = channelFor(context.channel);
        if (c != null) {
            c.sendTextReplyFrom(context.botId, context.userId, context.replyToMsgId, text);
            context.sent = true;
        }
    }

    /**
     * 发一条"我在忙"的提示：**不受 progressEnabled 开关限制**（那是给逐条工具进度用的），
     * 但仍然每轮只发一条（{@code context.sent} 置位后不再重复）。用于升档这种长等待场景。
     */
    public boolean pushNotice(String text) {
        StatusContext context = current.get();
        if (context == null || context.sent || text == null || text.isBlank() || channels.isEmpty()) {
            return false;
        }
        WeChatChannel channel = channelFor(context.channel);
        if (channel == null) {
            return false;
        }
        channel.sendTextReplyFrom(context.botId, context.userId, context.replyToMsgId, text);
        context.sent = true;
        log.info("已发送一次中途提示 user={} text={}", context.userId, text);
        return true;
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
        if (channelName == null || channelName.isBlank()) {
            return null;
        }
        for (WeChatChannel c : channels) {
            if (channelName.equals(c.channel())) {
                return c;
            }
        }
        return null;
    }
}
