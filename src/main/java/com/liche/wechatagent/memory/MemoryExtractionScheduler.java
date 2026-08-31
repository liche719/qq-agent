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
 * 记忆自动提取触发：每轮对话结束后开启 3 秒静默窗口；
 * 3 秒内用户无新消息 → 触发异步 LLM 提取；有新消息 → 取消并重置。
 */
@Component
public class MemoryExtractionScheduler {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionScheduler.class);

    private static final class PendingExtraction {
        private final long generation;
        private final int attempt;
        private volatile ScheduledFuture<?> future;

        private PendingExtraction(long generation, int attempt) {
            this.generation = generation;
            this.attempt = attempt;
        }
    }

    private final ScheduledExecutorService scheduler;
    private final MemoryExtractor extractor;
    private final int windowSeconds;
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
                                     @Value("${memory.extraction-window-seconds:3}") int windowSeconds,
                                     @Value("${memory.extraction-retry-attempts:2}") int retryAttempts,
                                     @Value("${memory.extraction-retry-delay-seconds:15}") int retryDelaySeconds) {
        this.scheduler = memoryExtractionScheduler;
        this.extractor = extractor;
        this.userService = userService;
        this.windowSeconds = Math.max(0, windowSeconds);
        this.retryAttempts = Math.max(0, retryAttempts);
        this.retryDelaySeconds = Math.max(0, retryDelaySeconds);
    }

    MemoryExtractionScheduler(ScheduledExecutorService memoryExtractionScheduler,
                              MemoryExtractor extractor,
                              UserService userService,
                              int windowSeconds) {
        this(memoryExtractionScheduler, extractor, userService, windowSeconds, 2, 15);
    }

    public void schedule(String userId) {
        if (!userService.isMemoryEnabled(userId)) {
            cancelPending(userId);
            return;
        }
        long generation = generationSequence.incrementAndGet();
        generations.put(userId, generation);
        cancelFuture(userId);
        scheduleAttempt(userId, generation, 0, windowSeconds);
    }

    public void cancelPending(String userId) {
        generations.remove(userId);
        cancelFuture(userId);
    }

    private void scheduleAttempt(String userId, long generation, int attempt, int delaySeconds) {
        PendingExtraction pending = new PendingExtraction(generation, attempt);
        PendingExtraction previous = pendingByUser.put(userId, pending);
        if (previous != null && previous != pending && previous.future != null) {
            previous.future.cancel(false);
        }
        pending.future = scheduler.schedule(() -> runAttempt(userId, pending), delaySeconds, TimeUnit.SECONDS);
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
        scheduleAttempt(userId, pending.generation, nextAttempt, retryDelaySeconds);
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
