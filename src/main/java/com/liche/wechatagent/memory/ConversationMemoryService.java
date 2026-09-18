package com.liche.wechatagent.memory;

import com.liche.wechatagent.agent.ContextTurn;
import com.liche.wechatagent.config.EmbeddingClient;
import com.liche.wechatagent.config.MemoryPolicyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
    /** 记忆提取只看用户与机器人的对话行；工具调用的 system 行（call + result）不参与提取 */
    private static final List<String> EXTRACTION_ROLES = List.of("user", "assistant");
    private final ConversationMemoryRepository repository;
    private final EmbeddingClient embeddingClient;
    private final PgVectorStore vectorStore;
    private final int extractionLimit;
    private final int retrievalLimit;
    private final int retentionDays;
    private final int maxContentChars;
    private final int forgetScanBatchSize;
    private final ZoneId zone;

    @Autowired
    public ConversationMemoryService(ConversationMemoryRepository repository,
                                     EmbeddingClient embeddingClient,
                                     PgVectorStore vectorStore,
                                     @Value("${memory.conversation-extraction-limit:80}") int extractionLimit,
                                     @Value("${memory.conversation-retrieval-limit:2000}") int retrievalLimit,
                                     @Value("${memory.conversation-retention-days:3650}") int retentionDays,
                                     @Value("${memory.conversation-max-content-chars:12000}") int maxContentChars,
                                     MemoryPolicyProperties policyProperties,
                                     @Value("${app.time-zone:Asia/Shanghai}") String timeZoneId) {
        this.repository = repository;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.extractionLimit = Math.max(2, extractionLimit);
        this.retrievalLimit = Math.max(10, retrievalLimit);
        this.retentionDays = Math.max(0, retentionDays);
        this.maxContentChars = Math.max(500, maxContentChars);
        MemoryPolicyProperties policies = policyProperties == null ? new MemoryPolicyProperties() : policyProperties;
        this.forgetScanBatchSize = bounded(policies.getConversationForgetScanBatch(), 1, 10_000,
                MemoryPolicyProperties.DEFAULT_CONVERSATION_FORGET_SCAN_BATCH);
        this.zone = parseZone(timeZoneId);
    }

    ConversationMemoryService(ConversationMemoryRepository repository, int extractionLimit, int retrievalLimit,
                              int retentionDays, int maxContentChars) {
        this(repository, null, null, extractionLimit, retrievalLimit, retentionDays, maxContentChars,
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
            ConversationMemory saved = repository.save(new ConversationMemory(userId, role.trim().toLowerCase(Locale.ROOT),
                    normalizedEventKey, normalized, sourceMessageIds, sourceMediaIds, timestamp, expiresAt));
            indexVector(saved);
        } catch (Exception exception) {
            log.warn("持久化对话证据失败 user={} reason={}", userId, exception.getClass().getSimpleName());
        }
    }

    /**
     * 给一条对话证据算向量（2026-09-18，P3）。
     *
     * <p>只给 user/assistant 正文算：工具调用那两条 system 记录只是轨迹，不是"说过的话"。
     * 写发生在回复发出**之后**，所以这次 embedding 调用不会让用户多等。
     */
    private void indexVector(ConversationMemory record) {
        if (record == null || record.getId() == null || embeddingClient == null || vectorStore == null
                || !embeddingClient.isEnabled()) {
            return;
        }
        String role = record.getRole() == null ? "" : record.getRole().toLowerCase(Locale.ROOT);
        if (!"user".equals(role) && !"assistant".equals(role)) {
            return;
        }
        float[] vector = embeddingClient.embedOne(record.getContent());
        if (vector != null) {
            vectorStore.saveEmbedding("conversation_memory", record.getId(), vector, embeddingClient.model());
        }
    }

    /**
     * 按语义取回该用户最相关的原始对话证据（top-K，只留相似度 ≥ {@code minScore} 的）。
     *
     * <p>取代了原来"最近窗口 + 按字面词 LIKE 捞旧记录"的 {@code relevantForRetrieval}：
     * 那条路对无关问题也会塞满 1500 字旧对话，而且换个说法就捞不到。
     */
    public List<ConversationHit> searchByVector(String userId, float[] query, int limit, double minScore) {
        if (!validUserId(userId) || vectorStore == null || query == null || query.length == 0 || limit <= 0) {
            return List.of();
        }
        List<PgVectorStore.Hit> hits = vectorStore.search("conversation_memory", userId, query, limit, minScore);
        if (hits.isEmpty()) {
            return List.of();
        }
        Map<Long, Double> scores = new LinkedHashMap<>();
        for (PgVectorStore.Hit hit : hits) {
            scores.put(hit.id(), hit.score());
        }
        LocalDateTime now = LocalDateTime.now(zone);
        return new ArrayList<>(repository.findAllById(scores.keySet())).stream()
                .filter(record -> record != null && userId.equals(record.getUserId()))
                .filter(record -> notExpired(record, now))
                .map(record -> new ConversationHit(record, scores.getOrDefault(record.getId(), 0d)))
                .sorted(Comparator.comparingDouble(ConversationHit::score).reversed())
                .toList();
    }

    /** 一条对话证据 + 它的余弦相似度（相似度只在"排序与文案"里用得到） */
    public record ConversationHit(ConversationMemory record, double score) {
    }

    /**
     * 启动时把存量对话证据的向量补齐一次（生产 1100+ 行，分批 500；失败只记日志）。
     * 缺向量的行**不参与检索**（P3 没有字面兜底这条路），所以日志里要看得到补齐结果。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillVectorsOnStartup() {
        if (embeddingClient == null || vectorStore == null || !embeddingClient.isEnabled()) {
            return;
        }
        int total = 0;
        for (String userId : vectorStore.userIdsWithMissing("conversation_memory")) {
            // 一个用户可能远超一批（生产机主 678 条对话正文）：**补到没有为止**，否则剩下的要等下次重启才可见。
            // 20 批 = 1 万条上限；每批"补不满一批"就跳出，失败返回 0 也跳出，不会死循环。
            for (int pass = 0; pass < 20; pass++) {
                int done = reindexMissing(userId, 500);
                total += done;
                if (done < 500) {
                    break;
                }
            }
        }
        if (total > 0) {
            log.info("对话证据向量补齐完成：{} 条", total);
        }
    }

    /** 给"还没有向量"的对话证据补向量（一次最多 {@code max} 条，只补 user/assistant 正文） */
    public int reindexMissing(String userId, int max) {
        if (!validUserId(userId) || max <= 0 || embeddingClient == null || vectorStore == null
                || !embeddingClient.isEnabled()) {
            return 0;
        }
        try {
            Set<Long> embedded = vectorStore.idsWithEmbedding("conversation_memory", userId);
            List<ConversationMemory> pending = new ArrayList<>();
            long lastId = 0L;
            while (pending.size() < max) {
                List<ConversationMemory> batch = repository.findByUserIdAndIdGreaterThanOrderByIdAsc(userId, lastId,
                        PageRequest.of(0, Math.min(200, max)));
                if (batch == null || batch.isEmpty()) {
                    break;
                }
                for (ConversationMemory record : batch) {
                    lastId = Math.max(lastId, record.getId() == null ? 0L : record.getId());
                    if (record.getId() == null || embedded.contains(record.getId())) {
                        continue;
                    }
                    String role = record.getRole() == null ? "" : record.getRole().toLowerCase(Locale.ROOT);
                    if (!"user".equals(role) && !"assistant".equals(role)) {
                        continue;
                    }
                    pending.add(record);
                    if (pending.size() >= max) {
                        break;
                    }
                }
                if (batch.size() < Math.min(200, max)) {
                    break;
                }
            }
            if (pending.isEmpty()) {
                return 0;
            }
            List<float[]> vectors = embeddingClient.embedAll(pending.stream()
                    .map(ConversationMemory::getContent).toList());
            if (vectors == null || vectors.size() != pending.size()) {
                return 0;
            }
            // 工具轨迹那两条 system 行没有向量、永远会出现在"缺向量"里，所以这里的 max 只当"补多少条"用，
            // 不做"补完就没有了"的判断——面板看的是 distinct 用户，不会因此反复扫。
            for (int index = 0; index < pending.size(); index++) {
                vectorStore.saveEmbedding("conversation_memory", pending.get(index).getId(), vectors.get(index),
                        embeddingClient.model());
            }
            return pending.size();
        } catch (Exception exception) {
            log.warn("补齐对话证据向量失败 user={} reason={}", userId, exception.getClass().getSimpleName());
            return 0;
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
