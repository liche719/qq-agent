package com.liche.wechatagent.memory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.liche.wechatagent.user.UserService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 记忆自动提取触发：每轮对话结束后开启一段静默窗口（默认 180 秒）；
 * 窗口内用户又说话了 → 取消并重置（所以**一场连续对话只会提取几次**，不是每发一条就调一次模型）。
 *
 * <p>**为什么要有"最多拖多久"**：只按静默窗口算的话，用户一直聊下去就永远不触发（每次新消息都把窗口推后）。
 * 所以再加一个 {@code memory.extraction-max-delay-seconds}（默认 300 秒）：从"第一次排队"起算，到点就必须跑一次。
 *
 * <p>**最小间隔**（{@code memory.extraction-min-interval-seconds}，默认 180 秒，2026-09-17 加）：
 * 高频短对话里，静默窗口会被反复重置又反复满足，导致"每两三分钟就提取一次"。这个闸保证同一个用户
 * 两次提取之间至少隔这么久——触发不会丢，只是往后挪。
 *
 * <p>**它还记得"这一轮用户说了什么"**（{@code burstTexts}）：提取前的"事务型窄跳过"要按**新消息**判定
 * （问课表、设提醒、元问题这类不值得花钱），而不是按最近 20 轮的整窗——整窗里混着旧内容，判定会失准。
 * 这些文本只在内存里，重启即丢；丢了也只是退回"整窗判定"，不会漏记。
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
    private final int minIntervalSeconds;
    private final UserService userService;
    private final int retryAttempts;
    private final int retryDelaySeconds;
    private final ConcurrentMap<String, PendingExtraction> pendingByUser = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> generations = new ConcurrentHashMap<>();
    /** 上一次真的跑完提取的时刻（毫秒）——最小间隔从它算起 */
    private final ConcurrentMap<String, Long> lastRunMillis = new ConcurrentHashMap<>();
    /** 这一轮静默窗口里用户说过的话（判"事务型窗口"用） */
    private final ConcurrentMap<String, List<String>> burstTexts = new ConcurrentHashMap<>();
    private final AtomicLong generationSequence = new AtomicLong();

    @Autowired
    public MemoryExtractionScheduler(@Qualifier("memoryExtractionThreadPool") ScheduledExecutorService memoryExtractionScheduler,
                                     MemoryExtractor extractor,
                                     UserService userService,
                                     @Value("${memory.extraction-window-seconds:180}") int windowSeconds,
                                     @Value("${memory.extraction-max-delay-seconds:300}") int maxDelaySeconds,
                                     @Value("${memory.extraction-min-interval-seconds:180}") int minIntervalSeconds,
                                     @Value("${memory.extraction-retry-attempts:2}") int retryAttempts,
                                     @Value("${memory.extraction-retry-delay-seconds:15}") int retryDelaySeconds) {
        this.scheduler = memoryExtractionScheduler;
        this.extractor = extractor;
        this.userService = userService;
        this.windowSeconds = Math.max(0, windowSeconds);
        this.maxDelaySeconds = Math.max(this.windowSeconds, maxDelaySeconds);
        this.minIntervalSeconds = Math.max(0, minIntervalSeconds);
        this.retryAttempts = Math.max(0, retryAttempts);
        this.retryDelaySeconds = Math.max(0, retryDelaySeconds);
    }

    MemoryExtractionScheduler(ScheduledExecutorService memoryExtractionScheduler,
                              MemoryExtractor extractor,
                              UserService userService,
                              int windowSeconds) {
        this(memoryExtractionScheduler, extractor, userService, windowSeconds, windowSeconds * 3, 0, 2, 15);
    }

    /** 只带 userId 的旧入口（不知道用户说了什么，退化为"整窗判定"） */
    public void schedule(String userId) {
        schedule(userId, null);
    }

    /**
     * 排一次提取。
     *
     * @param userText 这一轮用户说的话（可为空）；同一个窗口里多次调用会累积起来，供"事务型窄跳过"判定
     */
    public void schedule(String userId, String userText) {
        if (!userService.isMemoryEnabled(userId)) {
            cancelPending(userId);
            return;
        }
        rememberBurstText(userId, userText);
        long now = System.currentTimeMillis();
        long minIntervalMillis = minIntervalSeconds * 1000L;
        Long last = lastRunMillis.get(userId);
        if (last != null && minIntervalMillis > 0 && now - last < minIntervalMillis) {
            // 距上次提取太近：把这次触发推到"上次 + 最小间隔"（不丢，只是晚点跑）
            long wait = minIntervalMillis - (now - last);
            long generation = generationSequence.incrementAndGet();
            generations.put(userId, generation);
            cancelFuture(userId);
            scheduleAttempt(userId, generation, 0, wait, now);
            return;
        }
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
        burstTexts.remove(userId);
        cancelFuture(userId);
    }

    private void rememberBurstText(String userId, String userText) {
        if (userText == null || userText.isBlank()) {
            return;
        }
        String text = userText.trim();
        List<String> texts = burstTexts.computeIfAbsent(userId,
                key -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (texts) {
            if (texts.size() < 20 && (texts.isEmpty() || !text.equals(texts.get(texts.size() - 1)))) {
                texts.add(text);
            }
        }
    }

    private List<String> takeBurstTexts(String userId) {
        List<String> texts = burstTexts.remove(userId);
        if (texts == null) {
            return List.of();
        }
        synchronized (texts) {
            return List.copyOf(texts);
        }
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
        List<String> burst = takeBurstTexts(userId);
        boolean completed = extractor.extract(userId, burst,
                () -> isCurrentGeneration(userId, pending.generation) && userService.isMemoryEnabled(userId));
        lastRunMillis.put(userId, System.currentTimeMillis());
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
