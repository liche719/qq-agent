package com.liche.wechatagent.memory;

import com.liche.wechatagent.config.EmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class EpisodicMemoryService {

    private static final Logger log = LoggerFactory.getLogger(EpisodicMemoryService.class);
    private static final int MAX_ACTIVE_SCAN = 500;
    private static final String TABLE = "episodic_memory";

    private final EpisodicMemoryRepository repository;
    private final MemoryContentSimilarity similarity;
    private final EmbeddingClient embeddingClient;
    private final PgVectorStore vectorStore;

    @Autowired
    public EpisodicMemoryService(EpisodicMemoryRepository repository, MemoryContentSimilarity similarity,
                                 EmbeddingClient embeddingClient, PgVectorStore vectorStore) {
        this.repository = repository;
        this.similarity = similarity;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
    }

    public EpisodicMemoryService(EpisodicMemoryRepository repository, MemoryContentSimilarity similarity) {
        this(repository, similarity, null, null);
    }

    @Transactional
    public EpisodicMemory add(String userId, String title, String summary, String episodeType,
                              int importance, int confidence, List<String> keywords,
                              LocalDateTime occurredAt, MemoryProvenance provenance) {
        if (userId == null || userId.isBlank() || summary == null || summary.isBlank()) {
            return null;
        }
        String normalizedSummary = truncate(summary.trim(), 12_000);
        EpisodicMemory duplicate = listActive(userId).stream()
                .filter(memory -> similarity.isDuplicate(memory.getSummary(), normalizedSummary))
                .findFirst().orElse(null);
        if (duplicate != null) {
            duplicate.setTitle(normalizeTitle(title, normalizedSummary));
            duplicate.setSummary(normalizedSummary);
            duplicate.setEpisodeType(normalizeType(episodeType));
            duplicate.setImportance(bounded(importance, 1, 5));
            duplicate.setConfidence(bounded(confidence, 0, 100));
            duplicate.setKeywords(joinKeywords(keywords));
            duplicate.setOccurredAt(occurredAt == null ? duplicate.getOccurredAt() : occurredAt);
            duplicate.setLastConfirmedAt(LocalDateTime.now());
            duplicate.setUpdatedAt(LocalDateTime.now());
            mergeProvenance(duplicate, provenance);
            return indexVector(repository.save(duplicate));
        }
        EpisodicMemory memory = new EpisodicMemory(userId, normalizeTitle(title, normalizedSummary),
                normalizedSummary, normalizeType(episodeType), bounded(importance, 1, 5),
                bounded(confidence, 0, 100), occurredAt, provenance);
        memory.setKeywords(joinKeywords(keywords));
        return indexVector(repository.save(memory));
    }

    /**
     * 情景记忆的候选（2026-09-18，P3）：按向量相似度筛 + 排序，取代原来的字面关键词匹配。
     *
     * <p>为什么换：字面匹配对"换了说法"完全失效（和 P2 给工作记忆做的是同一件事）。
     * 没有向量的行不参与（不做字面兜底）；启动补齐 + 写路径保证活跃行迟早有向量。
     */
    public List<EpisodicMemory> rankByVector(String userId, float[] query, double minScore) {
        if (userId == null || userId.isBlank() || query == null || query.length == 0
                || vectorStore == null || embeddingClient == null) {
            return List.of();
        }
        Map<Long, Double> scores = vectorStore.scores(TABLE, userId, query);
        if (scores.isEmpty()) {
            return List.of();
        }
        List<EpisodicMemory> ranked = new ArrayList<>();
        for (EpisodicMemory memory : listActive(userId)) {
            if (memory == null || memory.getId() == null) {
                continue;
            }
            Double score = scores.get(memory.getId());
            if (score == null || score < minScore) {
                continue;
            }
            ranked.add(memory);
        }
        ranked.sort(Comparator
                .comparingDouble((EpisodicMemory memory) -> scores.getOrDefault(memory.getId(), 0d)).reversed()
                .thenComparing(EpisodicMemory::getImportance, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(EpisodicMemory::getOccurredAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return ranked;
    }

    /** 给一条情景记忆算向量并写回（失败只记日志，记忆本身已经落库） */
    private EpisodicMemory indexVector(EpisodicMemory memory) {
        if (memory == null || memory.getId() == null || embeddingClient == null || vectorStore == null
                || !embeddingClient.isEnabled()) {
            return memory;
        }
        float[] vector = embeddingClient.embedOne(memory.getTitle() + " " + memory.getSummary());
        if (vector != null) {
            vectorStore.saveEmbedding(TABLE, memory.getId(), vector, embeddingClient.model());
        }
        return memory;
    }

    /** 启动时把存量情景记忆的向量补齐一次（量很小） */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillVectorsOnStartup() {
        if (embeddingClient == null || vectorStore == null || !embeddingClient.isEnabled()) {
            return;
        }
        int total = 0;
        for (String userId : vectorStore.userIdsWithMissing(TABLE)) {
            total += reindexMissing(userId, 500);
        }
        if (total > 0) {
            log.info("情景记忆向量补齐完成：{} 条", total);
        }
    }

    /** 给"还没有向量"的情景记忆补向量（一次最多 {@code max} 条） */
    public int reindexMissing(String userId, int max) {
        if (userId == null || userId.isBlank() || max <= 0 || embeddingClient == null || vectorStore == null
                || !embeddingClient.isEnabled()) {
            return 0;
        }
        try {
            Set<Long> embedded = vectorStore.idsWithEmbedding(TABLE, userId);
            List<EpisodicMemory> pending = new ArrayList<>();
            for (EpisodicMemory memory : list(userId)) {
                if (memory == null || memory.getId() == null || embedded.contains(memory.getId())) {
                    continue;
                }
                pending.add(memory);
                if (pending.size() >= max) {
                    break;
                }
            }
            if (pending.isEmpty()) {
                return 0;
            }
            List<float[]> vectors = embeddingClient.embedAll(pending.stream()
                    .map(memory -> memory.getTitle() + " " + memory.getSummary()).toList());
            if (vectors == null || vectors.size() != pending.size()) {
                return 0;
            }
            for (int index = 0; index < pending.size(); index++) {
                vectorStore.saveEmbedding(TABLE, pending.get(index).getId(), vectors.get(index),
                        embeddingClient.model());
            }
            return pending.size();
        } catch (Exception exception) {
            log.warn("补齐情景记忆向量失败 user={} reason={}", userId, exception.getClass().getSimpleName());
            return 0;
        }
    }

    public List<EpisodicMemory> list(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        List<EpisodicMemory> memories = repository.findByUserIdOrderByOccurredAtDesc(userId);
        return memories == null ? List.of() : memories.stream()
                .filter(memory -> memory != null && userId.equals(memory.getUserId()))
                .toList();
    }

    public List<EpisodicMemory> listActive(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        List<EpisodicMemory> memories = repository.findByUserIdAndStatusOrderByOccurredAtDesc(
                userId, MemoryStatus.ACTIVE.name(), PageRequest.of(0, MAX_ACTIVE_SCAN));
        return memories == null ? List.of() : memories.stream()
                .filter(memory -> memory != null && userId.equals(memory.getUserId()))
                .toList();
    }

    @Transactional
    public int forgetEvidence(String userId, List<String> sourceMessageIds, String content) {
        if (userId == null || userId.isBlank()) {
            return 0;
        }
        Set<String> sources = sourceMessageIds == null ? Set.of() : sourceMessageIds.stream()
                .filter(value -> value != null && !value.isBlank()).collect(java.util.stream.Collectors.toSet());
        String normalizedContent = normalize(content);
        List<EpisodicMemory> removed = list(userId).stream()
                .filter(memory -> !sources.isEmpty()
                        ? MemoryProvenance.fromStored("USER_DERIVED", memory.getConfidence(),
                                memory.getSourceMessageIds(), memory.getSourceMediaIds()).sourceMessageIds().stream()
                                .anyMatch(sources::contains)
                        : normalizedContent.length() >= 6 && normalize(memory.getSummary()).contains(normalizedContent))
                .toList();
        if (!removed.isEmpty()) {
            repository.deleteAll(removed);
        }
        return removed.size();
    }

    @Transactional
    public void touch(List<EpisodicMemory> memories, LocalDateTime now, int minimumIntervalMinutes) {
        if (memories == null || memories.isEmpty()) {
            return;
        }
        LocalDateTime timestamp = now == null ? LocalDateTime.now() : now;
        LocalDateTime refreshBefore = timestamp.minusMinutes(Math.max(1, minimumIntervalMinutes));
        // 收集需要刷新的 id 后走定向 UPDATE（见 EpisodicMemoryRepository.updateLastUsedAt 的注释）：
        // 不再 setLastUsedAt + saveAll，避免整行按旧快照回写、把并发的状态变更冲掉。
        List<Long> staleIds = memories.stream()
                .filter(memory -> memory != null && memory.getId() != null
                        && (memory.getLastUsedAt() == null || memory.getLastUsedAt().isBefore(refreshBefore)))
                .map(EpisodicMemory::getId)
                .toList();
        if (!staleIds.isEmpty()) {
            repository.updateLastUsedAt(staleIds, timestamp);
        }
    }

    private void mergeProvenance(EpisodicMemory memory, MemoryProvenance newer) {
        if (newer == null) {
            return;
        }
        MemoryProvenance current = MemoryProvenance.fromStored(memory.getSourceType(), memory.getConfidence(),
                memory.getSourceMessageIds(), memory.getSourceMediaIds());
        MemoryProvenance merged = current.merge(newer);
        memory.setSourceType(merged.sourceType());
        memory.setSourceMessageIds(merged.messageIdsColumn());
        memory.setSourceMediaIds(merged.mediaIdsColumn());
    }

    private String normalizeTitle(String title, String summary) {
        String value = title == null || title.isBlank() ? summary : title.trim();
        return truncate(value, 200);
    }

    private String normalizeType(String type) {
        String value = type == null || type.isBlank() ? "EXPERIENCE" : type.trim().toUpperCase(Locale.ROOT);
        return truncate(value.replaceAll("[^A-Z0-9_]", "_"), 32);
    }

    private String joinKeywords(List<String> keywords) {
        if (keywords == null) {
            return "";
        }
        return keywords.stream().filter(value -> value != null && !value.isBlank())
                .map(String::trim).distinct().limit(12)
                .reduce((left, right) -> left + "|" + right).orElse("");
    }

    private String truncate(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\s]+", "");
    }

    private int bounded(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
