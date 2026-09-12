package com.liche.wechatagent.memory;

import com.liche.wechatagent.agent.ContextTurn;
import com.liche.wechatagent.config.MemoryPolicyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ConversationMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemoryService.class);
    private static final Pattern RETRIEVAL_TERM = Pattern.compile("[\\p{IsHan}]{2,}|[a-zA-Z0-9_]{2,}");
    /** 记忆提取只看用户与机器人的对话行；工具调用的 system 行（call + result）不参与提取 */
    private static final List<String> EXTRACTION_ROLES = List.of("user", "assistant");
    private final ConversationMemoryRepository repository;
    private final int extractionLimit;
    private final int retrievalLimit;
    private final int searchPerTerm;
    private final int retentionDays;
    private final int maxContentChars;
    private final int maxRetrievalTerms;
    private final int forgetScanBatchSize;
    private final List<String> retrievalNoise;
    private final ZoneId zone;

    @Autowired
    public ConversationMemoryService(ConversationMemoryRepository repository,
                                     @Value("${memory.conversation-extraction-limit:80}") int extractionLimit,
                                     @Value("${memory.conversation-retrieval-limit:2000}") int retrievalLimit,
                                     @Value("${memory.conversation-search-per-term:16}") int searchPerTerm,
                                     @Value("${memory.conversation-retention-days:3650}") int retentionDays,
                                     @Value("${memory.conversation-max-content-chars:12000}") int maxContentChars,
                                     MemoryPolicyProperties policyProperties,
                                     @Value("${app.time-zone:Asia/Shanghai}") String timeZoneId) {
        this.repository = repository;
        this.extractionLimit = Math.max(2, extractionLimit);
        this.retrievalLimit = Math.max(10, retrievalLimit);
        this.searchPerTerm = Math.max(1, Math.min(100, searchPerTerm));
        this.retentionDays = Math.max(0, retentionDays);
        this.maxContentChars = Math.max(500, maxContentChars);
        MemoryPolicyProperties policies = policyProperties == null ? new MemoryPolicyProperties() : policyProperties;
        this.maxRetrievalTerms = bounded(policies.getConversationMaxRetrievalTerms(), 1, 64,
                MemoryPolicyProperties.DEFAULT_CONVERSATION_MAX_RETRIEVAL_TERMS);
        this.forgetScanBatchSize = bounded(policies.getConversationForgetScanBatch(), 1, 10_000,
                MemoryPolicyProperties.DEFAULT_CONVERSATION_FORGET_SCAN_BATCH);
        this.retrievalNoise = policies.getConversationRetrievalNoise();
        this.zone = parseZone(timeZoneId);
    }

    ConversationMemoryService(ConversationMemoryRepository repository, int extractionLimit, int retrievalLimit,
                              int retentionDays, int maxContentChars) {
        this(repository, extractionLimit, retrievalLimit, 16, retentionDays, maxContentChars,
                new MemoryPolicyProperties(), "Asia/Shanghai");
    }

    @Transactional
    public void record(String userId, String role, String eventKey, String content,
                       List<String> sourceMessageIds, LocalDateTime createdAt) {
        record(userId, role, eventKey, content, sourceMessageIds, List.of(), createdAt);
    }

    @Transactional
    public void record(String userId, String role, String eventKey, String content,
                       List<String> sourceMessageIds, List<Long> sourceMediaIds,
                       LocalDateTime createdAt) {
        if (!validUserId(userId) || !validRole(role) || content == null || content.isBlank()) {
            return;
        }
        String normalized = content.trim();
        if (normalized.length() > maxContentChars) {
            normalized = normalized.substring(0, maxContentChars) + "…";
        }
        LocalDateTime timestamp = createdAt == null ? LocalDateTime.now(zone) : createdAt;
        String normalizedEventKey = normalizeEventKey(eventKey);
        LocalDateTime expiresAt = retentionDays == 0 ? null : timestamp.plusDays(retentionDays);
        try {
            if (normalizedEventKey != null && repository.existsByUserIdAndEventKey(userId, normalizedEventKey)) {
                return;
            }
            repository.save(new ConversationMemory(userId, role.trim().toLowerCase(Locale.ROOT), normalizedEventKey,
                    normalized, sourceMessageIds, sourceMediaIds, timestamp, expiresAt));
        } catch (Exception exception) {
            log.warn("持久化对话证据失败 user={} reason={}", userId, exception.getClass().getSimpleName());
        }
    }

    public List<ContextTurn> recentForExtraction(String userId) {
        return recentForExtraction(userId, extractionLimit);
    }

    public List<ContextTurn> recentForExtraction(String userId, int limit) {
        if (!validUserId(userId)) {
            return List.of();
        }
        try {
            // 只取 user/assistant 行：工具调用会以 system 角色写两条记录，不能占掉提取窗口
            List<ConversationMemory> source = repository.findByUserIdAndRoleInOrderByCreatedAtDesc(userId,
                    EXTRACTION_ROLES, PageRequest.of(0, Math.max(2, Math.min(extractionLimit, limit))));
            List<ConversationMemory> records = new ArrayList<>(source == null ? List.of() : source);
            Collections.reverse(records);
            return records.stream()
                    .filter(record -> record != null && userId.equals(record.getUserId()))
                    .filter(record -> notExpired(record, LocalDateTime.now(zone)))
                    .map(record -> new ContextTurn(record.getRole(), record.getContent(),
                            record.sourceMessageIdList()))
                    .toList();
        } catch (Exception exception) {
            log.warn("读取持久化提取证据失败 user={} reason={}", userId, exception.getClass().getSimpleName());
            return List.of();
        }
    }

    /** Builds the newest complete conversation window that fits the model-context budget. */
    public List<ContextTurn> recentForContext(String userId, int maxTurns, int maxChars) {
        if (!validUserId(userId)) {
            return List.of();
        }
        int turnLimit = Math.max(2, Math.min(256, maxTurns));
        int charLimit = Math.max(500, maxChars);
        try {
            List<ConversationMemory> newest = repository.findByUserIdOrderByCreatedAtDesc(userId,
                    PageRequest.of(0, turnLimit));
            if (newest == null || newest.isEmpty()) {
                return List.of();
            }
            LocalDateTime now = LocalDateTime.now(zone);
            List<ContextTurn> selected = new ArrayList<>();
            int usedChars = 0;
            for (ConversationMemory record : newest) {
                if (record == null || !userId.equals(record.getUserId()) || !notExpired(record, now)
                        || !("user".equalsIgnoreCase(record.getRole())
                        || "assistant".equalsIgnoreCase(record.getRole()))) {
                    continue;
                }
                String content = record.getContent();
                if (content == null || content.isBlank()) {
                    continue;
                }
                if (!selected.isEmpty() && usedChars + content.length() > charLimit) {
                    break;
                }
                String boundedContent = content.length() > charLimit
                        ? content.substring(0, Math.max(1, charLimit - 1)) + "…" : content;
                selected.add(new ContextTurn(record.getRole(), boundedContent, record.sourceMessageIdList()));
                usedChars += boundedContent.length();
            }
            Collections.reverse(selected);
            while (!selected.isEmpty() && "assistant".equalsIgnoreCase(selected.getFirst().role())) {
                selected.removeFirst();
            }
            return List.copyOf(selected);
        } catch (Exception exception) {
            log.warn("按预算读取近期对话失败 user={} reason={}", userId, exception.getClass().getSimpleName());
            return List.of();
        }
    }

    public List<ConversationMemory> recentForRetrieval(String userId) {
        if (!validUserId(userId)) {
            return List.of();
        }
        List<ConversationMemory> paged;
        try {
            List<ConversationMemory> source = repository.findByUserIdOrderByCreatedAtDesc(userId,
                            PageRequest.of(0, retrievalLimit));
            paged = source == null ? List.of() : source;
        } catch (Exception exception) {
            log.warn("读取历史对话失败 user={} reason={}", userId, exception.getClass().getSimpleName());
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now(zone);
        return (paged == null ? List.<ConversationMemory>of() : paged).stream()
                .filter(record -> record != null && userId.equals(record.getUserId()))
                .filter(record -> notExpired(record, now))
                .sorted(Comparator.comparing(ConversationMemory::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(retrievalLimit)
                .toList();
    }

    /**
     * Retrieves a bounded recent window plus older records that contain a distinctive term from the current query.
     * This keeps ordinary requests cheap while allowing an old topic to be recalled even after many newer messages.
     */
    public List<ConversationMemory> relevantForRetrieval(String userId, String query) {
        if (!validUserId(userId)) {
            return List.of();
        }
        LinkedHashMap<String, ConversationMemory> unique = new LinkedHashMap<>();
        addRecentRecords(unique, userId);
        searchHistoricalTerms(unique, userId, query);
        return unique.values().stream()
                .sorted(Comparator.comparing(ConversationMemory::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    // Adds the bounded recent conversation window to the retrieval candidates.
    private void addRecentRecords(LinkedHashMap<String, ConversationMemory> target, String userId) {
        for (ConversationMemory record : recentForRetrieval(userId)) {
            putOwnedActive(target, userId, record);
        }
    }

    // Searches older conversation evidence using distinctive terms from the current query.
    private void searchHistoricalTerms(LinkedHashMap<String, ConversationMemory> target, String userId,
                                       String query) {
        for (String term : retrievalTerms(query)) {
            try {
                List<ConversationMemory> matches = repository.findByUserIdAndContentContainingOrderByCreatedAtDesc(
                        userId, term, PageRequest.of(0, searchPerTerm));
                if (matches != null) {
                    matches.forEach(record -> putOwnedActive(target, userId, record));
                }
            } catch (Exception exception) {
                log.debug("按历史关键词检索对话证据失败 user={} reason={}", userId,
                        exception.getClass().getSimpleName());
            }
        }
    }

    @Transactional
    public int forgetSourceMessageIds(String userId, List<String> sourceMessageIds) {
        Set<String> ids = normalizeIds(sourceMessageIds);
        if (!validUserId(userId) || ids.isEmpty()) {
            return 0;
        }
        return deleteMatching(userId,
                record -> record.sourceMessageIdList().stream().anyMatch(ids::contains));
    }

    @Transactional
    public int forgetContent(String userId, String rememberedContent) {
        String target = normalizeForComparison(rememberedContent);
        if (!validUserId(userId) || target.length() < 6) {
            return 0;
        }
        return deleteMatching(userId, record -> contentContains(record.getContent(), target));
    }

    private int deleteMatching(String userId, Predicate<ConversationMemory> matches) {
        if (!validUserId(userId) || matches == null) {
            return 0;
        }
        long lastId = 0L;
        int deleted = 0;
        try {
            while (true) {
                List<ConversationMemory> records = repository.findByUserIdAndIdGreaterThanOrderByIdAsc(userId, lastId,
                        PageRequest.of(0, forgetScanBatchSize));
                if (records == null || records.isEmpty()) {
                    return deleted;
                }
                List<ConversationMemory> removed = records.stream()
                    .filter(Objects::nonNull)
                    .filter(record -> userId.equals(record.getUserId()))
                    .filter(matches)
                    .toList();
                if (!removed.isEmpty()) {
                    repository.deleteAll(removed);
                    deleted += removed.size();
                }
                Long cursor = records.stream()
                        .filter(Objects::nonNull)
                        .map(ConversationMemory::getId)
                        .filter(Objects::nonNull)
                        .max(Long::compareTo)
                        .orElse(null);
                if (cursor == null || cursor <= lastId || records.size() < forgetScanBatchSize) {
                    return deleted;
                }
                lastId = cursor;
            }
        } catch (Exception exception) {
            log.warn("按内容清理对话证据失败 user={} reason={}", userId, exception.getClass().getSimpleName());
            return deleted;
        }
    }

    @Transactional
    public int purgeExpired() {
        if (retentionDays == 0) {
            return 0;
        }
        try {
            return Math.max(0, repository.deleteExpiredBefore(LocalDateTime.now(zone)));
        } catch (Exception exception) {
            log.debug("对话证据过期扫描暂不可用: {}", exception.getClass().getSimpleName());
            return 0;
        }
    }

    private boolean notExpired(ConversationMemory record, LocalDateTime now) {
        return record != null && (record.getExpiresAt() == null
                || record.getExpiresAt().isAfter(now));
    }

    private void putOwnedActive(LinkedHashMap<String, ConversationMemory> target, String userId,
                                ConversationMemory record) {
        if (record == null || !userId.equals(record.getUserId()) || !notExpired(record, LocalDateTime.now(zone))) {
            return;
        }
        String key = record.getId() == null
                ? record.getUserId() + "\u0000" + record.getEventKey() + "\u0000" + record.getCreatedAt()
                : "id:" + record.getId();
        target.putIfAbsent(key, record);
    }

    private List<String> retrievalTerms(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String cleaned = query;
        for (String noise : retrievalNoise) {
            cleaned = cleaned.replace(noise, " ");
        }
        cleaned = cleaned.replaceAll("[的了啊呀吗呢吧请帮问]", " ");
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        Matcher matcher = RETRIEVAL_TERM.matcher(cleaned);
        while (matcher.find()) {
            String term = matcher.group().trim();
            if (term.length() < 2) {
                continue;
            }
            if (term.length() <= 12) {
                candidates.add(term);
            }
            for (int length = Math.min(6, term.length()); length >= 2; length--) {
                for (int start = 0; start + length <= term.length(); start++) {
                    candidates.add(term.substring(start, start + length));
                }
            }
        }
        return candidates.stream()
                .filter(term -> term.length() >= 2)
                .sorted(Comparator.comparingInt(String::length).reversed())
                .limit(maxRetrievalTerms)
                .toList();
    }

    private boolean validUserId(String userId) {
        return userId != null && !userId.isBlank();
    }

    private boolean validRole(String role) {
        if (role == null || role.isBlank()) {
            return false;
        }
        String normalized = role.trim().toLowerCase(Locale.ROOT);
        return "user".equals(normalized) || "assistant".equals(normalized) || "system".equals(normalized);
    }

    private String normalizeEventKey(String eventKey) {
        if (eventKey == null || eventKey.isBlank()) {
            return null;
        }
        String normalized = eventKey.trim().replace('|', '_');
        return normalized.length() > 128 ? normalized.substring(0, 128) : normalized;
    }

    private Set<String> normalizeIds(List<String> values) {
        Set<String> ids = new HashSet<>();
        if (values != null) {
            values.stream().filter(value -> value != null && !value.isBlank())
                    .map(String::trim).forEach(ids::add);
        }
        return ids;
    }

    private boolean contentContains(String content, String target) {
        String normalized = normalizeForComparison(content);
        if (normalized.length() < 6) {
            return false;
        }
        if (normalized.contains(target) || target.contains(normalized)) {
            return true;
        }
        int maximum = Math.min(24, Math.min(normalized.length(), target.length()));
        for (int length = maximum; length >= 6; length--) {
            for (int start = 0; start + length <= normalized.length(); start++) {
                if (target.contains(normalized.substring(start, start + length))) {
                    return true;
                }
            }
        }
        return false;
    }

    private String normalizeForComparison(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{P}\\p{Z}\\s]+", "")
                .trim();
    }

    private int bounded(int value, int minimum, int maximum, int fallback) {
        return value < minimum || value > maximum ? fallback : value;
    }

    private ZoneId parseZone(String value) {
        try {
            return ZoneId.of(value);
        } catch (RuntimeException ignored) {
            return ZoneId.of("Asia/Shanghai");
        }
    }
}
