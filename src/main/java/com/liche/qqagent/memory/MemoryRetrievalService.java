package com.liche.qqagent.memory;

import com.liche.qqagent.config.EmbeddingClient;
import com.liche.qqagent.config.MemoryPolicyProperties;
import com.liche.qqagent.media.StoredMedia;
import com.liche.qqagent.media.StoredMediaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * 每轮对话"该给模型看哪些记忆"的组装（2026-09-18 三表合并后：所有散文记忆都从 {@code memory} 表来）。
 *
 * <p>四路来源、四条预算：
 * <ol>
 *   <li><b>长期设定</b>（{@code kind=PROFILE} 且 {@code always_inject}）：无条件注入，按字面相关度 + 重要度 + 最近确认排序；</li>
 *   <li><b>中期事项</b>（{@code kind=TASK}）：按**当前这句的向量**挑，过不了门槛就不注入（见 docs/memory-vector-plan.md §18）；</li>
 *   <li><b>经历</b>（{@code kind=EXPERIENCE}）：同样按向量挑，和事项共用工作记忆那 1500 字预算（最多分 600 字）；</li>
 *   <li><b>历史兜底</b>：已经结束/过期/被替代的记忆 + 语义最相关的原始对话（§19），只在"没有活跃记忆能回答"或用户明确问"之前/上次"时才去捞。</li>
 * </ol>
 */
@Component
public class MemoryRetrievalService {

    /** RRF 的平滑常数（原论文推荐 60）：越大越弱化名次差异 */
    private static final int RRF_K = 60;

    private static final Logger log = LoggerFactory.getLogger(MemoryRetrievalService.class);

    private static final Pattern HAN_OR_WORD = Pattern.compile("[\\p{IsHan}]{2,}|[a-zA-Z0-9_]{2,}");
    private final MemoryService memoryService;
    private final ConversationMemoryService conversationMemoryService;
    private final StoredMediaRepository storedMediaRepository;
    private final EmbeddingClient embeddingClient;
    private final double workVectorFloor;
    private final double episodeVectorFloor;


    /** 字面检索（2026-11-04）：BM25 补"专有名词/编号"这一路，和向量取并集后交给精排 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MemoryBm25Index bm25Index;

    /** 说话意图判别（词表在配置里，不写死在代码） */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MemorySignal memorySignal;

    /** 指代型查询改写（2026-11-04）：把"上次那件事"补成有具体词的查询 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MemoryQueryRewriter queryRewriter;
    private final double conversationVectorFloor;
    private final int conversationRecallLimit;
    private final int historicalLimit;
    private final int minimumHistoricalScore;
    private final List<String> historyMarkers;
    private final int linkedMediaMaxPerMemory;
    private final int linkedMediaSummaryMaxChars;
    private final int historicalItemMaxChars;

    @Autowired
    public MemoryRetrievalService(MemoryService memoryService,
                                  ConversationMemoryService conversationMemoryService,
                                  StoredMediaRepository storedMediaRepository,
                                  MemoryPolicyProperties policyProperties,
                                  EmbeddingClient embeddingClient,
                                  @Value("${memory.historical-retrieval-limit:8}") int historicalLimit,
                                  @Value("${memory.historical-min-score:3}") int minimumHistoricalScore,
                                  @Value("${memory.work-vector-floor:0.45}") double workVectorFloor,
                                  @Value("${memory.episode-vector-floor:0.45}") double episodeVectorFloor,
                                  @Value("${memory.conversation-vector-floor:0.45}") double conversationVectorFloor,
                                  @Value("${memory.conversation-recall-limit:6}") int conversationRecallLimit) {
        this.memoryService = memoryService;
        this.conversationMemoryService = conversationMemoryService;
        this.storedMediaRepository = storedMediaRepository;
        this.embeddingClient = embeddingClient;
        this.workVectorFloor = workVectorFloor <= 0 ? 0d : Math.min(1d, workVectorFloor);
        this.episodeVectorFloor = episodeVectorFloor <= 0 ? 0d : Math.min(1d, episodeVectorFloor);
        this.conversationVectorFloor = conversationVectorFloor <= 0 ? 0d : Math.min(1d, conversationVectorFloor);
        this.conversationRecallLimit = Math.max(1, Math.min(32, conversationRecallLimit));
        this.historicalLimit = Math.max(1, historicalLimit);
        this.minimumHistoricalScore = Math.max(1, minimumHistoricalScore);
        MemoryPolicyProperties policies = policyProperties == null ? new MemoryPolicyProperties() : policyProperties;
        this.historyMarkers = policies.getHistoryMarkers();
        this.linkedMediaMaxPerMemory = bounded(policies.getLinkedMediaMaxPerMemory(), 1, 32,
                MemoryPolicyProperties.DEFAULT_LINKED_MEDIA_MAX_PER_MEMORY);
        this.linkedMediaSummaryMaxChars = bounded(policies.getLinkedMediaSummaryMaxChars(), 16, 2_000,
                MemoryPolicyProperties.DEFAULT_LINKED_MEDIA_SUMMARY_MAX_CHARS);
        this.historicalItemMaxChars = bounded(policies.getHistoricalItemMaxChars(), 64, 10_000,
                MemoryPolicyProperties.DEFAULT_HISTORICAL_ITEM_MAX_CHARS);
    }

    MemoryRetrievalService(MemoryService memoryService,
                           ConversationMemoryService conversationMemoryService,
                           StoredMediaRepository storedMediaRepository) {
        this(memoryService, conversationMemoryService, storedMediaRepository, new MemoryPolicyProperties(),
                null, 8, 3, 0.45d, 0.45d, 0.45d, 6);
    }

    MemoryRetrievalService(MemoryService memoryService,
                           ConversationMemoryService conversationMemoryService,
                           StoredMediaRepository storedMediaRepository,
                           EmbeddingClient embeddingClient,
                           double workVectorFloor,
                           double episodeVectorFloor,
                           double conversationVectorFloor,
                           int conversationRecallLimit) {
        this(memoryService, conversationMemoryService, storedMediaRepository, new MemoryPolicyProperties(),
                embeddingClient, 8, 3, workVectorFloor, episodeVectorFloor, conversationVectorFloor,
                conversationRecallLimit);
    }

    @Transactional
    public RetrievedMemory retrieve(String userId, String query, int coreMaxLoad, int coreMaxChars,
                                    int workMaxLoad, int workMaxChars) {
        return retrieve(userId, query, coreMaxLoad, coreMaxChars, workMaxLoad, workMaxChars, 15);
    }

    /** 事务边界放在最外层入口：内部的使用时间刷新走 @Modifying 定向更新，需要事务。 */
    @Transactional
    public RetrievedMemory retrieve(String userId, String query, int coreMaxLoad, int coreMaxChars,
                                    int workMaxLoad, int workMaxChars, int usageTouchIntervalMinutes) {
        if (!validUserId(userId)) {
            return new RetrievedMemory("（暂无）", "（暂无）");
        }
        LocalDateTime now = LocalDateTime.now();
        String normalizedQuery = normalize(query);

        // 2026-11-04：**寒暄类不翻旧账**。一句"哈哈"也要查库 + 算 embedding，
        // 实测白花 600~900ms 和一次模型调用。但**画像照给**——它得知道"你是谁"。
        if (isSmallTalk(normalizedQuery)) {
            List<Memory> quickProfiles = profileCandidates(userId, normalizedQuery);
            String quickCore = format(quickProfiles, memory -> "- " + memory.getContent());
            return new RetrievedMemory(quickCore.isBlank() ? "（暂无）" : quickCore, "（暂无）");
        }
        // 2026-11-04：指代型问题（"我上次说的你还清楚吗"）没有可检索的词，
        // 先用最近几轮对话把它补全成具体查询；正常问题原样返回，不花这次调用。
        if (queryRewriter != null) {
            try {
                normalizedQuery = normalize(queryRewriter.expand(userId, normalizedQuery));
            } catch (RuntimeException ignored) {
                // 改写失败就用原句，不阻塞
            }
        }
        boolean historicalQuery = containsHistoryMarker(normalizedQuery);
        // 2026-11-01：召回整体实测偶发 10 秒以上，分段计时定位（超过 800ms 才打）
        long t0 = System.currentTimeMillis();
        Map<Long, StoredMedia> linkedMedia = linkedMedia(userId);
        long tMedia = System.currentTimeMillis();
        // 一次算好"当前这句"的向量，中期事项 / 经历 / 历史对话三处共用（省调用，也保证口径一致）
        float[] queryVector = embedQuery(userId, normalizedQuery);
        long tEmbed = System.currentTimeMillis();
        long tProfile0 = tEmbed;

        // ① 长期设定：无条件注入，按字面相关度 + 重要度 + 最近确认排序（没有向量门槛）
        List<Memory> profiles = profileCandidates(userId, normalizedQuery);
        List<Memory> selectedProfiles = select(profiles, Memory::getContent,
                memory -> memory.getContent() + mediaSuffix(memory.getSourceMediaIds(), linkedMedia),
                Math.max(1, coreMaxLoad), Math.max(1, coreMaxChars));

        // ②③ 中期事项 + 经历：都按向量挑，共用 workMaxChars 预算
        long tProfile1 = System.currentTimeMillis();
        List<Memory> experiences = rankByVector(userId, queryVector, episodeVectorFloor, Memory.KIND_EXPERIENCE);
        experiences = mergeLexical(userId, normalizedQuery, experiences, Memory.KIND_EXPERIENCE, 4);
        long tVector1 = System.currentTimeMillis();
        int totalWorkBudget = Math.max(1, workMaxChars);
        int episodeBudget = experiences.isEmpty() ? 0
                : Math.min(totalWorkBudget, Math.max(80, Math.min(600, totalWorkBudget / 2)));
        int activeWorkBudget = Math.max(1, totalWorkBudget - episodeBudget);
        List<Memory> tasks = rankByVector(userId, queryVector, workVectorFloor, Memory.KIND_TASK);
        tasks = mergeLexical(userId, normalizedQuery, tasks, Memory.KIND_TASK, 4);
        List<Memory> selectedTasks = select(tasks, Memory::getContent,
                memory -> memory.getContent() + mediaSuffix(memory.getSourceMediaIds(), linkedMedia),
                Math.max(1, workMaxLoad), activeWorkBudget);
        long tTask1 = System.currentTimeMillis();
        touchUsage(now, selectedProfiles, selectedTasks, experiences, usageTouchIntervalMinutes);
        long tTouch1 = System.currentTimeMillis();

        List<Memory> selectedExperiences = select(experiences,
                Memory::getContent, this::formatEpisode, historicalLimit, episodeBudget);

        final String matchQuery = normalizedQuery;
        boolean hasRelevantActiveMemory = !tasks.isEmpty()
                || profiles.stream().anyMatch(memory -> score(memory.getContent(), memory.getKeywords(), matchQuery)
                        >= minimumHistoricalScore)
                || !experiences.isEmpty();
        long tRank = System.currentTimeMillis();
        List<HistoricalItem> historical = historicalItems(userId, normalizedQuery, historicalQuery,
                hasRelevantActiveMemory, now, queryVector);
        long tHistory = System.currentTimeMillis();
        long total = tHistory - t0;
        if (total > 800) {
            log.info("记忆召回偏慢 user={} total={}ms 媒体={}ms 向量={}ms 画像与排序={}ms 历史={}ms"
                            + " [细分 画像={}ms 经历向量={}ms 任务={}ms 写使用时间={}ms]",
                    userId, total, tMedia - t0, tEmbed - tMedia, tRank - tEmbed, tHistory - tRank,
                    tProfile1 - tProfile0, tVector1 - tProfile1, tTask1 - tVector1, tTouch1 - tTask1);
        }
        String coreSection = format(selectedProfiles, memory -> "- " + memory.getContent()
                + mediaSuffix(memory.getSourceMediaIds(), linkedMedia));
        String workSection = format(selectedTasks, memory -> "- " + memory.getContent()
                + mediaSuffix(memory.getSourceMediaIds(), linkedMedia));
        if (!selectedExperiences.isEmpty()) {
            String episodes = "【相关经历】\n" + format(selectedExperiences, this::formatEpisode);
            workSection = "（暂无）".equals(workSection) ? episodes : workSection + "\n\n" + episodes;
            workSection = truncate(workSection, Math.max(1, workMaxChars));
        }
        if (!historical.isEmpty()) {
            int usedChars = "（暂无）".equals(workSection) ? 0 : workSection.length();
            String historyText = formatHistorical(historical, Math.max(1, workMaxChars - usedChars));
            if (!historyText.isBlank()) {
                workSection = workSection.equals("（暂无）") ? historyText : workSection + "\n\n" + historyText;
            }
        }
        return new RetrievedMemory(coreSection, workSection);
    }

    /** 长期设定：活跃 + 显式来源，按字面相关度排序（和合并前 core 的行为一致） */
    /**
     * 用精排把候选裁到几条（2026-11-04）。
     *
     * <p>不启用 / 调用失败 / 候选太少时**原样返回**——精排只是减噪，不能让它变成单点故障。
     */
    /**
     * 向量候选 + BM25 候选合并（2026-11-04 定稿）。
     *
     * <p><b>为什么不是 RRF</b>：实测 RRF 后召回 27.6%（向量+rerank 是 53.6%）。
     * 原因是我们的两路候选高度重叠、且 RRF 分数是 1/61 量级，把原有的阈值/截断全废掉了，
     * 结果取满上限、语义被抹平——TASK 类掉到 15%。
     *
     * <p><b>为什么不是简单并集 + rerank</b>：rerank 额度已停用，而且并集后 rerank 重排
     * 会把向量已经挑中的挤掉（实测 53.6% → 52.8%）。
     *
     * <p><b>现在的做法</b>：**向量顺序原样保留**，BM25 独有的命中按分数插到后面。
     * 排序仍然是向量的语义相似度（这是目前最可靠的信号），BM25 只负责"补漏"。
     * 字数预算在 select() 里截，所以补进来的不会挤掉原来那几条。
     */
    private List<Memory> mergeLexical(String userId, String query, List<Memory> vectorHits,
                                      String kind, int lexicalLimit) {
        if (bm25Index == null) {
            return vectorHits;
        }
        // 只在**查询里带编号/英文/数字**时才补字面命中（2026-11-04 实测结论）：
        // 250 题里 68% 是"纯指代"（我上次说的那件事），字面通道对它们没有任何信号，
        // 补进来只会把注入字数从 1335 涨到 2772、召回还掉了 1 个点。
        // 而"6e206 是哪门课""521 那次"这类，字面通道一枪命中——所以按查询特征按需启用。
        if (query == null || !query.matches(".*[0-9A-Za-z].*")) {
            return vectorHits;
        }
        List<Memory> lexical;
        try {
            lexical = bm25Index.search(userId, query, Math.max(lexicalLimit * 3, 20));
        } catch (RuntimeException exception) {
            return vectorHits;
        }
        if (lexical == null || lexical.isEmpty()) {
            return vectorHits;
        }
        java.util.Set<Long> seen = new java.util.HashSet<>();
        List<Memory> merged = new java.util.ArrayList<>();
        if (vectorHits != null) {
            for (Memory memory : vectorHits) {
                if (memory != null && memory.getId() != null) {
                    seen.add(memory.getId());
                }
                merged.add(memory);
            }
        }
        int added = 0;
        for (Memory memory : lexical) {
            if (added >= lexicalLimit) {
                break;
            }
            if (memory == null || memory.getId() == null || !kind.equals(memory.getKind())
                    || seen.contains(memory.getId())) {
                continue;
            }
            seen.add(memory.getId());
            merged.add(memory);
            added++;
        }
        return merged;
    }

    private List<Memory> profileCandidates(String userId, String normalizedQuery) {
        List<Memory> profiles;
        try {
            profiles = memoryService == null ? List.of() : memoryService.listAlwaysInject(userId);
        } catch (Exception e) {
            log.warn("读取长期设定失败 user={} reason={}", userId, e.getClass().getSimpleName());
            return List.of();
        }
        return profiles.stream()
                .filter(memory -> memory != null && ownedBy(memory.getUserId(), userId))
                .sorted(Comparator
                        .comparingInt((Memory memory) -> score(memory.getContent(), memory.getKeywords(), normalizedQuery))
                        .reversed()
                        .thenComparing(Comparator.comparing(Memory::getImportance,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                        .thenComparing(Memory::getLastConfirmedAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Memory::getUpdatedAt,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /** 按向量挑 TASK / EXPERIENCE：过不了门槛就不返回（没有字面兜底） */
    private List<Memory> rankByVector(String userId, float[] queryVector, double floor, String kind) {
        if (memoryService == null || queryVector == null) {
            return List.of();
        }
        try {
            return memoryService.rankByVector(userId, queryVector, floor, kind).stream()
                    .filter(memory -> memory != null && ownedBy(memory.getUserId(), userId))
                    .toList();
        } catch (Exception e) {
            log.warn("按向量挑记忆失败 user={} kind={} reason={}", userId, kind, e.getClass().getSimpleName());
            return List.of();
        }
    }

    private String formatEpisode(Memory memory) {
        String date = memory.getOccurredAt() == null ? "时间未知" : memory.getOccurredAt().toLocalDate().toString();
        String title = memory.getTitle() == null ? "" : memory.getTitle();
        return "- [" + date + "] " + title + "：" + memory.getContent();
    }

    public record RetrievedMemory(String coreSection, String workSection) {
    }

    /**
     * 算"当前这句"的向量（三处共用）。
     *
     * <p>拿不到就返回 null——调用方按"这次没有可用的向量"处理（**不做字面兜底**，用户 2026-09-18 明确要求）。
     */
    private float[] embedQuery(String userId, String normalizedQuery) {
        if (embeddingClient == null || memoryService == null || !embeddingClient.isEnabled()
                || normalizedQuery == null || normalizedQuery.isBlank()) {
            log.warn("没有可用的向量（embedding.api-key 未配？），本轮不注入向量相关的记忆 user={}", userId);
            return null;
        }
        float[] query = embeddingClient.embedOne(normalizedQuery);
        if (query == null) {
            log.warn("问题向量算不出来，本轮不注入向量相关的记忆 user={}", userId);
        }
        return query;
    }

    private List<HistoricalItem> historicalItems(String userId, String query, boolean historicalQuery,
                                                  boolean hasRelevantActiveMemory, LocalDateTime now,
                                                  float[] queryVector) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<HistoricalItem> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Memory memory : safeMemories(userId)) {
            if (memory == null || !ownedBy(memory.getUserId(), userId)) {
                continue;
            }
            if (MemoryService.isActive(memory, now)) {
                continue;
            }
            addHistorical(candidates, seen, memory.getContent(), memory.getStatus(),
                    score(memory.getContent(), memory.getKeywords(), query), historicalQuery, memory.getUpdatedAt());
        }
        // 历史兜底对话证据：按向量取 top-K、过门槛才进（2026-09-18 P3 把字面 LIKE 那条路整块删了）
        if (conversationMemoryService != null && queryVector != null
                && (historicalQuery || !hasRelevantActiveMemory)) {
            List<ConversationMemoryService.ConversationHit> records = conversationMemoryService.searchByVector(
                    userId, queryVector, conversationRecallLimit, conversationVectorFloor);
            for (ConversationMemoryService.ConversationHit hit : records) {
                ConversationMemory record = hit.record();
                if (record == null || !ownedBy(record.getUserId(), userId)) {
                    continue;
                }
                String label = switch (record.getRole() == null ? "" : record.getRole().toLowerCase(Locale.ROOT)) {
                    case "assistant" -> "助手曾回复";
                    case "system" -> "工具执行记录";
                    default -> "用户曾说";
                };
                // 分数只是"排序用"：对话证据是余弦（0~1 → 0~100），记忆历史是字面分，两者不同尺度
                addHistorical(candidates, seen, label + "：" + record.getContent(), "CONVERSATION",
                        (int) Math.round(hit.score() * 100), historicalQuery, record.getCreatedAt());
            }
        }
        return candidates.stream()
                .sorted(Comparator.comparingInt(HistoricalItem::score).reversed()
                        .thenComparing(HistoricalItem::occurredAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(HistoricalItem::content))
                .limit(historicalLimit)
                .toList();
    }

    private void addHistorical(List<HistoricalItem> target, Set<String> seen, String content,
                               String status, int score, boolean historicalQuery,
                               LocalDateTime occurredAt) {
        if (content == null || content.isBlank()) {
            return;
        }
        if (!historicalQuery && score < minimumHistoricalScore) {
            return;
        }
        String key = normalize(content);
        if (key.isBlank() || !seen.add(key)) {
            return;
        }
        target.add(new HistoricalItem(content.trim(), status == null ? "历史" : status, score, occurredAt));
    }

    private String formatHistorical(List<HistoricalItem> items, int maxChars) {
        StringBuilder text = new StringBuilder("【历史记忆（已完成/已归档，仅供追溯）】");
        for (HistoricalItem item : items) {
            String prefix = "\n- [" + displayStatus(item.status()) + "] ";
            int remaining = maxChars - text.length() - prefix.length();
            if (remaining <= 0) {
                break;
            }
            String content = truncate(item.content(), Math.min(historicalItemMaxChars, remaining));
            if (content.isBlank()) {
                continue;
            }
            text.append(prefix).append(content);
        }
        return text.length() == "【历史记忆（已完成/已归档，仅供追溯）】".length() ? "" : text.toString();
    }

    private String displayStatus(String status) {
        return switch (status == null ? "" : status.toUpperCase(Locale.ROOT)) {
            case "COMPLETED" -> "已完成";
            case "EXPIRED" -> "已过期";
            case "SUPERSEDED" -> "已被替代";
            case "ARCHIVED" -> "已归档";
            default -> "历史";
        };
    }

    private <T> List<T> select(List<T> candidates, Function<T, String> plainContent,
                               Function<T, String> budgetContent, int maxItems, int maxChars) {
        List<T> selected = new ArrayList<>();
        int total = 0;
        for (T candidate : candidates) {
            if (selected.size() >= maxItems) {
                break;
            }
            String plain = plainContent.apply(candidate);
            String rendered = budgetContent.apply(candidate);
            if (plain == null || plain.isBlank() || rendered == null || rendered.isBlank()) {
                continue;
            }
            int remaining = maxChars - total;
            if (remaining <= 0) {
                break;
            }
            if (rendered.length() > remaining) {
                continue;
            }
            selected.add(candidate);
            total += rendered.length();
        }
        return selected;
    }

    private <T> String format(List<T> values, Function<T, String> renderer) {
        if (values == null || values.isEmpty()) {
            return "（暂无）";
        }
        return values.stream().map(renderer).toList().stream()
                .reduce((left, right) -> left + "\n" + right).orElse("（暂无）");
    }

    private int score(String content, String keywords, String normalizedQuery) {
        if (content == null || content.isBlank() || normalizedQuery == null || normalizedQuery.isBlank()) {
            return 0;
        }
        String normalizedContent = normalize(content);
        String normalizedKeywords = normalize(keywords);
        if (normalizedContent.contains(normalizedQuery) || normalizedQuery.contains(normalizedContent)) {
            return 100;
        }
        int result = 0;
        for (String term : terms(normalizedQuery)) {
            if (normalizedContent.contains(term)) {
                result += Math.min(18, 6 + term.length());
            }
            if (!normalizedKeywords.isBlank() && normalizedKeywords.contains(term)) {
                result += 12;
            }
        }
        Set<String> queryBigrams = ngrams(normalizedQuery, 2);
        Set<String> contentBigrams = ngrams(normalizedContent, 2);
        Set<String> overlap = new HashSet<>(queryBigrams);
        overlap.retainAll(contentBigrams);
        result += Math.min(36, overlap.size() * 3);
        Set<String> queryTrigrams = ngrams(normalizedQuery, 3);
        Set<String> contentTrigrams = ngrams(normalizedContent, 3);
        overlap = new HashSet<>(queryTrigrams);
        overlap.retainAll(contentTrigrams);
        result += Math.min(30, overlap.size() * 4);
        return result;
    }

    private List<String> terms(String value) {
        List<String> result = new ArrayList<>();
        var matcher = HAN_OR_WORD.matcher(value == null ? "" : value);
        while (matcher.find()) {
            result.add(matcher.group());
        }
        return result;
    }

    private Set<String> ngrams(String value, int size) {
        Set<String> result = new LinkedHashSet<>();
        if (value == null) {
            return result;
        }
        for (int index = 0; index + size <= value.length(); index++) {
            result.add(value.substring(index, index + size));
        }
        return result;
    }

    private boolean containsHistoryMarker(String query) {
        return historyMarkers.stream().anyMatch(query::contains);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{P}\\p{Z}\\s]+", "")
                .trim();
    }

    private List<Memory> safeMemories(String userId) {
        if (!validUserId(userId) || memoryService == null) {
            return List.of();
        }
        try {
            List<Memory> result = memoryService.list(userId);
            return result == null ? List.of() : result.stream()
                    .filter(memory -> memory != null && ownedBy(memory.getUserId(), userId))
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Map<Long, StoredMedia> linkedMedia(String userId) {
        if (storedMediaRepository == null) {
            return Map.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        safeMemories(userId).forEach(memory -> ids.addAll(mediaIds(memory.getSourceMediaIds())));
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, StoredMedia> result = new HashMap<>();
        try {
            List<StoredMedia> media = storedMediaRepository.findByUserIdAndIdInAndStatus(userId, List.copyOf(ids),
                    StoredMedia.ACTIVE);
            if (media != null) {
                media.stream().filter(item -> item != null && ownedBy(item.getUserId(), userId))
                        .forEach(item -> result.put(item.getId(), item));
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private String mediaSuffix(String sourceMediaIds, Map<Long, StoredMedia> mediaById) {
        List<String> labels = mediaIds(sourceMediaIds).stream()
                .map(mediaById::get)
                .filter(media -> media != null)
                .limit(linkedMediaMaxPerMemory)
                .map(this::mediaLabel)
                .toList();
        return labels.isEmpty() ? "" : "（关联资料：" + String.join("；", labels) + "）";
    }

    private String mediaLabel(StoredMedia media) {
        String summary = media.getSummary() == null ? "" : media.getSummary().trim();
        if (summary.length() > linkedMediaSummaryMaxChars) {
            summary = summary.substring(0, linkedMediaSummaryMaxChars) + "…";
        }
        return "#" + media.getId() + " " + media.getFileName()
                + (summary.isBlank() ? "" : "：" + summary);
    }

    private List<Long> mediaIds(String sourceMediaIds) {
        return MemoryProvenance.fromStored("USER_EXPLICIT", 100, "", sourceMediaIds).sourceMediaIds();
    }

    private record HistoricalItem(String content, String status, int score, LocalDateTime occurredAt) {
    }

    private boolean ownedBy(String owner, String userId) {
        return owner != null && userId != null && owner.equals(userId);
    }

    /**
     * 是不是"寒暄/附和"（2026-11-04）。
     *
     * <p>判据刻意保守：**短 + 寒暄词 + 没有疑问 + 没有数字/英文**。
     * 只要有一点像是在问事（"好吗""那个呢""6e206"），就走完整检索——
     * 宁可多花 700ms，也不能让它对着重要上下文装失忆。
     */
    private boolean isSmallTalk(String query) {
        return memorySignal != null && memorySignal.isSmallTalk(query);
    }

    private boolean validUserId(String userId) {
        return userId != null && !userId.isBlank();
    }

    private String truncate(String value, int maxChars) {
        if (value == null || maxChars <= 0) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return maxChars == 1 ? "…" : value.substring(0, maxChars - 1) + "…";
    }

    private int bounded(int value, int minimum, int maximum, int fallback) {
        return value < minimum || value > maximum ? fallback : value;
    }

    /** 只刷"最近被用过"这一列（面板反查"这轮注入了什么"就靠它）；定向 UPDATE，避免整行回写 */
    private void touchUsage(LocalDateTime now, List<Memory> profiles, List<Memory> tasks, List<Memory> experiences,
                            int usageTouchIntervalMinutes) {
        if (memoryService == null) {
            return;
        }
        List<Memory> touched = new ArrayList<>();
        if (profiles != null) {
            touched.addAll(profiles);
        }
        if (tasks != null) {
            touched.addAll(tasks);
        }
        if (experiences != null) {
            touched.addAll(experiences);
        }
        try {
            memoryService.touch(touched, now, usageTouchIntervalMinutes);
        } catch (Exception e) {
            log.warn("刷新记忆使用时间失败: {}", e.getMessage());
        }
    }
}
