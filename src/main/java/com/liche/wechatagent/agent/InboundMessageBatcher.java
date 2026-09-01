package com.liche.wechatagent.agent;

import com.liche.wechatagent.channel.InboundMessage;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Component
public class InboundMessageBatcher {

    private static final Logger log = LoggerFactory.getLogger(InboundMessageBatcher.class);
    private static final long DEFAULT_MAX_WINDOW_MILLIS = 4_000;
    private static final String DEFAULT_CONTINUATION_PATTERN =
            "^(?:还有|另外|补充|再来|一起|这些|这个|那个|上面|刚才|帮我看看|帮我一起看看|看一下|都在这里).*";

    private static final class PendingBatch {
        private final ArrayList<InboundMessage> messages = new ArrayList<>();
        private final Consumer<InboundMessageBatch> consumer;
        private final long openedAtMillis;
        private ScheduledFuture<?> future;

        private PendingBatch(InboundMessage first, Consumer<InboundMessageBatch> consumer) {
            this.messages.add(first);
            this.consumer = consumer;
            this.openedAtMillis = System.currentTimeMillis();
        }

        private InboundMessageBatch toBatch() {
            return new InboundMessageBatch(messages, null);
        }
    }

    private final ScheduledExecutorService scheduler;
    private final long windowMillis;
    private final long maxWindowMillis;
    private final long continuationWindowMillis;
    private final Pattern continuationPattern;
    private final Object monitor = new Object();
    private final Map<String, PendingBatch> pendingByConversation = new HashMap<>();

    @Autowired
    public InboundMessageBatcher(@Qualifier("messageBatchScheduler") ScheduledExecutorService scheduler,
                                 @Value("${agent.message-batch-window-millis:1500}") long windowMillis,
                                 @Value("${agent.message-batch-max-window-millis:4000}") long maxWindowMillis,
                                 @Value("${agent.message-batch-continuation-window-millis:900}") long continuationWindowMillis,
                                 @Value("${agent.message-batch-continuation-pattern:}") String continuationPattern) {
        this(scheduler, windowMillis, maxWindowMillis, continuationWindowMillis, compilePattern(continuationPattern));
    }

    private InboundMessageBatcher(ScheduledExecutorService scheduler, long windowMillis, long maxWindowMillis,
                                  long continuationWindowMillis, Pattern continuationPattern) {
        this.scheduler = scheduler;
        this.windowMillis = Math.max(0, windowMillis);
        this.maxWindowMillis = Math.max(this.windowMillis, maxWindowMillis);
        this.continuationWindowMillis = Math.max(0, continuationWindowMillis);
        this.continuationPattern = continuationPattern;
    }

    InboundMessageBatcher(ScheduledExecutorService scheduler, long windowMillis) {
        this(scheduler, windowMillis, DEFAULT_MAX_WINDOW_MILLIS, Math.min(windowMillis, 900),
                Pattern.compile(DEFAULT_CONTINUATION_PATTERN));
    }

    public void submit(InboundMessage message, Consumer<InboundMessageBatch> consumer) {
        if (windowMillis == 0 || isCommand(message)) {
            consumer.accept(InboundMessageBatch.single(message));
            return;
        }
        PendingBatch completed = null;
        synchronized (monitor) {
            String key = conversationKey(message);
            PendingBatch pending = pendingByConversation.get(key);
            if (pending != null && canMerge(pending, message)) {
                pending.messages.add(message);
                reschedule(key, pending);
                return;
            }
            if (pending != null) {
                pendingByConversation.remove(key);
                cancel(pending);
                completed = pending;
            }
            PendingBatch created = new PendingBatch(message, consumer);
            pendingByConversation.put(key, created);
            reschedule(key, created);
        }
        if (completed != null) {
            deliver(completed);
        }
    }

    private void reschedule(String key, PendingBatch pending) {
        cancel(pending);
        long remaining = Math.max(0, pending.openedAtMillis + maxWindowMillis - System.currentTimeMillis());
        long delay = Math.min(windowMillis, remaining);
        pending.future = scheduler.schedule(() -> complete(key, pending), delay, TimeUnit.MILLISECONDS);
    }

    private void complete(String key, PendingBatch expected) {
        PendingBatch completed = null;
        synchronized (monitor) {
            if (pendingByConversation.remove(key, expected)) {
                completed = expected;
            }
        }
        if (completed != null) {
            deliver(completed);
        }
    }

    private void deliver(PendingBatch pending) {
        InboundMessageBatch batch = pending.toBatch();
        log.info("消息批次就绪 user={} count={} replyTo={}", batch.userId(), batch.messages().size(), batch.replyToMsgId());
        pending.consumer.accept(batch);
    }

    private boolean canMerge(PendingBatch pending, InboundMessage incoming) {
        if (isCommand(incoming)) {
            return false;
        }
        if (hasMediaOrQuote(incoming)) {
            return true;
        }
        if (!pendingHasMediaOrQuote(pending)) {
            return false;
        }
        if (System.currentTimeMillis() - pending.openedAtMillis > continuationWindowMillis) {
            return false;
        }
        return isContinuation(incoming.content());
    }

    private boolean hasMediaOrQuote(InboundMessage message) {
        return (message.images() != null && !message.images().isEmpty())
                || (message.attachments() != null && !message.attachments().isEmpty())
                || (message.quotedImages() != null && !message.quotedImages().isEmpty())
                || (message.quotedAttachments() != null && !message.quotedAttachments().isEmpty())
                || (message.quotedContent() != null && !message.quotedContent().isBlank());
    }

    private boolean isCommand(InboundMessage message) {
        return message.content() != null && message.content().trim().startsWith("/");
    }

    private boolean isContinuation(String content) {
        if (content == null || content.isBlank()) {
            return true;
        }
        return continuationPattern != null && continuationPattern.matcher(content.strip()).matches();
    }

    private boolean pendingHasMediaOrQuote(PendingBatch pending) {
        return pending.messages.stream().anyMatch(this::hasMediaOrQuote);
    }

    private static Pattern compilePattern(String expression) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        try {
            return Pattern.compile(expression, Pattern.DOTALL);
        } catch (PatternSyntaxException exception) {
            log.warn("消息批处理补充消息规则无效，将关闭文本补充聚合: {}", exception.getDescription());
            return null;
        }
    }

    private String conversationKey(InboundMessage message) {
        return (message.channel() == null ? "" : message.channel()) + '\u0000'
                + (message.botId() == null ? "" : message.botId()) + '\u0000'
                + message.userId();
    }

    private void cancel(PendingBatch pending) {
        if (pending.future != null) {
            pending.future.cancel(false);
            pending.future = null;
        }
    }

    @PreDestroy
    void clear() {
        synchronized (monitor) {
            pendingByConversation.values().forEach(this::cancel);
            pendingByConversation.clear();
        }
    }
}
