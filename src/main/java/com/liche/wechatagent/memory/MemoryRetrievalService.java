package com.liche.wechatagent.memory;

import com.liche.wechatagent.config.EmbeddingClient;
import com.liche.wechatagent.config.MemoryPolicyProperties;
import com.liche.wechatagent.media.StoredMedia;
import com.liche.wechatagent.media.StoredMediaRepository;
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
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.Locale;

@Component
public class MemoryRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(MemoryRetrievalService.class);

    private static final Pattern HAN_OR_WORD = Pattern.compile("[\\p{IsHan}]{2,}|[a-zA-Z0-9_]{2,}");
    private final UserCoreMemoryRepository coreRepository;
    private final UserWorkMemoryRepository workRepository;
    private final ConversationMemoryService conversationMemoryService;
    private final EpisodicMemoryService episodicMemoryService;
    private final StoredMediaRepository storedMediaRepository;
    private final EmbeddingClient embeddingClient;
    private final WorkMemoryVectorStore workVectorStore;
    private final double workVectorFloor;
    private final int historicalLimit;
    private final int minimumHistoricalScore;
    private final List<String> historyMarkers;
    private final int linkedMediaMaxPerMemory;
    private final int linkedMediaSummaryMaxChars;
    private final int historicalItemMaxChars;

    @Autowired
    public MemoryRetrievalService(UserCoreMemoryRepository coreRepository,
                                  UserWorkMemoryRepository workRepository,
                                  ConversationMemoryService conversationMemoryService,
                                  EpisodicMemoryService episodicMemoryService,
                                  StoredMediaRepository storedMediaRepository,
                                  MemoryPolicyProperties policyProperties,
                                  EmbeddingClient embeddingClient,
                                  WorkMemoryVectorStore workVectorStore,
                                  @Value("${memory.historical-retrieval-limit:8}") int historicalLimit,
                                  @Value("${memory.historical-min-score:3}") int minimumHistoricalScore,
                                  @Value("${memory.work-vector-floor:0.5}") double workVectorFloor) {
        this.coreRepository = coreRepository;
        this.workRepository = workRepository;
        this.conversationMemoryService = conversationMemoryService;
        this.episodicMemoryService = episodicMemoryService;
        this.storedMediaRepository = storedMediaRepository;
        this.embeddingClient = embeddingClient;
        this.workVectorStore = workVectorStore;
        this.workVectorFloor = workVectorFloor <= 0 ? 0d : Math.min(1d, workVectorFloor);
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

    MemoryRetrievalService(UserCoreMemoryRepository coreRepository,
                           UserWorkMemoryRepository workRepository,
                           ConversationMemoryService conversationMemoryService,
                           StoredMediaRepository storedMediaRepository) {
        this(coreRepository, workRepository, conversationMemoryService,
                null, storedMediaRepository, new MemoryPolicyProperties(), null, null, 8, 3, 0.5d);
    }

    MemoryRetrievalService(UserCoreMemoryRepository coreRepository,
                           UserWorkMemoryRepository workRepository,
                           ConversationMemoryService conversationMemoryService,
                           EpisodicMemoryService episodicMemoryService,
                           StoredMediaRepository storedMediaRepository) {
        this(coreRepository, workRepository, conversationMemoryService,
                episodicMemoryService, storedMediaRepository, new MemoryPolicyProperties(), null, null, 8, 3, 0.5d);
    }

    @Transactional
    public RetrievedMemory retrieve(String userId, String query, int coreMaxLoad, int coreMaxChars,
                                    int workMaxLoad, int workMaxChars) {
        return retrieve(userId, query, coreMaxLoad, coreMaxChars, workMaxLoad, workMaxChars, 15);
    }

    /**
     * 事务边界放在最外层入口：内部的使用时间刷新走 @Modifying 定向更新，需要事务。
     */
    @Transactional
    public RetrievedMemory retrieve(String userId, String query, int coreMaxLoad, int coreMaxChars,
                                    int workMaxLoad, int workMaxChars, int usageTouchIntervalMinutes) {
        if (!validUserId(userId)) {
            return new RetrievedMemory("（暂无）", "（暂无）");
        }
        LocalDateTime now = LocalDateTime.now();
        String normalizedQuery = normalize(query);
        boolean historicalQuery = containsHistoryMarker(normalizedQuery);
        Map<Long, StoredMedia> linkedMedia = linkedMedia(userId);

        List<UserCoreMemory> cores = activeCoreCandidates(userId, normalizedQuery);
        List<UserCoreMemory> selectedCores = select(cores, UserCoreMemory::getContent,
                memory -> memory.getContent() + mediaSuffix(memory.getSourceMediaIds(), linkedMedia),
                Math.max(1, coreMaxLoad), Math.max(1, coreMaxChars));

        List<EpisodicMemory> episodeCandidates = activeEpisodeCandidates(userId, normalizedQuery);
        int totalWorkBudget = Math.max(1, workMaxChars);
        int episodeBudget = episodeCandidates.isEmpty() ? 0
                : Math.min(totalWorkBudget, Math.max(80, Math.min(600, totalWorkBudget / 2)));
        int activeWorkBudget = Math.max(1, totalWorkBudget - episodeBudget);
        List<UserWorkMemory> activeWork = activeWorkCandidates(userId, normalizedQuery, now);
        // P2（2026-09-18）：注入资格**只看当前这句的向量相似度**——过不了门槛就不注入，
        // 不再"排完序一律填到上限"（实测那会带来 600~1300 字纯陪跑）。**没有字面关键词兜底这条路**：
        // 算不出向量的行就是不注入，所以写路径 + 启动补齐要保证活跃行都有向量。
        // 为什么不用"最近几轮拼成窗口"：实测过（3 轮）会把语义摊平——注入回涨到 13~15 条，连
        // "今天心情不错"都能捞出一堆课表，故窗口整块不做（详见 docs/memory-vector-plan.md §18）。
        List<UserWorkMemory> relevantWork = rankWorkByRelevance(userId, normalizedQuery, activeWork);
        List<UserWorkMemory> selectedWork = select(relevantWork, UserWorkMemory::getContent,
                memory -> memory.getContent() + mediaSuffix(memory.getSourceMediaIds(), linkedMedia),
                Math.max(1, workMaxLoad), activeWorkBudget);
        touchUsage(now, selectedCores, selectedWork, usageTouchIntervalMinutes);

        List<EpisodicMemory> selectedEpisodes = select(episodeCandidates,
                EpisodicMemory::getSummary, this::formatEpisode,
                historicalLimit, episodeBudget);
        if (episodicMemoryService != null && !selectedEpisodes.isEmpty()) {
            episodicMemoryService.touch(selectedEpisodes, now, usageTouchIntervalMinutes);
        }

        boolean hasRelevantActiveMemory = !relevantWork.isEmpty()
                || cores.stream().anyMatch(memory -> score(memory.getContent(), memory.getKeywords(), normalizedQuery)
                        >= minimumHistoricalScore)
                || !episodeCandidates.isEmpty();
        List<HistoricalItem> historical = historicalItems(userId, normalizedQuery, historicalQuery,
                hasRelevantActiveMemory, now);
        String coreSection = format(selectedCores, memory -> "- " + memory.getContent()
                + mediaSuffix(memory.getSourceMediaIds(), linkedMedia));
        String workSection = format(selectedWork, memory -> "- " + memory.getContent()
                + mediaSuffix(memory.getSourceMediaIds(), linkedMedia));
        if (!selectedEpisodes.isEmpty()) {
            String episodes = "【相关经历】\n" + format(selectedEpisodes, this::formatEpisode);
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

    private List<EpisodicMemory> activeEpisodeCandidates(String userId, String normalizedQuery) {
        if (episodicMemoryService == null || normalizedQuery == null || normalizedQuery.isBlank()) {
            return List.of();
        }
        return episodicMemoryService.listActive(userId).stream()
                .filter(memory -> memory != null && ownedBy(memory.getUserId(), userId))
                .filter(memory -> score(memory.getTitle() + " " + memory.getSummary(), memory.getKeywords(),
                        normalizedQuery) >= minimumHistoricalScore)
                .sorted(Comparator
                        .comparingInt((EpisodicMemory memory) -> score(memory.getTitle() + " " + memory.getSummary(),
                                memory.getKeywords(), normalizedQuery)).reversed()
                        .thenComparing(EpisodicMemory::getImportance,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(EpisodicMemory::getOccurredAt,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private String formatEpisode(EpisodicMemory memory) {
        String date = memory.getOccurredAt() == null ? "时间未知" : memory.getOccurredAt().toLocalDate().toString();
        return "- [" + date + "] " + memory.getTitle() + "：" + memory.getSummary();
    }

    // Selects active, explicit core memories ordered by query relevance and recency.
    private List<UserCoreMemory> activeCoreCandidates(String userId, String normalizedQuery) {
        return safeCore(userId).stream()
                .filter(memory -> memory != null && ownedBy(memory.getUserId(), userId))
                .filter(CoreMemoryService::isActive)
                .filter(CoreMemoryService::isExplicit)
                .sorted(Comparator
                        .comparingInt((UserCoreMemory memory) -> score(memory.getContent(), memory.getKeywords(), normalizedQuery))
                        .reversed()
                        .thenComparing(Comparator.comparing(UserCoreMemory::getImportance,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                        .thenComparing(UserCoreMemory::getLastConfirmedAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(UserCoreMemory::getUpdatedAt,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    // Selects active work memories ordered by query relevance, importance, and priority.
    private List<UserWorkMemory> activeWorkCandidates(String userId, String normalizedQuery, LocalDateTime now) {
        return safeWork(userId).stream()
                .filter(memory -> memory != null && ownedBy(memory.getUserId(), userId))
                .filter(memory -> WorkMemoryService.isActive(memory, now))
                .sorted(Comparator
                        .comparingInt((UserWorkMemory memory) -> score(memory.getContent(), memory.getKeywords(), normalizedQuery))
                        .reversed()
                        .thenComparing(Comparator.comparing(UserWorkMemory::getImportance,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                        .thenComparing(Comparator.comparing(UserWorkMemory::getPriority,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                        .thenComparing(UserWorkMemory::getUpdatedAt,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    /**
     * 用向量给活跃工作记忆打分并筛出够像的那些（2026-09-18，P2）。
     *
     * <p>一次 {@code embedOne(query)} + 一条 SQL 拿回全部候选的余弦相似度；低于
     * {@code memory.work-vector-floor} 的一律丢弃——**不再用字面关键词把名额填满**。
     * 字面得分只留作同分时的次序（"都要做，结果一样就好"）。
     */
    private List<UserWorkMemory> rankWorkByRelevance(String userId, String normalizedQuery,
                                                     List<UserWorkMemory> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (embeddingClient == null || workVectorStore == null || !embeddingClient.isEnabled()
                || normalizedQuery == null || normalizedQuery.isBlank()) {
            log.warn("没有可用的向量（embedding.api-key 未配？），本轮不注入工作记忆 user={}", userId);
            return List.of();
        }
        float[] query = embeddingClient.embedOne(normalizedQuery);
        if (query == null) {
            log.warn("问题向量算不出来，本轮不注入工作记忆 user={}", userId);
            return List.of();
        }
        Map<Long, Double> similarities = workVectorStore.scores(userId, query);
        List<UserWorkMemory> ranked = new ArrayList<>();
        for (UserWorkMemory memory : candidates) {
            if (memory == null || memory.getId() == null) {
                continue;
            }
            Double similarity = similarities.get(memory.getId());
            if (similarity == null || similarity < workVectorFloor) {
                continue;
            }
            ranked.add(memory);
        }
        ranked.sort(Comparator
                .comparingDouble((UserWorkMemory memory) -> similarities.getOrDefault(memory.getId(), 0d))
                .reversed()
                .thenComparing(Comparator.comparingInt((UserWorkMemory memory) ->
                        score(memory.getContent(), memory.getKeywords(), normalizedQuery)).reversed())
                .thenComparing(Comparator.comparing(UserWorkMemory::getImportance,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .thenComparing(Comparator.comparing(UserWorkMemory::getPriority,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .thenComparing(UserWorkMemory::getUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())));
        return ranked;
    }

    public record RetrievedMemory(String coreSection, String workSection) {
    }

    private List<HistoricalItem> historicalItems(String userId, String query, boolean historicalQuery,
                                                  boolean hasRelevantActiveMemory, LocalDateTime now) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<HistoricalItem> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (UserWorkMemory memory : safeWork(userId)) {
            if (memory == null || !ownedBy(memory.getUserId(), userId)) {
                continue;
            }
            if (WorkMemoryService.isActive(memory, now)) {
                continue;
            }
            String status = memory.getStatus();
            addHistorical(candidates, seen, memory.getContent(), status,
                    score(memory.getContent(), memory.getKeywords(), query), historicalQuery, memory.getUpdatedAt());
        }
        if (conversationMemoryService != null && (historicalQuery || !hasRelevantActiveMemory)) {
            List<ConversationMemory> records = conversationMemoryService.relevantForRetrieval(userId, query);
            if (records == null || records.isEmpty()) {
                records = conversationMemoryService.recentForRetrieval(userId);
            }
            if (records == null) {
                records = List.of();
            }
            for (ConversationMemory record : records) {
                if (record == null || !ownedBy(record.getUserId(), userId)) {
                    continue;
                }
                int conversationScore = score(record.getContent(), "", query);
                if (conversationScore >= minimumHistoricalScore) {
                    String label = switch (record.getRole() == null ? "" : record.getRole().toLowerCase(Locale.ROOT)) {
                        case "assistant" -> "助手曾回复";
                        case "system" -> "工具执行记录";
                        default -> "用户曾说";
                    };
                    addHistorical(candidates, seen, label + "：" + record.getContent(), "CONVERSATION",
                            conversationScore, historicalQuery, record.getCreatedAt());
                }
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
        return switch (status == null ? "" : status.toUpperCase()) {
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
        return values.stream().map(renderer).toList().stream().reduce((left, right) -> left + "\n" + right).orElse("（暂无）");
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

    private List<UserCoreMemory> safeCore(String userId) {
        if (!validUserId(userId)) {
            return List.of();
        }
        try {
            List<UserCoreMemory> result = coreRepository.findByUserIdOrderByCreatedAtAsc(userId);
            return result == null ? List.of() : result.stream().filter(memory -> memory != null
                    && ownedBy(memory.getUserId(), userId)).toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<UserWorkMemory> safeWork(String userId) {
        if (!validUserId(userId)) {
            return List.of();
        }
        try {
            List<UserWorkMemory> result = workRepository.findByUserIdOrderByUpdatedAtDesc(userId);
            return result == null ? List.of() : result.stream().filter(memory -> memory != null
                    && ownedBy(memory.getUserId(), userId)).toList();
        } catch (Exception ignored) {
            try {
                List<UserWorkMemory> result = workRepository.findByUserId(userId);
                return result == null ? List.of() : result.stream().filter(memory -> memory != null
                        && ownedBy(memory.getUserId(), userId)).toList();
            } catch (Exception ignoredAgain) {
                return List.of();
            }
        }
    }

    private Map<Long, StoredMedia> linkedMedia(String userId) {
        if (storedMediaRepository == null) {
            return Map.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        safeCore(userId).forEach(memory -> ids.addAll(mediaIds(memory.getSourceMediaIds())));
        safeWork(userId).forEach(memory -> ids.addAll(mediaIds(memory.getSourceMediaIds())));
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, StoredMedia> result = new HashMap<>();
        try {
            List<StoredMedia> media = storedMediaRepository.findByUserIdAndIdInAndStatus(userId, List.copyOf(ids), StoredMedia.ACTIVE);
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

    private void touchUsage(LocalDateTime now, List<UserCoreMemory> cores, List<UserWorkMemory> work,
                            int usageTouchIntervalMinutes) {
        LocalDateTime refreshBefore = now.minusMinutes(Math.max(1, usageTouchIntervalMinutes));
        List<Long> coreUpdates = cores.stream()
                .filter(memory -> memory.getLastUsedAt() == null || memory.getLastUsedAt().isBefore(refreshBefore))
                .map(UserCoreMemory::getId)
                .filter(id -> id != null)
                .toList();
        if (!coreUpdates.isEmpty()) {
            try {
                // 定向更新一列：整实体回写会用内存旧快照覆盖掉别处刚改过的状态列
                coreRepository.updateLastUsedAt(coreUpdates, now);
            } catch (Exception ignored) {
            }
        }
        List<Long> workUpdates = work.stream()
                .filter(memory -> memory.getLastUsedAt() == null || memory.getLastUsedAt().isBefore(refreshBefore))
                .map(UserWorkMemory::getId)
                .filter(id -> id != null)
                .toList();
        if (!workUpdates.isEmpty()) {
            try {
                workRepository.updateLastUsedAt(workUpdates, now);
            } catch (Exception ignored) {
            }
        }
    }
}
