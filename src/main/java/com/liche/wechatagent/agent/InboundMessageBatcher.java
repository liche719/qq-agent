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
        /** 这一批第一条的**平台发送时间**（判断"后面来的这条是不是同一次发出的"） */
        private final long firstSentAt;
        private ScheduledFuture<?> future;

        private PendingBatch(InboundMessage first, Consumer<InboundMessageBatch> consumer) {
            this.messages.add(first);
            this.consumer = consumer;
            this.openedAtMillis = System.currentTimeMillis();
            this.firstSentAt = first.timestamp();
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
    /**
     * 窗口内**任何**消息都合并（2026-10-21 用户要求）。
     *
     * <p>原来只有"带媒体/引用"的消息能并进同一批，纯文字连发会被当成两轮、各回一次，
     * 看起来像机器人逐条应答。人不是这么聊的——所以默认开启，配 {@code agent.message-batch-merge-text=false} 可退回旧行为。
     */
    private final boolean mergeText;
    /**
     * 一次"发送动作"里被平台拆成的多条消息，发送时间几乎相同（2026-10-21 用户要求）。
     *
     * <p>典型场景：相册里选三张图一起发，平台拆成三条消息，**没有批次 id**；上传慢的时候
     * 到达间隔能有几秒。只看到达间隔会把它们拆成两轮、回两次。这里的判断用**平台发送时间**：
     * 与这批第一条相差 {@code sameBatchMillis} 以内，就算同一次发出。
     */
    private final long sameBatchMillis;
    /** 带媒体的批次要等更久：上传是三张图依次到达，不能前两张一到就回复 */
    private final long mediaWindowMillis;
    private final long mediaMaxWindowMillis;
    private final Object monitor = new Object();
    private final Map<String, PendingBatch> pendingByConversation = new HashMap<>();

    @Autowired
    public InboundMessageBatcher(@Qualifier("messageBatchScheduler") ScheduledExecutorService scheduler,
                                 @Value("${agent.message-batch-window-millis:2500}") long windowMillis,
                                 @Value("${agent.message-batch-max-window-millis:8000}") long maxWindowMillis,
                                 @Value("${agent.message-batch-media-window-millis:5000}") long mediaWindowMillis,
                                 @Value("${agent.message-batch-media-max-window-millis:20000}") long mediaMaxWindowMillis,
                                 @Value("${agent.message-batch-same-batch-millis:1500}") long sameBatchMillis,
                                 @Value("${agent.message-batch-continuation-window-millis:900}") long continuationWindowMillis,
                                 @Value("${agent.message-batch-continuation-pattern:}") String continuationPattern,
                                 @Value("${agent.message-batch-merge-text:true}") boolean mergeText) {
        this(scheduler, windowMillis, maxWindowMillis, mediaWindowMillis, mediaMaxWindowMillis,
                sameBatchMillis, continuationWindowMillis, compilePattern(continuationPattern), mergeText);
    }

    private InboundMessageBatcher(ScheduledExecutorService scheduler, long windowMillis, long maxWindowMillis,
                                  long mediaWindowMillis, long mediaMaxWindowMillis, long sameBatchMillis,
                                  long continuationWindowMillis, Pattern continuationPattern, boolean mergeText) {
        this.scheduler = scheduler;
        this.windowMillis = Math.max(0, windowMillis);
        this.maxWindowMillis = Math.max(this.windowMillis, maxWindowMillis);
        this.mediaWindowMillis = Math.max(this.windowMillis, mediaWindowMillis);
        this.mediaMaxWindowMillis = Math.max(this.mediaWindowMillis, mediaMaxWindowMillis);
        this.sameBatchMillis = Math.max(0, sameBatchMillis);
        this.continuationWindowMillis = Math.max(0, continuationWindowMillis);
        this.continuationPattern = continuationPattern;
        this.mergeText = mergeText;
    }

    InboundMessageBatcher(ScheduledExecutorService scheduler, long windowMillis) {
        this(scheduler, windowMillis, DEFAULT_MAX_WINDOW_MILLIS, Math.max(windowMillis, 5000),
                Math.max(windowMillis, 20000), 1500, Math.min(windowMillis, 900),
                Pattern.compile(DEFAULT_CONTINUATION_PATTERN), false);
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
        // 带媒体的批次等更久：相册里一起发三张图，它们是**一条条**到达的，
        // 前两张一到就回复会变成"回两次"（2026-10-21 用户反馈）
        boolean media = pendingHasMediaOrQuote(pending);
        long window = media ? mediaWindowMillis : windowMillis;
        long max = media ? mediaMaxWindowMillis : maxWindowMillis;
        long remaining = Math.max(0, pending.openedAtMillis + max - System.currentTimeMillis());
        long delay = Math.min(window, remaining);
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
        InboundMessage last = pending.messages.get(pending.messages.size() - 1);
        // 记下"这一批里最早/最晚的平台发送时间"，出问题时能看出是不是同一次发出来的
        log.info("消息批次就绪 user={} count={} replyTo={} sentAtSpanMs={} waitedMs={}",
                batch.userId(), batch.messages().size(), batch.replyToMsgId(),
                Math.max(0, last.timestamp() - pending.firstSentAt),
                Math.max(0, System.currentTimeMillis() - pending.openedAtMillis));
        pending.consumer.accept(batch);
    }

    private boolean canMerge(PendingBatch pending, InboundMessage incoming) {
        if (isCommand(incoming)) {
            return false;
        }
        // 平台发送时间几乎相同 = 同一次发送动作（相册选三张图），跟到达间隔无关（2026-10-21）
        if (sameBatchMillis > 0 && Math.abs(incoming.timestamp() - pending.firstSentAt) <= sameBatchMillis) {
            return true;
        }
        // 连发合并：窗口内的消息（文字、图片、视频混着发）当同一轮处理，一起回一次
        if (mergeText) {
            return true;
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
