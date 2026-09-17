package com.liche.wechatagent.memory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.liche.wechatagent.user.UserService;

import java.time.Duration;
import java.time.LocalDateTime;
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
 * 记忆自动提取的触发（**2026-09-18 换成"轮次驱动 + 时间兜底"**）。
 *
 * <p>旧机制是"静默窗口"：每轮对话后开一个 180 秒的窗口，窗口内又说话就重置（最多拖 300 秒）。
 * 它有两个毛病：**① 一场连续对话里跑很多次**（实测 1.5 小时 13 次、0.13~0.19 元，且一半是空转），
 * **② 边界恰好切在话说到一半的地方**（"那改成 305 吧"被切到下一窗，模型看不到在改哪件事）。
 *
 * <p>现在的两个触发条件（任一满足就排一次，真正跑之前还会合并一小段时间 + 过最小间隔）：
 * <ol>
 *   <li><b>轮次</b>：上一次提取之后机主又说了 {@code memory.extraction-rounds}（默认 15）条 → 跑；</li>
 *   <li><b>兜底</b>：待提取窗口里最早那条已经放了 {@code memory.extraction-max-idle-hours}（默认 6）小时 → 跑，
 *       避免"聊了两句就安静了"的记忆一直不进库。</li>
 * </ol>
 * （原先还有第三个条件"用户说「记住」就立刻跑"，**2026-09-18 用户明确要求删掉**：不要这种特例。）
 *
 * <p><b>轮次计数放在 Redis</b>（用户 2026-09-18 提的）：每来一条消息 {@code INCR memory:pending:&lt;user&gt;}，
 * 提取真跑完就删掉。比每次查库便宜，而且 Redis 开了 AOF，重启不丢。
 * **键丢了（Redis 被清空 / 过期）就回退查库**：按"上一次生效的提取"之后的机主消息数重新数一遍并把键种回去，
 * 所以既快又不会因为 Redis 没了就卡住不提取。
 *
 * <p>**边界**（"上次看到哪儿为止"那条时间线）仍然从库里算，只在 Redis 计数缺失时用：
 * 取最近一次**真正生效**的提取记录，用它的**开始**时刻（不是结束时刻——提取要跑十几秒，
 * 这期间说的话既不在那次窗口里、时间上又早于结束时刻，用结束时刻当边界它们就永远轮不到提取了）；
 * {@code FAILED}（失败）和 {@code STALE}（结果被丢弃）都**不算处理过**。
 *
 * <p>**上下文重叠**：窗口取最近 {@code memory.extraction-recent-turns}（默认 40 行）条对话，
 * 其中上次已经处理过的那几条机主消息**只作背景**（提取提示词里会标出来，规则 14），
 * 这样跨窗口的一句话仍能被正确理解，又不会把旧内容反复提取成新记忆。
 *
 * <p>**它还记得"这一轮用户说了什么"**（{@code burstTexts}）：提取前的"事务型窄跳过"要按**新消息**判定
 * （问课表、设提醒、元问题这类不值得花钱），而不是按整窗——整窗里混着旧内容，判定会失准。
 * 这些文本只在内存里，重启即丢；丢了也只是退回"整窗判定"，不会漏记。
 */
@Component
public class MemoryExtractionScheduler {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionScheduler.class);

    /**
     * 早于这个时间的都算"没有上一次提取"（不能用 LocalDateTime.MIN：PG 的 timestamp 存不下）
     */
    private static final LocalDateTime EPOCH = LocalDateTime.of(1970, 1, 1, 0, 0);
    /** 轮次计数（Redis）：上一次提取之后机主说了几条 */
    private static final String KEY_PENDING = "memory:pending:";
    /** 待提取窗口最早那条的时间（Redis，判"拖了 6 小时"用） */
    private static final String KEY_PENDING_SINCE = "memory:pending-since:";
    /** 计数键的兜底寿命：真提取过就会删，留着只是防止 Redis 里堆垃圾 */
    private static final Duration KEY_TTL = Duration.ofDays(7);

    private static final class PendingExtraction {
        private final long generation;
        private final int attempt;
        /** 这一轮排队的起点（毫秒）：合并窗口从它算起，反复被推后也不会超过它 + coalesce */
        private final long anchorMillis;
        private volatile ScheduledFuture<?> future;

        private PendingExtraction(long generation, int attempt, long anchorMillis) {
            this.generation = generation;
            this.attempt = attempt;
            this.anchorMillis = anchorMillis;
        }
    }

    /** 待提取窗口：攒了多少条机主消息、最早那条是什么时候 */
    private record Pending(long turns, LocalDateTime oldest) {
        private static final Pending NONE = new Pending(0, null);
    }
    private final ScheduledExecutorService scheduler;
    private final MemoryExtractor extractor;
    private final UserService userService;
    private final StringRedisTemplate redis;
    private final ConversationMemoryRepository conversationMemory;
    private final MemoryExtractionRunRepository runs;
    private final int rounds;
    private final int maxIdleHours;
    private final int coalesceSeconds;
    private final int minIntervalSeconds;
    private final int retryAttempts;
    private final int retryDelaySeconds;
    private final ConcurrentMap<String, PendingExtraction> pendingByUser = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> generations = new ConcurrentHashMap<>();
    /** 上一次真的跑完提取的时刻（毫秒）——最小间隔从它算起 */
    private final ConcurrentMap<String, Long> lastRunMillis = new ConcurrentHashMap<>();
    /** 这一轮窗口里用户说过的话（判"事务型窗口"用，同时决定"最老几条只作背景"） */
    private final ConcurrentMap<String, List<String>> burstTexts = new ConcurrentHashMap<>();
    /** 正在跑提取的用户：跑的时候**不改代号**（否则新消息会把这一趟打断、白花一次调用） */
    private final java.util.Set<String> runningUsers = ConcurrentHashMap.newKeySet();
    private final AtomicLong generationSequence = new AtomicLong();

    @Autowired
    public MemoryExtractionScheduler(@Qualifier("memoryExtractionThreadPool") ScheduledExecutorService memoryExtractionScheduler,
                                     MemoryExtractor extractor,
                                     UserService userService,
                                     StringRedisTemplate redis,
                                     ConversationMemoryRepository conversationMemory,
                                     MemoryExtractionRunRepository runs,
                                     @Value("${memory.extraction-rounds:15}") int rounds,
                                     @Value("${memory.extraction-max-idle-hours:6}") int maxIdleHours,
                                     @Value("${memory.extraction-coalesce-seconds:10}") int coalesceSeconds,
                                     @Value("${memory.extraction-min-interval-seconds:180}") int minIntervalSeconds,
                                     @Value("${memory.extraction-retry-attempts:2}") int retryAttempts,
                                     @Value("${memory.extraction-retry-delay-seconds:15}") int retryDelaySeconds) {
        this.scheduler = memoryExtractionScheduler;
        this.extractor = extractor;
        this.userService = userService;
        this.redis = redis;
        this.conversationMemory = conversationMemory;
        this.runs = runs;
        this.rounds = Math.max(1, rounds);
        this.maxIdleHours = Math.max(1, maxIdleHours);
        this.coalesceSeconds = Math.max(0, coalesceSeconds);
        this.minIntervalSeconds = Math.max(0, minIntervalSeconds);
        this.retryAttempts = Math.max(0, retryAttempts);
        this.retryDelaySeconds = Math.max(0, retryDelaySeconds);
    }

    /**
     * 单测/降级用的便捷构造器：**没有 Redis 也没有库，判定不了轮次**，退化成"每次调用都排一次"
     * （这样重试这类与触发策略无关的行为仍然可测）。
     */
    MemoryExtractionScheduler(ScheduledExecutorService memoryExtractionScheduler,
                              MemoryExtractor extractor,
                              UserService userService,
                              int coalesceSeconds,
                              int minIntervalSeconds,
                              int retryAttempts,
                              int retryDelaySeconds) {
        this(memoryExtractionScheduler, extractor, userService, null, null, null,
                0, 1, coalesceSeconds, minIntervalSeconds, retryAttempts, retryDelaySeconds);
    }

    /** 只带 userId 的旧入口（不知道用户说了什么，退化为"整窗判定"） */
    public void schedule(String userId) {
        schedule(userId, null);
    }

    /**
     * 一轮对话结束后调用：先把"这一轮说了什么"记下来并给 Redis 计数 +1，再看够不够触发。
     *
     * @param userText 这一轮用户说的话（可为空）；同一个窗口里多次调用会累积起来，供"事务型窄跳过"判定
     */
    public void schedule(String userId, String userText) {
        if (!userService.isMemoryEnabled(userId)) {
            cancelPending(userId);
            return;
        }
        rememberBurstText(userId, userText);
        bumpPending(userId);
        String reason = decide(userId);
        if (reason == null) {
            // 轮次没攒够、也没超时：什么都不排（这正是省下那 13 次/1.5 小时的地方）
            return;
        }
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
        if (runningUsers.contains(userId)) {
            // 这一趟正在跑：别打断它（跑完会自己再看一眼窗口还满不满），
            // 也不能改代号——改了会让正在跑的这趟被判成"过期"，白花一次调用还不写回
            return;
        }
        PendingExtraction existing = pendingByUser.get(userId);
        // 已经排着队的，沿用它的起点：连续消息只会把这次往后并一点，不会超过 anchor + coalesce
        long anchor = existing == null ? now : existing.anchorMillis;
        long remainingBudget = Math.max(0L, anchor + coalesceSeconds * 1000L - now);
        long delayMillis = Math.min(coalesceSeconds * 1000L, remainingBudget);
        long generation = generationSequence.incrementAndGet();
        generations.put(userId, generation);
        cancelFuture(userId);
        scheduleAttempt(userId, generation, 0, delayMillis, anchor);
        log.info("记忆提取已排队 user={} 原因={} 待处理={} 条", userId, reason, pending(userId).turns());
    }

    /**
     * 该不该排一次提取；返回原因（用于日志），不该排返回 null。
     * 没有 Redis 也没有库（单测/降级）时一律返回 {@code DEGRADED}——退化成"每次都排"，不影响提取本身。
     */
    private String decide(String userId) {
        if (redis == null || conversationMemory == null || runs == null) {
            return "DEGRADED";
        }
        Pending pending = pending(userId);
        if (pending.turns() >= rounds) {
            return "ROUNDS";
        }
        if (pending.turns() > 0 && pending.oldest() != null
                && pending.oldest().isBefore(LocalDateTime.now().minusHours(maxIdleHours))) {
            return "IDLE";
        }
        return null;
    }

    /**
     * 一条新的机主消息：计数 +1，并记住"最早那条是什么时候"。
     *
     * <p><b>键不在时不能直接 INCR</b>——那会把键从 1 重新建出来，"按库里的真实情况兜底"这条路就永远走不到
     * （一段对话会被当成"只有 1 条"）。所以键不在就先查库、按真实条数种回去；
     * 此时这条消息**已经落库**（编排器是先写 conversation_memory 再调这里），所以种回去的数就是对的，不用再加 1。
     */
    private void bumpPending(String userId) {
        if (redis == null || userId == null || userId.isBlank()) {
            return;
        }
        try {
            String key = KEY_PENDING + userId;
            if (Boolean.TRUE.equals(redis.hasKey(key))) {
                redis.opsForValue().increment(key);
                return;
            }
            Pending fromDb = pendingFromDb(userId);
            if (fromDb.turns() <= 0) {
                return;
            }
            redis.opsForValue().set(key, Long.toString(fromDb.turns()), KEY_TTL);
            if (fromDb.oldest() != null) {
                redis.opsForValue().setIfAbsent(KEY_PENDING_SINCE + userId, fromDb.oldest().toString(), KEY_TTL);
            }
            log.info("轮次计数不在 Redis（首次或已被清空），按库里真实条数种回 user={} 待处理={} 条",
                    userId, fromDb.turns());
        } catch (Exception e) {
            // Redis 挂了不影响提取：下面 pending() 还会再回退查库
            log.warn("轮次计数写入 Redis 失败（本次回退查库）user={}: {}", userId, e.getMessage());
        }
    }

    /** 提取真跑完了：窗口被消费掉，计数归零 */
    private void clearPending(String userId) {
        if (redis == null || userId == null || userId.isBlank()) {
            return;
        }
        try {
            redis.delete(List.of(KEY_PENDING + userId, KEY_PENDING_SINCE + userId));
        } catch (Exception e) {
            log.warn("清空轮次计数失败（下次会回退查库，不影响正确性）user={}: {}", userId, e.getMessage());
        }
    }

    /**
     * 待提取窗口：**优先 Redis 计数**（快、不压库），键不在才回退查库并把键种回去。
     *
     * <p>为什么两边都要：Redis 快但它可能被清空/过期，那时如果只认 Redis 就会"计数归零 → 一直不提取"；
     * 库里的 {@code conversation_memory} 和提取记录才是事实，拿来兜底既慢不了多少（一个用户两条小查询），又不会漏。
     */
    private Pending pending(String userId) {
        Pending cached = pendingFromRedis(userId);
        if (cached != null) {
            return cached;
        }
        Pending fromDb = pendingFromDb(userId);
        seedRedis(userId, fromDb);
        return fromDb;
    }

    /** Redis 里有计数就用它；没有（键不存在）返回 null 表示"回退查库" */
    private Pending pendingFromRedis(String userId) {
        if (redis == null || userId == null || userId.isBlank()) {
            return null;
        }
        try {
            String value = redis.opsForValue().get(KEY_PENDING + userId);
            if (value == null) {
                return null;
            }
            long turns = Math.max(0L, Long.parseLong(value.trim()));
            LocalDateTime oldest = null;
            String since = redis.opsForValue().get(KEY_PENDING_SINCE + userId);
            if (since != null) {
                try {
                    oldest = LocalDateTime.parse(since.trim());
                } catch (Exception ignored) {
                    oldest = null;
                }
            }
            return new Pending(turns, oldest);
        } catch (Exception e) {
            log.warn("读 Redis 轮次计数失败（本次回退查库）user={}: {}", userId, e.getMessage());
            return null;
        }
    }

    /** 回退路径：把 Redis 计数按库里的真实情况种回去 */
    private void seedRedis(String userId, Pending pending) {
        if (redis == null || pending == null || pending.turns() <= 0) {
            return;
        }
        try {
            redis.opsForValue().set(KEY_PENDING + userId, Long.toString(pending.turns()), KEY_TTL);
            if (pending.oldest() != null) {
                redis.opsForValue().setIfAbsent(KEY_PENDING_SINCE + userId, pending.oldest().toString(), KEY_TTL);
            }
        } catch (Exception e) {
            log.warn("回填 Redis 轮次计数失败（不影响本次判定）user={}: {}", userId, e.getMessage());
        }
    }

    /** 待提取窗口（查库）：边界 = 最近一次**真正生效**的提取的**开始时刻**（不是结束时刻，见下） */
    private Pending pendingFromDb(String userId) {
        if (conversationMemory == null || runs == null) {
            return Pending.NONE;
        }
        try {
            LocalDateTime after = EPOCH;
            for (MemoryExtractionRun run : runs.findTop5ByUserIdOrderByCreatedAtDesc(userId)) {
                if (run.getCreatedAt() == null) {
                    continue;
                }
                // FAILED = 这次调用/解析失败，那段对话还没被处理；
                // STALE = 跑到一半窗口已经变了、结果被丢掉——两者都**不能**算作"处理过了"，
                // 否则一段对话会被这两个记录悄悄放过去，再也提取不到。
                if ("FAILED".equals(run.getSkipReason()) || "STALE".equals(run.getSkipReason())) {
                    continue;
                }
                // 用**开始**时刻而不是结束时刻：提取要跑十几秒，这期间用户说的话既不在这一趟的窗口里，
                // 时间上又会早于结束时刻——用结束时刻当边界，那几句就永远轮不到提取了。
                long startedAt = run.getDurationMs() == null ? 0L : run.getDurationMs();
                after = run.getCreatedAt().minusNanos(startedAt * 1_000_000L);
                break;
            }
            long turns = conversationMemory.countUserTurnsAfter(userId, after);
            return new Pending(turns, turns > 0 ? conversationMemory.oldestUserTurnAfter(userId, after) : null);
        } catch (Exception e) {
            // 数不出来就当作"到点了"：宁可多跑一次，也不要因为一次查询失败就一直不提取
            log.warn("统计待提取轮次失败（本次按到点处理）user={}: {}", userId, e.getMessage());
            return new Pending(rounds, null);
        }
    }

    public void cancelPending(String userId) {
        generations.remove(userId);
        burstTexts.remove(userId);
        cancelFuture(userId);
        // 用户关了记忆 / 要求遗忘：计数也一起清掉，免得回头一开就"立刻触发一次"
        clearPending(userId);
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
        boolean completed;
        runningUsers.add(userId);
        try {
            completed = extractor.extract(userId, burst,
                    () -> isCurrentGeneration(userId, pending.generation) && userService.isMemoryEnabled(userId));
        } finally {
            runningUsers.remove(userId);
        }
        lastRunMillis.put(userId, System.currentTimeMillis());
        if (completed) {
            generations.remove(userId, pending.generation);
            // 这一趟真的跑完了：窗口被消费掉，Redis 计数归零（失败/过期都不清，见各自的 return）
            clearPending(userId);
        } else if (!isCurrentGeneration(userId, pending.generation) || !userService.isMemoryEnabled(userId)) {
            return;
        } else if (pending.attempt >= retryAttempts) {
            generations.remove(userId, pending.generation);
            log.warn("记忆提取多次失败，等待下一轮对话重新触发 user={} attempts={}", userId, pending.attempt + 1);
            return;
        } else {
            int nextAttempt = pending.attempt + 1;
            log.warn("记忆提取失败，将在 {} 秒后重试 user={} attempt={}/{}", retryDelaySeconds, userId,
                    nextAttempt, retryAttempts);
            scheduleAttempt(userId, pending.generation, nextAttempt, retryDelaySeconds * 1000L,
                    System.currentTimeMillis());
            return;
        }
        // 刚跑完这一趟的窗口里可能又满了（提取要跑十几秒，用户还在说话）→ 立刻再看一眼
        if (userService.isMemoryEnabled(userId) && decide(userId) != null) {
            schedule(userId);
        }
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
