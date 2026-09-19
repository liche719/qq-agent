package com.liche.wechatagent.memory;

import com.liche.wechatagent.config.EmbeddingClient;
import com.liche.wechatagent.config.MemoryPolicyProperties;
import com.liche.wechatagent.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 记忆服务（2026-09-18 三合一）：core / work / episode 三套几乎同构的逻辑合并到这一个类，**按 {@link Memory#getKind()} 分派**。
 *
 * <p>三条行为线（和合并前逐字保持一致，只是从三张表变成一张）：
 * <ul>
 *   <li><b>PROFILE</b>（原 core）：长期设定，写路径靠"字面相似度判重 + 模型判定换说法"更新，注入时无条件在场；</li>
 *   <li><b>TASK</b>（原 work）：中期事项，写路径判重确认 / 被新说法替代（旧行 SUPERSEDED），有有效期，到点自动过期；</li>
 *   <li><b>EXPERIENCE</b>（原 episode）：经历叙事，写路径按摘要判重并合并，带 title/类型/发生时间。</li>
 * </ul>
 *
 * <p>三种都：写路径算一次向量（{@link PgVectorStore} 的原生 SQL）；每次改动写一行变更留痕；
 * 遗忘时删除行 + 脱敏日志 + 交给备份清理。
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);
    private static final String TABLE = "memory";
    private static final int MAX_ACTIVE_SCAN = 500;

    private final MemoryRepository repository;
    private final MemoryChangeLogRepository changeLogRepository;
    private final MemoryContentSimilarity similarity;
    private final EmbeddingClient embeddingClient;
    private final PgVectorStore vectorStore;
    private final int coreMaxContentChars;
    private final int taskMaxContentChars;
    private final int experienceMaxContentChars;
    private final int defaultConfidence;
    private final int defaultPriority;

    @Autowired
    public MemoryService(MemoryRepository repository,
                         MemoryChangeLogRepository changeLogRepository,
                         MemoryContentSimilarity similarity,
                         MemoryPolicyProperties policyProperties,
                         EmbeddingClient embeddingClient,
                         PgVectorStore vectorStore) {
        this.repository = repository;
        this.changeLogRepository = changeLogRepository;
        this.similarity = similarity;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        MemoryPolicyProperties policies = policyProperties == null ? new MemoryPolicyProperties() : policyProperties;
        this.coreMaxContentChars = bounded(policies.getCoreMaxContentChars(), 128,
                MemoryPolicyProperties.CORE_CONTENT_COLUMN_MAX_CHARS,
                MemoryPolicyProperties.DEFAULT_CORE_MAX_CONTENT_CHARS);
        this.taskMaxContentChars = bounded(policies.getWorkMaxContentChars(), 128,
                MemoryPolicyProperties.WORK_CONTENT_COLUMN_MAX_CHARS,
                MemoryPolicyProperties.DEFAULT_WORK_MAX_CONTENT_CHARS);
        this.experienceMaxContentChars = 12_000;
        this.defaultConfidence = bounded(policies.getExtractionDefaultConfidence(), 0, 100,
                MemoryPolicyProperties.DEFAULT_EXTRACTION_CONFIDENCE);
        this.defaultPriority = bounded(policies.getDefaultWorkPriority(), 1, 5,
                MemoryPolicyProperties.DEFAULT_WORK_PRIORITY);
    }

    public MemoryService(MemoryRepository repository, MemoryChangeLogRepository changeLogRepository,
                         MemoryContentSimilarity similarity) {
        this(repository, changeLogRepository, similarity, new MemoryPolicyProperties(), null, null);
    }

    // ================= 读 =================

    /** 某个用户的全部记忆（含非活跃），按更新时间倒序 */
    public List<Memory> list(String userId) {
        if (!validUserId(userId)) {
            return List.of();
        }
        List<Memory> records = repository.findByUserIdOrderByUpdatedAtDesc(userId);
        if (records == null) {
            return List.of();
        }
        return records.stream()
                .filter(memory -> memory != null && userId.equals(memory.getUserId()))
                .toList();
    }

    public List<Memory> listByKind(String userId, String kind) {
        if (!validUserId(userId)) {
            return List.of();
        }
        List<Memory> records = repository.findByUserIdAndKindOrderByUpdatedAtDesc(userId, kind);
        if (records == null) {
            return List.of();
        }
        return records.stream()
                .filter(memory -> memory != null && userId.equals(memory.getUserId()))
                .toList();
    }

    /** 仍然生效的记忆（状态活跃且没到有效期） */
    public List<Memory> listActive(String userId) {
        LocalDateTime now = LocalDateTime.now();
        return list(userId).stream().filter(memory -> isActive(memory, now)).toList();
    }

    public List<Memory> listActive(String userId, String kind) {
        LocalDateTime now = LocalDateTime.now();
        return listByKind(userId, kind).stream().filter(memory -> isActive(memory, now)).toList();
    }

    /** 每轮无条件注入的那些（原 core）：活跃 + 只认显式来源 */
    public List<Memory> listAlwaysInject(String userId) {
        return listActive(userId, Memory.KIND_PROFILE).stream().filter(MemoryService::isExplicit).toList();
    }

    /** 已经结束/过期/被替代的（面板"近期已结束"用） */
    public List<Memory> listInactive(String userId) {
        LocalDateTime now = LocalDateTime.now();
        return list(userId).stream().filter(memory -> !isActive(memory, now)).toList();
    }

    public Memory find(String userId, Long id) {
        if (!validUserId(userId) || id == null) {
            return null;
        }
        return repository.findById(id)
                .filter(memory -> userId.equals(memory.getUserId()))
                .orElse(null);
    }

    public long count(String userId) {
        return validUserId(userId) ? repository.countByUserId(userId) : 0L;
    }

    public long countByKind(String userId, String kind) {
        return validUserId(userId) ? repository.countByUserIdAndKind(userId, kind) : 0L;
    }

    public long countActive(String userId) {
        return validUserId(userId) ? repository.countByUserIdAndStatus(userId, MemoryStatus.ACTIVE.name()) : 0L;
    }

    public static boolean isActive(Memory memory, LocalDateTime now) {
        if (memory == null || !isStoredActive(memory)) {
            return false;
        }
        LocalDateTime effectiveNow = now == null ? LocalDateTime.now() : now;
        return memory.getValidUntil() == null || memory.getValidUntil().isAfter(effectiveNow);
    }

    private static boolean isStoredActive(Memory memory) {
        return memory.getStatus() == null || memory.getStatus().isBlank()
                || MemoryStatus.ACTIVE.name().equals(memory.getStatus());
    }

    /** 只认显式来源（USER_EXPLICIT / USER_DERIVED / 老数据的 null）——写错这个字段记忆会"凭空消失" */
    public static boolean isExplicit(Memory memory) {
        return memory != null && (memory.getSourceType() == null || memory.getSourceType().isBlank()
                || "USER_EXPLICIT".equalsIgnoreCase(memory.getSourceType())
                || "USER_DERIVED".equalsIgnoreCase(memory.getSourceType()));
    }

    // ================= 写：PROFILE（原 core） =================

    @Transactional
    public Memory addProfile(String userId, String content, String operator) {
        return addProfile(userId, content, operator, MemoryProvenance.automatic("extraction"), profileDefaults());
    }

    @Transactional
    public Memory addProfile(String userId, String content, String operator, MemoryProvenance provenance) {
        return addProfile(userId, content, operator, provenance, profileDefaults());
    }

    @Transactional
    public Memory addProfile(String userId, String content, String operator, MemoryProvenance provenance,
                             MemoryAttributes attributes) {
        requireUserId(userId);
        String normalized = normalizeContent(content, coreMaxContentChars, "核心记忆");
        MemoryAttributes normalizedAttributes = attributes == null ? profileDefaults() : attributes;
        Memory existing = listActive(userId, Memory.KIND_PROFILE).stream()
                .filter(memory -> similarity.isDuplicate(normalized, memory.getContent()))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            applyProvenance(existing, provenance, true);
            applyAttributes(existing, MemoryAttributes.fromStored(existing.getImportance(), existing.getConfidence(),
                    existing.getKeywords()).merge(normalizedAttributes));
            existing.setLastConfirmedAt(LocalDateTime.now());
            existing.setUpdatedAt(LocalDateTime.now());
            repository.save(existing);
            changeLogRepository.save(new MemoryChangeLog(userId, "CONFIRM", existing.getKind(), existing.getId(),
                    existing.getContent(), existing.getContent(), "用户再次明确确认已有长期记忆", operator));
            return existing;
        }
        Memory memory = new Memory(userId, Memory.KIND_PROFILE, normalized);
        memory.setAlwaysInject(true);
        memory.setPriority(null);
        applyProvenance(memory, provenance, false);
        applyAttributes(memory, normalizedAttributes);
        memory.setLastConfirmedAt(LocalDateTime.now());
        Memory saved = repository.save(memory);
        indexVector(saved);
        changeLogRepository.save(new MemoryChangeLog(userId, "ADD", saved.getKind(), saved.getId(), null, normalized,
                "自动提取长期稳定记忆", operator));
        return saved;
    }

    @Transactional
    public void updateProfile(String userId, Long id, String newContent, String reason, String operator) {
        replaceProfile(userId, id, newContent, reason, operator, MemoryProvenance.automatic("extraction"),
                profileDefaults());
    }

    /** 用新版本替代旧的长期设定：旧行标 SUPERSEDED（不删），新行落库，两边都留痕 */
    @Transactional
    public Memory replaceProfile(String userId, Long id, String newContent, String reason, String operator,
                                 MemoryProvenance provenance, MemoryAttributes attributes) {
        requireUserId(userId);
        if (id == null) {
            throw new BizException("记忆编号不能为空");
        }
        Memory memory = ownedMemory(userId, id);
        String normalized = normalizeContent(newContent, coreMaxContentChars, "核心记忆");
        LocalDateTime now = LocalDateTime.now();
        if (normalized.equals(memory.getContent())) {
            applyProvenance(memory, provenance, true);
            applyAttributes(memory, attributes == null ? profileDefaults() : attributes);
            memory.setLastConfirmedAt(now);
            memory.setUpdatedAt(now);
            repository.save(memory);
            changeLogRepository.save(new MemoryChangeLog(userId, "CONFIRM", memory.getKind(), id,
                    memory.getContent(), memory.getContent(), "重复事实再次确认", operator));
            return memory;
        }
        Memory replacement = new Memory(userId, memory.getKind(), normalized);
        replacement.setAlwaysInject(memory.isAlwaysInject());
        replacement.setPriority(memory.getPriority());
        MemoryProvenance mergedProvenance = storedProvenance(memory).merge(
                provenance == null ? MemoryProvenance.automatic("extraction") : provenance);
        applyProvenance(replacement, mergedProvenance, false);
        applyAttributes(replacement, attributes == null ? profileDefaults() : attributes);
        replacement.setLastConfirmedAt(now);
        replacement.setUpdatedAt(now);
        Memory savedReplacement = repo(repository.save(replacement), replacement);
        indexVector(savedReplacement);
        memory.setStatus(MemoryStatus.SUPERSEDED.name());
        memory.setSupersededById(savedReplacement.getId());
        memory.setUpdatedAt(now);
        memory.setLastDecisionAt(now);
        repository.save(memory);
        changeLogRepository.save(new MemoryChangeLog(userId, "SUPERSEDE", memory.getKind(), id,
                memory.getContent(), normalized, reason, operator));
        changeLogRepository.save(new MemoryChangeLog(userId, "ADD", memory.getKind(), savedReplacement.getId(),
                null, normalized, "替代旧记忆", operator));
        return savedReplacement;
    }

    // ================= 写：TASK（原 work） =================

    @Transactional
    public Memory addTask(String userId, String content, Integer priority, String source, String operator) {
        return addTask(userId, content, priority, source, operator, MemoryProvenance.automatic(source), null,
                taskDefaults());
    }

    @Transactional
    public Memory addTask(String userId, String content, Integer priority, String source, String operator,
                          MemoryProvenance provenance, LocalDateTime validUntil) {
        return addTask(userId, content, priority, source, operator, provenance, validUntil, taskDefaults());
    }

    @Transactional
    public Memory addTask(String userId, String content, Integer priority, String source, String operator,
                          MemoryProvenance provenance, LocalDateTime validUntil, MemoryAttributes attributes) {
        requireUserId(userId);
        String normalized = normalizeContent(content, taskMaxContentChars, "记忆");
        LocalDateTime now = LocalDateTime.now();
        MemoryAttributes normalizedAttributes = attributes == null ? taskDefaults() : attributes;
        Memory existing = listActive(userId, Memory.KIND_TASK).stream()
                .filter(memory -> similarity.isDuplicate(normalized, memory.getContent()))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            existing.setPriority(Math.max(existing.getPriority() == null ? defaultPriority : existing.getPriority(),
                    normalizedPriority(priority)));
            existing.setLastConfirmedAt(now);
            existing.setUpdatedAt(now);
            if (validUntil != null) {
                existing.setValidUntil(validUntil);
            }
            applyProvenance(existing, provenance, true);
            applyAttributes(existing, MemoryAttributes.fromStored(existing.getImportance(), existing.getConfidence(),
                    existing.getKeywords()).merge(normalizedAttributes));
            repository.save(existing);
            changeLogRepository.save(new MemoryChangeLog(userId, "CONFIRM", existing.getKind(), existing.getId(),
                    existing.getContent(), existing.getContent(), "用户再次明确确认已有工作记忆", operator));
            return existing;
        }
        Memory memory = new Memory(userId, Memory.KIND_TASK, normalized);
        memory.setPriority(normalizedPriority(priority));
        memory.setSource(source == null ? "extraction" : source);
        applyLifecycle(memory, provenance, validUntil, false);
        applyAttributes(memory, normalizedAttributes);
        repository.save(memory);
        indexVector(memory);
        changeLogRepository.save(new MemoryChangeLog(userId, "ADD", memory.getKind(), memory.getId(), null, normalized,
                "自动记忆提取", operator));
        return memory;
    }

    @Transactional
    public void updateTask(String userId, Long id, String newContent) {
        replaceTask(userId, id, newContent, MemoryProvenance.automatic("extraction"), null, taskDefaults());
    }

    /** 用新说法替代旧的事项：旧行 SUPERSEDED（保留可追溯），新行落库 */
    @Transactional
    public Memory replaceTask(String userId, Long id, String newContent, MemoryProvenance provenance,
                              LocalDateTime validUntil, MemoryAttributes attributes) {
        requireUserId(userId);
        if (id == null) {
            throw new BizException("记忆编号不能为空");
        }
        Memory memory = ownedMemory(userId, id);
        String normalized = normalizeContent(newContent, taskMaxContentChars, "记忆");
        LocalDateTime now = LocalDateTime.now();
        if (normalized.equals(memory.getContent())) {
            memory.setUpdatedAt(now);
            memory.setLastConfirmedAt(now);
            applyLifecycle(memory, provenance, validUntil, true);
            applyAttributes(memory, attributes == null ? taskDefaults() : attributes);
            repository.save(memory);
            changeLogRepository.save(new MemoryChangeLog(userId, "CONFIRM", memory.getKind(), id,
                    memory.getContent(), memory.getContent(), "重复事实再次确认", "AUTO"));
            return memory;
        }
        Memory replacement = new Memory(userId, memory.getKind(), normalized);
        replacement.setPriority(memory.getPriority());
        replacement.setSource(memory.getSource());
        MemoryProvenance mergedProvenance = storedProvenance(memory).merge(
                provenance == null ? MemoryProvenance.automatic("extraction") : provenance);
        applyLifecycle(replacement, mergedProvenance, validUntil, false);
        applyAttributes(replacement, attributes == null ? taskDefaults() : attributes);
        replacement.setValidFrom(memory.getValidFrom() == null ? now : memory.getValidFrom());
        replacement.setLastConfirmedAt(now);
        replacement.setUpdatedAt(now);
        Memory savedReplacement = repo(repository.save(replacement), replacement);
        indexVector(savedReplacement);
        memory.setStatus(MemoryStatus.SUPERSEDED.name());
        memory.setSupersededById(savedReplacement.getId());
        memory.setUpdatedAt(now);
        memory.setLastDecisionAt(now);
        repository.save(memory);
        changeLogRepository.save(new MemoryChangeLog(userId, "SUPERSEDE", memory.getKind(), id,
                memory.getContent(), normalized, "用户明确陈述的新事实替代旧事实", "AUTO"));
        changeLogRepository.save(new MemoryChangeLog(userId, "ADD", memory.getKind(), savedReplacement.getId(),
                null, normalized, "替代旧工作记忆", "AUTO"));
        return savedReplacement;
    }

    // ================= 写：EXPERIENCE（原 episode） =================

    @Transactional
    public Memory addExperience(String userId, String title, String summary, String episodeType,
                                int importance, int confidence, List<String> keywords,
                                LocalDateTime occurredAt, MemoryProvenance provenance) {
        if (!validUserId(userId) || summary == null || summary.isBlank()) {
            return null;
        }
        String normalizedSummary = truncate(summary.trim(), experienceMaxContentChars);
        Memory duplicate = listActive(userId, Memory.KIND_EXPERIENCE).stream()
                .filter(memory -> similarity.isDuplicate(memory.getContent(), normalizedSummary))
                .findFirst().orElse(null);
        if (duplicate != null) {
            duplicate.setTitle(normalizeTitle(title, normalizedSummary));
            duplicate.setContent(normalizedSummary);
            duplicate.setCategory(normalizeType(episodeType));
            duplicate.setImportance(bounded(importance, 1, 5));
            duplicate.setConfidence(bounded(confidence, 0, 100));
            duplicate.setKeywords(joinKeywords(keywords));
            duplicate.setOccurredAt(occurredAt == null ? duplicate.getOccurredAt() : occurredAt);
            duplicate.setLastConfirmedAt(LocalDateTime.now());
            duplicate.setUpdatedAt(LocalDateTime.now());
            mergeProvenance(duplicate, provenance);
            Memory saved = repository.save(duplicate);
            indexVector(saved);
            return saved;
        }
        Memory memory = new Memory(userId, Memory.KIND_EXPERIENCE, normalizedSummary);
        memory.setTitle(normalizeTitle(title, normalizedSummary));
        memory.setCategory(normalizeType(episodeType));
        memory.setImportance(bounded(importance, 1, 5));
        memory.setConfidence(bounded(confidence, 0, 100));
        memory.setKeywords(joinKeywords(keywords));
        memory.setPriority(null);
        memory.setOccurredAt(occurredAt == null ? LocalDateTime.now() : occurredAt);
        memory.setValidFrom(memory.getOccurredAt());
        mergeProvenance(memory, provenance);
        Memory saved = repo(repository.save(memory), memory);
        indexVector(saved);
        return saved;
    }

    // ================= 生命周期 =================

    @Transactional
    public void markCompleted(String userId, Long id, String reason, MemoryProvenance provenance) {
        requireUserId(userId);
        Memory memory = ownedMemory(userId, id);
        if (!isActive(memory, LocalDateTime.now())) {
            return;
        }
        memory.setStatus(MemoryStatus.COMPLETED.name());
        memory.setUpdatedAt(LocalDateTime.now());
        memory.setLastConfirmedAt(LocalDateTime.now());
        applyProvenance(memory, provenance, true);
        memory.setLastDecisionAt(LocalDateTime.now());
        repository.save(memory);
        changeLogRepository.save(new MemoryChangeLog(userId, "COMPLETE", memory.getKind(), id,
                memory.getContent(), memory.getContent(),
                reason == null || reason.isBlank() ? "用户明确表示该事项已完成" : reason, "AUTO"));
    }

    /** 有效期到了就标 EXPIRED（每小时扫一次，见 MemoryLifecycleService） */
    @Transactional
    public int expireDueMemories() {
        LocalDateTime now = LocalDateTime.now();
        int changed = 0;
        List<Memory> due = repository.findByValidUntilBefore(now);
        if (due == null) {
            return 0;
        }
        for (Memory memory : due) {
            if (memory == null || !isStoredActive(memory)
                    || memory.getValidUntil() == null || memory.getValidUntil().isAfter(now)) {
                continue;
            }
            memory.setStatus(MemoryStatus.EXPIRED.name());
            memory.setUpdatedAt(now);
            repository.save(memory);
            changeLogRepository.save(new MemoryChangeLog(memory.getUserId(), "EXPIRE", memory.getKind(), memory.getId(),
                    memory.getContent(), memory.getContent(), "记忆有效期已过期", "SYSTEM"));
            changed++;
        }
        return changed;
    }

    /** 刷"最近被用过"（面板反查"这轮注入了什么"就靠它）；定向 UPDATE，避免整行回写 */
    @Transactional
    public void touch(List<Memory> memories, LocalDateTime now, int minimumIntervalMinutes) {
        if (memories == null || memories.isEmpty()) {
            return;
        }
        LocalDateTime timestamp = now == null ? LocalDateTime.now() : now;
        LocalDateTime refreshBefore = timestamp.minusMinutes(Math.max(1, minimumIntervalMinutes));
        List<Long> staleIds = memories.stream()
                .filter(memory -> memory != null && memory.getId() != null
                        && (memory.getLastUsedAt() == null || memory.getLastUsedAt().isBefore(refreshBefore)))
                .map(Memory::getId)
                .toList();
        if (!staleIds.isEmpty()) {
            repository.updateLastUsedAt(staleIds, timestamp);
        }
    }

    // ================= 遗忘 =================

    @Transactional
    public ForgottenMemory forget(String userId, Long id) {
        requireUserId(userId);
        if (id == null) {
            throw new BizException("记忆编号不能为空");
        }
        Memory memory = ownedMemory(userId, id);
        ForgottenMemory forgotten = new ForgottenMemory(memory.getKind(), memory.getId(), memory.getContent(),
                memory.getSource(),
                MemoryProvenance.fromStored(memory.getSourceType(), memory.getConfidence(),
                        memory.getSourceMessageIds(), memory.getSourceMediaIds()).sourceMessageIds());
        repository.delete(memory);
        changeLogRepository.redactContentForMemory(userId, memory.getKind(), id);
        changeLogRepository.save(new MemoryChangeLog(userId, "FORGET", memory.getKind(), id, null, null,
                "用户主动遗忘；审计正文已清除", "USER"));
        return forgotten;
    }

    /** 遗忘某条时，把它"替代链"上的历史版本一起忘掉（用户要的是这件事彻底消失） */
    @Transactional
    public List<ForgottenMemory> forgetSupersededHistory(String userId, Long memoryId) {
        List<ForgottenMemory> forgotten = new ArrayList<>();
        if (memoryId == null) {
            return forgotten;
        }
        List<Memory> all = list(userId);
        if (all.isEmpty()) {
            return forgotten;
        }
        Set<Long> related = new LinkedHashSet<>();
        related.add(memoryId);
        boolean changed;
        do {
            changed = false;
            for (Memory memory : all) {
                if (memory.getId() == null) {
                    continue;
                }
                if (related.contains(memory.getId())) {
                    if (memory.getSupersededById() != null && related.add(memory.getSupersededById())) {
                        changed = true;
                    }
                    continue;
                }
                if (related.contains(memory.getSupersededById())) {
                    related.add(memory.getId());
                    changed = true;
                }
            }
        } while (changed);
        for (Long id : related) {
            if (id.equals(memoryId)) {
                continue;
            }
            Memory memory = all.stream().filter(item -> id.equals(item.getId())).findFirst().orElse(null);
            if (memory != null && userId.equals(memory.getUserId())) {
                forgotten.add(forget(userId, id));
            }
        }
        return forgotten;
    }

    /** 按"证据来源"遗忘经历类记忆（用户删掉某段聊天时连带清掉由它推出来的经历） */
    @Transactional
    public int forgetEvidence(String userId, List<String> sourceMessageIds, String content) {
        if (!validUserId(userId)) {
            return 0;
        }
        Set<String> sources = sourceMessageIds == null ? Set.of() : sourceMessageIds.stream()
                .filter(value -> value != null && !value.isBlank()).collect(java.util.stream.Collectors.toSet());
        String normalizedContent = normalizeForComparison(content);
        List<Memory> removed = list(userId).stream()
                .filter(memory -> !sources.isEmpty()
                        ? MemoryProvenance.fromStored("USER_DERIVED", memory.getConfidence(),
                                memory.getSourceMessageIds(), memory.getSourceMediaIds()).sourceMessageIds().stream()
                                .anyMatch(sources::contains)
                        : normalizedContent.length() >= 6 && normalizeForComparison(memory.getContent())
                                .contains(normalizedContent))
                .toList();
        if (!removed.isEmpty()) {
            repository.deleteAll(removed);
        }
        return removed.size();
    }

    // ================= 向量 =================

    /**
     * 按语义挑"跟当前这句像"的记忆（TASK / EXPERIENCE 用；PROFILE 是无条件注入，不走这里）。
     *
     * <p>只有相似度 ≥ {@code floor} 的才返回——**没有字面兜底**，算不出向量的行就是不参与。
     */
    public List<Memory> rankByVector(String userId, float[] query, double floor, String... kinds) {
        if (!validUserId(userId) || query == null || query.length == 0 || vectorStore == null) {
            return List.of();
        }
        Set<String> wanted = kinds == null || kinds.length == 0
                ? Set.of(Memory.KIND_TASK, Memory.KIND_EXPERIENCE)
                : Set.of(kinds);
        Map<Long, Double> scores = vectorStore.scores(TABLE, userId, query);
        if (scores.isEmpty()) {
            return List.of();
        }
        List<Memory> ranked = new ArrayList<>();
        for (Memory memory : listActive(userId)) {
            if (memory == null || memory.getId() == null || !wanted.contains(memory.getKind())) {
                continue;
            }
            Double score = scores.get(memory.getId());
            if (score == null || score < floor) {
                continue;
            }
            ranked.add(memory);
        }
        ranked.sort(Comparator
                .comparingDouble((Memory memory) -> scores.getOrDefault(memory.getId(), 0d)).reversed()
                .thenComparing(Memory::getImportance, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Memory::getPriority, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Memory::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return ranked;
    }

    public long countMissingEmbedding(String userId) {
        return vectorStore == null ? 0L : vectorStore.countMissing(TABLE, userId);
    }

    public Set<Long> embeddedIds(String userId) {
        return vectorStore == null ? Set.of() : vectorStore.idsWithEmbedding(TABLE, userId);
    }

    /** 给一条记忆算向量并写回（失败只记日志，记忆本身已经落库） */
    private void indexVector(Memory memory) {
        if (memory == null || memory.getId() == null || embeddingClient == null || vectorStore == null
                || !embeddingClient.isEnabled()) {
            return;
        }
        float[] vector = embeddingClient.embedOne(vectorText(memory));
        if (vector != null) {
            vectorStore.saveEmbedding(TABLE, memory.getId(), vector, embeddingClient.model());
        }
    }

    /** 经历类把标题也算进向量（标题往往是"申请表提醒失误"这种最能代表它的短语） */
    private String vectorText(Memory memory) {
        if (memory.isExperience() && memory.getTitle() != null && !memory.getTitle().isBlank()) {
            return memory.getTitle() + " " + memory.getContent();
        }
        return memory.getContent();
    }

    /** 给"还没有向量"的记忆补向量（一次最多 {@code max} 条） */
    public int reindexMissing(String userId, int max) {
        if (!validUserId(userId) || max <= 0 || embeddingClient == null || vectorStore == null
                || !embeddingClient.isEnabled()) {
            return 0;
        }
        try {
            Set<Long> embedded = vectorStore.idsWithEmbedding(TABLE, userId);
            List<Memory> pending = new ArrayList<>();
            for (Memory memory : list(userId)) {
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
            List<float[]> vectors = embeddingClient.embedAll(pending.stream().map(this::vectorText).toList());
            if (vectors == null || vectors.size() != pending.size()) {
                return 0;
            }
            for (int index = 0; index < pending.size(); index++) {
                vectorStore.saveEmbedding(TABLE, pending.get(index).getId(), vectors.get(index),
                        embeddingClient.model());
            }
            return pending.size();
        } catch (Exception e) {
            log.warn("补齐记忆向量失败 user={}: {}", userId, e.getMessage());
            return 0;
        }
    }

    /**
     * 启动时把存量记忆的向量补齐一次。缺向量的 TASK/EXPERIENCE 行**不会被注入**，所以日志里要看得到补齐结果。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillVectorsOnStartup() {
        if (embeddingClient == null || vectorStore == null || !embeddingClient.isEnabled()) {
            return;
        }
        int total = 0;
        for (String userId : repository.findDistinctUserIds()) {
            for (int pass = 0; pass < 20; pass++) {
                int done = reindexMissing(userId, 500);
                total += done;
                if (done < 500) {
                    break;
                }
            }
        }
        if (total > 0) {
            log.info("记忆向量补齐完成：{} 条", total);
        }
    }

    // ================= 内部工具 =================

    private Memory ownedMemory(String userId, Long id) {
        Memory memory = id == null ? null : repository.findById(id)
                .orElseThrow(() -> new BizException("记忆不存在"));
        if (memory == null) {
            throw new BizException("记忆不存在");
        }
        if (!userId.equals(memory.getUserId())) {
            throw new BizException("无权操作其他用户的记忆");
        }
        return memory;
    }

    private Memory repo(Memory saved, Memory fallback) {
        return saved == null ? fallback : saved;
    }

    private String normalizeContent(String content, int maxChars, String label) {
        if (content == null || content.isBlank()) {
            throw new BizException(label + "内容不能为空");
        }
        String normalized = content.trim();
        if (normalized.length() > maxChars) {
            throw new BizException(label + "内容太长");
        }
        return normalized;
    }

    private int normalizedPriority(Integer priority) {
        return Math.max(1, Math.min(5, priority == null ? defaultPriority : priority));
    }

    private void applyLifecycle(Memory memory, MemoryProvenance provenance, LocalDateTime validUntil,
                                boolean mergeExisting) {
        applyProvenance(memory, provenance, mergeExisting);
        if (memory.getValidFrom() == null) {
            memory.setValidFrom(LocalDateTime.now());
        }
        if (validUntil != null) {
            memory.setValidUntil(validUntil);
        }
    }

    private void applyProvenance(Memory memory, MemoryProvenance provenance, boolean mergeExisting) {
        MemoryProvenance normalized = provenance == null
                ? MemoryProvenance.automatic(memory.getSource() == null ? "extraction" : memory.getSource())
                : provenance;
        if (mergeExisting) {
            normalized = storedProvenance(memory).merge(normalized);
        }
        memory.setSourceType(normalized.sourceType());
        memory.setConfidence(normalized.confidence());
        memory.setSourceMessageIds(normalized.messageIdsColumn());
        memory.setSourceMediaIds(normalized.mediaIdsColumn());
    }

    private MemoryProvenance storedProvenance(Memory memory) {
        return MemoryProvenance.fromStored(memory.getSourceType(), memory.getConfidence(),
                memory.getSourceMessageIds(), memory.getSourceMediaIds());
    }

    private void applyAttributes(Memory memory, MemoryAttributes attributes) {
        MemoryAttributes normalized = attributes == null ? taskDefaults() : attributes;
        memory.setImportance(normalized.importance());
        int existingConfidence = memory.getConfidence() == null ? 0 : memory.getConfidence();
        memory.setConfidence(Math.max(existingConfidence, normalized.confidence()));
        memory.setKeywords(normalized.keywordsColumn());
        memory.setLastDecisionAt(LocalDateTime.now());
    }

    private void mergeProvenance(Memory memory, MemoryProvenance newer) {
        if (newer == null) {
            return;
        }
        MemoryProvenance merged = storedProvenance(memory).merge(newer);
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
        return value == null ? null : (value.length() <= maximum ? value : value.substring(0, maximum));
    }

    private String normalizeForComparison(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\s]+", "");
    }

    private void requireUserId(String userId) {
        if (!validUserId(userId)) {
            throw new BizException("用户标识不能为空");
        }
    }

    private boolean validUserId(String userId) {
        return userId != null && !userId.isBlank();
    }

    private MemoryAttributes profileDefaults() {
        return new MemoryAttributes(5, defaultConfidence, List.of());
    }

    private MemoryAttributes taskDefaults() {
        return new MemoryAttributes(3, defaultConfidence, List.of());
    }

    private int bounded(int value, int minimum, int maximum, int fallback) {
        return value < minimum || value > maximum ? fallback : value;
    }

    /** 直接夹到区间内（原 EpisodicMemoryService 的 bounded 语义：importance 1~5、confidence 0~100） */
    private int bounded(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    /** 面板与补齐全量遍历用：所有活跃记忆的最大扫描条数 */
    public int activeScanLimit() {
        return MAX_ACTIVE_SCAN;
    }

    List<Memory> ownedOnly(List<Memory> memories, String userId) {
        return memories == null ? List.of() : memories.stream()
                .filter(Objects::nonNull)
                .filter(memory -> userId != null && userId.equals(memory.getUserId()))
                .toList();
    }
}
