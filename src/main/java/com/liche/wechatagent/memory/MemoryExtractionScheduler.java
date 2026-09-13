package com.liche.wechatagent.memory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.liche.wechatagent.user.UserService;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 记忆自动提取触发：每轮对话结束后开启一段静默窗口（默认 45 秒）；
 * 窗口内用户又说话了 → 取消并重置（所以**一场连续对话只会提取几次**，不是每发一条就调一次模型）。
 *
 * <p>**为什么要有"最多拖多久"**：只按静默窗口算的话，用户一直聊下去就永远不触发（每次新消息都把窗口推后）。
 * 所以再加一个 {@code memory.extraction-max-delay-seconds}（默认 150 秒）：从"第一次排队"起算，到点就必须跑一次。
 */
@Component
public class MemoryExtractionScheduler {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionScheduler.class);

    private static final class PendingExtraction {
        private final long generation;
        private final int attempt;
        /** 这一轮排队的起点（毫秒）：max-delay 从它算起，反复被推后也不会超过它 + maxDelay */
        private final long anchorMillis;
        private volatile ScheduledFuture<?> future;

        private PendingExtraction(long generation, int attempt, long anchorMillis) {
            this.generation = generation;
            this.attempt = attempt;
            this.anchorMillis = anchorMillis;
        }
    }

    private final ScheduledExecutorService scheduler;
    private final MemoryExtractor extractor;
    private final int windowSeconds;
    private final int maxDelaySeconds;
    private final UserService userService;
    private final int retryAttempts;
    private final int retryDelaySeconds;
    private final ConcurrentMap<String, PendingExtraction> pendingByUser = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> generations = new ConcurrentHashMap<>();
    private final AtomicLong generationSequence = new AtomicLong();

    @Autowired
    public MemoryExtractionScheduler(@Qualifier("memoryExtractionThreadPool") ScheduledExecutorService memoryExtractionScheduler,
                                     MemoryExtractor extractor,
                                     UserService userService,
                                     @Value("${memory.extraction-window-seconds:45}") int windowSeconds,
                                     @Value("${memory.extraction-max-delay-seconds:150}") int maxDelaySeconds,
                                     @Value("${memory.extraction-retry-attempts:2}") int retryAttempts,
                                     @Value("${memory.extraction-retry-delay-seconds:15}") int retryDelaySeconds) {
        this.scheduler = memoryExtractionScheduler;
        this.extractor = extractor;
        this.userService = userService;
        this.windowSeconds = Math.max(0, windowSeconds);
        this.maxDelaySeconds = Math.max(this.windowSeconds, maxDelaySeconds);
        this.retryAttempts = Math.max(0, retryAttempts);
        this.retryDelaySeconds = Math.max(0, retryDelaySeconds);
    }

    MemoryExtractionScheduler(ScheduledExecutorService memoryExtractionScheduler,
                              MemoryExtractor extractor,
                              UserService userService,
                              int windowSeconds) {
        this(memoryExtractionScheduler, extractor, userService, windowSeconds, windowSeconds * 3, 2, 15);
    }

    public void schedule(String userId) {
        if (!userService.isMemoryEnabled(userId)) {
            cancelPending(userId);
            return;
        }
        long now = System.currentTimeMillis();
        PendingExtraction existing = pendingByUser.get(userId);
        // 已经排着队的，沿用它的起点：连续聊天时窗口一直往后推，但不会超过 anchor + maxDelay
        long anchor = existing == null ? now : existing.anchorMillis;
        long remainingBudget = Math.max(0L, anchor + maxDelaySeconds * 1000L - now);
        long delayMillis = Math.min(windowSeconds * 1000L, remainingBudget);
        long generation = generationSequence.incrementAndGet();
        generations.put(userId, generation);
        cancelFuture(userId);
        scheduleAttempt(userId, generation, 0, delayMillis, anchor);
    }

    public void cancelPending(String userId) {
        generations.remove(userId);
        cancelFuture(userId);
    }

    private void scheduleAttempt(String userId, long generation, int attempt, long delayMillis, long anchorMillis) {
        PendingExtraction pending = new PendingExtraction(generation, attempt, anchorMillis);
        PendingExtraction previous = pendingByUser.put(userId, pending);
        if (previous != null && previous != pending && previous.future != null) {
            previous.future.cancel(false);
        }
        pending.future = scheduler.schedule(() -> runAttempt(userId, pending), Math.max(0L, delayMillis),
                TimeUnit.MILLISECONDS);
    }

    private void runAttempt(String userId, PendingExtraction pending) {
        if (!pendingByUser.remove(userId, pending) || !isCurrentGeneration(userId, pending.generation)) {
            return;
        }
        boolean completed = extractor.extract(userId,
                () -> isCurrentGeneration(userId, pending.generation) && userService.isMemoryEnabled(userId));
        if (completed) {
            generations.remove(userId, pending.generation);
            return;
        }
        if (!isCurrentGeneration(userId, pending.generation) || !userService.isMemoryEnabled(userId)) {
            return;
        }
        if (pending.attempt >= retryAttempts) {
            generations.remove(userId, pending.generation);
            log.warn("记忆提取多次失败，等待下一轮对话重新触发 user={} attempts={}", userId, pending.attempt + 1);
            return;
        }
        int nextAttempt = pending.attempt + 1;
        log.warn("记忆提取失败，将在 {} 秒后重试 user={} attempt={}/{}", retryDelaySeconds, userId,
                nextAttempt, retryAttempts);
        scheduleAttempt(userId, pending.generation, nextAttempt, retryDelaySeconds * 1000L,
                System.currentTimeMillis());
    }

    private boolean isCurrentGeneration(String userId, long generation) {
        return Long.valueOf(generation).equals(generations.get(userId));
    }

    private void cancelFuture(String userId) {
        PendingExtraction pending = pendingByUser.remove(userId);
        if (pending != null && pending.future != null) {
            pending.future.cancel(false);
        }
    }
}
