package com.liche.wechatagent.log;

import com.liche.wechatagent.channel.InboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Development-only conversation trace. Disabled by default because it writes chat content to logs.
 * Enable it explicitly for a short diagnostic session with conversation.trace.enabled=true.
 */
@Component
public class ConversationTraceLogger {

    private static final Logger log = LoggerFactory.getLogger(ConversationTraceLogger.class);

    private final boolean enabled;
    private final int maxChars;

    public ConversationTraceLogger(@Value("${conversation.trace.enabled:false}") boolean enabled,
                                   @Value("${conversation.trace.max-chars:8000}") int maxChars) {
        this.enabled = enabled;
        this.maxChars = maxChars;
        if (enabled) {
            log.warn("对话追踪已开启：聊天正文将写入本地日志；诊断结束后请重启并关闭该开关");
        }
    }

    public void inbound(InboundMessage message) {
        if (enabled) {
            String quote = message.quotedContent();
            String quoteSuffix = quote == null || quote.isBlank()
                    ? ""
                    : "\n[quoted message] " + clip(quote);
            log.info("[conversation] user={} channel={} msgId={} user: {}{}", message.userId(), message.channel(),
                    message.msgId(), clip(message.content()), quoteSuffix);
        }
    }

    public void assistant(String userId, String message) {
        if (enabled) {
            log.info("[conversation] user={} assistant: {}", userId, clip(message));
        }
    }

    private String clip(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "…[已截断]";
    }
}
