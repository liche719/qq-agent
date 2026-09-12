package com.liche.wechatagent.memory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class EpisodicMemoryService {

    private static final int MAX_ACTIVE_SCAN = 500;

    private final EpisodicMemoryRepository repository;
    private final MemoryContentSimilarity similarity;

    public EpisodicMemoryService(EpisodicMemoryRepository repository, MemoryContentSimilarity similarity) {
        this.repository = repository;
        this.similarity = similarity;
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
            return repository.save(duplicate);
        }
        EpisodicMemory memory = new EpisodicMemory(userId, normalizeTitle(title, normalizedSummary),
                normalizedSummary, normalizeType(episodeType), bounded(importance, 1, 5),
                bounded(confidence, 0, 100), occurredAt, provenance);
        memory.setKeywords(joinKeywords(keywords));
        return repository.save(memory);
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
