package com.liche.wechatagent.memory;

import com.liche.wechatagent.config.MemoryPolicyProperties;
import com.liche.wechatagent.exception.BizException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 第二层中期工作记忆：用户主动遗忘会永久删除并脱敏历史正文，其他情况一直保留。 */
@Service
public class WorkMemoryService {

    private final UserWorkMemoryRepository workRepository;
    private final MemoryChangeLogRepository changeLogRepository;
    private final MemoryContentSimilarity similarity;
    private final int maxContentChars;
    private final int defaultConfidence;
    private final int defaultPriority;

    @Autowired
    public WorkMemoryService(UserWorkMemoryRepository workRepository,
                             MemoryChangeLogRepository changeLogRepository,
                             MemoryContentSimilarity similarity,
                             MemoryPolicyProperties policyProperties) {
        this.workRepository = workRepository;
        this.changeLogRepository = changeLogRepository;
        this.similarity = similarity;
        MemoryPolicyProperties policies = policyProperties == null ? new MemoryPolicyProperties() : policyProperties;
        this.maxContentChars = bounded(policies.getWorkMaxContentChars(), 128,
                MemoryPolicyProperties.WORK_CONTENT_COLUMN_MAX_CHARS,
                MemoryPolicyProperties.DEFAULT_WORK_MAX_CONTENT_CHARS);
        this.defaultConfidence = bounded(policies.getExtractionDefaultConfidence(), 0, 100,
                MemoryPolicyProperties.DEFAULT_EXTRACTION_CONFIDENCE);
        this.defaultPriority = bounded(policies.getDefaultWorkPriority(), 1, 5,
                MemoryPolicyProperties.DEFAULT_WORK_PRIORITY);
    }

    public WorkMemoryService(UserWorkMemoryRepository workRepository,
                             MemoryChangeLogRepository changeLogRepository,
                             MemoryContentSimilarity similarity) {
        this(workRepository, changeLogRepository, similarity, new MemoryPolicyProperties());
    }

    WorkMemoryService(UserWorkMemoryRepository workRepository, MemoryChangeLogRepository changeLogRepository) {
        this(workRepository, changeLogRepository, new MemoryContentSimilarity(0.8d), new MemoryPolicyProperties());
    }

    @Transactional
    public UserWorkMemory add(String userId, String content, Integer priority, String source, String operator) {
        return add(userId, content, priority, source, operator, MemoryProvenance.automatic(source), null,
                workDefaults());
    }

    @Transactional
    public UserWorkMemory add(String userId, String content, Integer priority, String source, String operator,
                              MemoryProvenance provenance, LocalDateTime validUntil) {
        return add(userId, content, priority, source, operator, provenance, validUntil,
                workDefaults());
    }

    @Transactional
    public UserWorkMemory add(String userId, String content, Integer priority, String source, String operator,
                              MemoryProvenance provenance, LocalDateTime validUntil,
                              MemoryAttributes attributes) {
        requireUserId(userId);
        String normalized = normalizeContent(content);
        LocalDateTime now = LocalDateTime.now();
        MemoryAttributes normalizedAttributes = attributes == null ? workDefaults() : attributes;
        List<UserWorkMemory> existingRecords = workRepository.findByUserId(userId);
        UserWorkMemory existing = (existingRecords == null ? List.<UserWorkMemory>of() : existingRecords).stream()
                .filter(Objects::nonNull)
                .filter(memory -> userId.equals(memory.getUserId()))
                .filter(memory -> isActive(memory, now))
                .filter(memory -> similarity.isDuplicate(normalized, memory.getContent()))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            existing.setPriority(Math.max(existing.getPriority() == null ? defaultPriority : existing.getPriority(), normalizedPriority(priority)));
            existing.setLastConfirmedAt(now);
            existing.setUpdatedAt(now);
            if (validUntil != null && (existing.getValidUntil() == null || existing.getValidUntil().isBefore(validUntil))) {
                existing.setValidUntil(validUntil);
            }
            applyProvenance(existing, provenance, true);
            applyAttributes(existing, MemoryAttributes.fromStored(existing.getImportance(), existing.getConfidence(),
                    existing.getKeywords()).merge(normalizedAttributes));
            workRepository.save(existing);
            changeLogRepository.save(new MemoryChangeLog(userId, "CONFIRM", "WORK", existing.getId(),
                    existing.getContent(), existing.getContent(), "用户再次明确确认已有工作记忆", operator));
            return existing;
        }
        UserWorkMemory mem = workRepository.save(new UserWorkMemory(userId, normalized, normalizedPriority(priority), source));
        applyLifecycle(mem, provenance, validUntil, false);
        applyAttributes(mem, normalizedAttributes);
        workRepository.save(mem);
        changeLogRepository.save(new MemoryChangeLog(userId, "ADD", "WORK", mem.getId(), null, normalized,
                "自动记忆提取", operator));
        return mem;
    }

    private String normalizeContent(String content) {
        if (content == null || content.isBlank()) {
            throw new BizException("记忆内容不能为空");
        }
        String normalized = content.trim();
        if (normalized.length() > maxContentChars) {
            throw new BizException("记忆内容太长");
        }
        return normalized;
    }

    private int normalizedPriority(Integer priority) {
        return Math.max(1, Math.min(5, priority == null ? defaultPriority : priority));
    }

    public List<UserWorkMemory> listActive(String userId) {
        if (!validUserId(userId)) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now();
        List<UserWorkMemory> records = workRepository.findByUserId(userId);
        if (records == null) {
            return List.of();
        }
        return records.stream().filter(memory -> memory != null && userId != null
                        && userId.equals(memory.getUserId()))
                .filter(memory -> isActive(memory, now))
                .toList();
    }

    public List<UserWorkMemory> listInactive(String userId) {
        if (!validUserId(userId)) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now();
        List<UserWorkMemory> records = workRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        if (records == null) {
            return List.of();
        }
        return records.stream().filter(memory -> memory != null && userId != null
                        && userId.equals(memory.getUserId()))
                .filter(memory -> !isActive(memory, now))
                .toList();
    }

    public long countActive(String userId) {
        return listActive(userId).size();
    }

    @Transactional
    public void updateFromExtraction(String userId, Long workId, String newContent) {
        updateFromExtraction(userId, workId, newContent, MemoryProvenance.automatic("extraction"), null,
                workDefaults());
    }

    @Transactional
    public void updateFromExtraction(String userId, Long workId, String newContent,
                                     MemoryProvenance provenance, LocalDateTime validUntil) {
        updateFromExtraction(userId, workId, newContent, provenance, validUntil, workDefaults());
    }

    @Transactional
    public void updateFromExtraction(String userId, Long workId, String newContent,
                                     MemoryProvenance provenance, LocalDateTime validUntil,
                                     MemoryAttributes attributes) {
        replaceFromExtraction(userId, workId, newContent, provenance, validUntil, attributes);
    }

    /** Replaces a changed work fact while retaining the prior version as SUPERSEDED. */
    @Transactional
    public UserWorkMemory replaceFromExtraction(String userId, Long workId, String newContent,
                                                MemoryProvenance provenance, LocalDateTime validUntil,
                                                MemoryAttributes attributes) {
        requireUserId(userId);
        if (workId == null) {
            throw new BizException("工作记忆编号不能为空");
        }
        UserWorkMemory mem = workRepository.findById(workId)
                .orElseThrow(() -> new BizException("中期记忆不存在"));
        if (!userId.equals(mem.getUserId())) {
            throw new BizException("无权操作其他用户的记忆");
        }
        String normalized = normalizeContent(newContent);
        LocalDateTime now = LocalDateTime.now();
        if (normalized.equals(mem.getContent())) {
            mem.setUpdatedAt(now);
            mem.setLastConfirmedAt(now);
            applyLifecycle(mem, provenance, validUntil, true);
            applyAttributes(mem, attributes == null ? workDefaults() : attributes);
            workRepository.save(mem);
            changeLogRepository.save(new MemoryChangeLog(userId, "CONFIRM", "WORK", workId,
                    mem.getContent(), mem.getContent(), "重复事实再次确认", "AUTO"));
            return mem;
        }
        UserWorkMemory replacement = new UserWorkMemory(userId, normalized,
                normalizedPriority(mem.getPriority()), mem.getSource());
        MemoryProvenance mergedProvenance = storedProvenance(mem).merge(
                provenance == null ? MemoryProvenance.automatic("extraction") : provenance);
        applyLifecycle(replacement, mergedProvenance, validUntil, false);
        applyAttributes(replacement, attributes == null ? workDefaults() : attributes);
        replacement.setValidFrom(mem.getValidFrom() == null ? now : mem.getValidFrom());
        replacement.setLastConfirmedAt(now);
        replacement.setUpdatedAt(now);
        UserWorkMemory savedReplacement = workRepository.save(replacement);
        if (savedReplacement == null) {
            savedReplacement = replacement;
        }
        mem.setStatus(MemoryStatus.SUPERSEDED.name());
        mem.setSupersededById(savedReplacement.getId());
        mem.setUpdatedAt(now);
        mem.setLastDecisionAt(now);
        workRepository.save(mem);
        changeLogRepository.save(new MemoryChangeLog(userId, "SUPERSEDE", "WORK", workId,
                mem.getContent(), normalized, "用户明确陈述的新事实替代旧事实", "AUTO"));
        changeLogRepository.save(new MemoryChangeLog(userId, "ADD", "WORK", savedReplacement.getId(),
                null, normalized, "替代旧工作记忆", "AUTO"));
        return savedReplacement;
    }

    @Transactional
    public ForgottenMemory forget(String userId, Long workId) {
        requireUserId(userId);
        if (workId == null) {
            throw new BizException("工作记忆编号不能为空");
        }
        UserWorkMemory mem = workRepository.findById(workId)
                .orElseThrow(() -> new BizException("工作记忆不存在"));
        if (!userId.equals(mem.getUserId())) {
            throw new BizException("无权操作其他用户的记忆");
        }
        ForgottenMemory forgotten = new ForgottenMemory("WORK", mem.getId(), mem.getContent(), mem.getSource(),
                MemoryProvenance.fromStored(mem.getSourceType(), mem.getConfidence(),
                        mem.getSourceMessageIds(), mem.getSourceMediaIds()).sourceMessageIds());
        workRepository.delete(mem);
        changeLogRepository.redactContentForMemory(userId, "WORK", workId);
        changeLogRepository.save(new MemoryChangeLog(userId, "FORGET", "WORK", workId, null, null,
                "用户主动遗忘；审计正文已清除", "USER"));
        return forgotten;
    }

    @Transactional
    public List<ForgottenMemory> forgetSupersededHistory(String userId, Long memoryId) {
        List<ForgottenMemory> forgotten = new ArrayList<>();
        if (memoryId == null) {
            return forgotten;
        }
        List<UserWorkMemory> source = workRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        List<UserWorkMemory> all = source == null ? List.of() : source.stream()
                .filter(memory -> memory != null && userId != null && userId.equals(memory.getUserId()))
                .toList();
        if (all.isEmpty()) {
            return forgotten;
        }
        Set<Long> related = new LinkedHashSet<>();
        related.add(memoryId);
        boolean changed;
        do {
            changed = false;
            for (UserWorkMemory memory : all) {
                if (memory.getId() == null || related.contains(memory.getId())) {
                    if (memory.getId() != null && related.contains(memory.getId())
                            && memory.getSupersededById() != null
                            && related.add(memory.getSupersededById())) {
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
            UserWorkMemory memory = all.stream().filter(item -> id.equals(item.getId())).findFirst().orElse(null);
            if (memory != null && userId.equals(memory.getUserId())) {
                forgotten.add(forget(userId, id));
            }
        }
        return forgotten;
    }

    @Transactional
    public void markCompleted(String userId, Long workId, String reason, MemoryProvenance provenance) {
        requireUserId(userId);
        if (workId == null) {
            throw new BizException("工作记忆编号不能为空");
        }
        UserWorkMemory mem = workRepository.findById(workId)
                .orElseThrow(() -> new BizException("中期记忆不存在"));
        if (!userId.equals(mem.getUserId())) {
            throw new BizException("无权操作其他用户的记忆");
        }
        if (!isActive(mem, LocalDateTime.now())) {
            return;
        }
        mem.setStatus(MemoryStatus.COMPLETED.name());
        mem.setUpdatedAt(LocalDateTime.now());
        mem.setLastConfirmedAt(LocalDateTime.now());
        applyProvenance(mem, provenance, true);
        mem.setLastDecisionAt(LocalDateTime.now());
        workRepository.save(mem);
        changeLogRepository.save(new MemoryChangeLog(userId, "COMPLETE", "WORK", workId, mem.getContent(), mem.getContent(),
                reason == null || reason.isBlank() ? "用户明确表示该事项已完成" : reason, "AUTO"));
    }

    @Transactional
    public int expireDueMemories() {
        LocalDateTime now = LocalDateTime.now();
        int changed = 0;
        List<UserWorkMemory> due = workRepository.findByValidUntilBefore(now);
        if (due == null) {
            return 0;
        }
        for (UserWorkMemory memory : due) {
            if (memory == null || !isStoredActive(memory)
                    || memory.getValidUntil() == null || memory.getValidUntil().isAfter(now)) {
                continue;
            }
            memory.setStatus(MemoryStatus.EXPIRED.name());
            memory.setUpdatedAt(now);
            workRepository.save(memory);
            changeLogRepository.save(new MemoryChangeLog(memory.getUserId(), "EXPIRE", "WORK", memory.getId(),
                    memory.getContent(), memory.getContent(), "记忆有效期已过", "SYSTEM"));
            changed++;
        }
        return changed;
    }

    public static boolean isActive(UserWorkMemory memory, LocalDateTime now) {
        if (memory == null || !isStoredActive(memory)) {
            return false;
        }
        LocalDateTime effectiveNow = now == null ? LocalDateTime.now() : now;
        return memory.getValidUntil() == null || memory.getValidUntil().isAfter(effectiveNow);
    }

    private static boolean isStoredActive(UserWorkMemory memory) {
        return memory.getStatus() == null || memory.getStatus().isBlank()
                || MemoryStatus.ACTIVE.name().equals(memory.getStatus());
    }

    private void applyLifecycle(UserWorkMemory memory, MemoryProvenance provenance, LocalDateTime validUntil,
                                boolean mergeExisting) {
        applyProvenance(memory, provenance, mergeExisting);
        if (memory.getValidFrom() == null) {
            memory.setValidFrom(LocalDateTime.now());
        }
        if (validUntil != null) {
            memory.setValidUntil(validUntil);
        }
    }

    private void applyProvenance(UserWorkMemory memory, MemoryProvenance provenance, boolean mergeExisting) {
        MemoryProvenance normalized = provenance == null ? MemoryProvenance.automatic(memory.getSource()) : provenance;
        if (mergeExisting) {
            normalized = storedProvenance(memory).merge(normalized);
        }
        memory.setSourceType(normalized.sourceType());
        memory.setConfidence(normalized.confidence());
        memory.setSourceMessageIds(normalized.messageIdsColumn());
        memory.setSourceMediaIds(normalized.mediaIdsColumn());
    }

    private void applyAttributes(UserWorkMemory memory, MemoryAttributes attributes) {
        MemoryAttributes normalized = attributes == null ? workDefaults() : attributes;
        memory.setImportance(normalized.importance());
        int existingConfidence = memory.getConfidence() == null ? 0 : memory.getConfidence();
        memory.setConfidence(Math.max(existingConfidence, normalized.confidence()));
        memory.setKeywords(normalized.keywordsColumn());
        memory.setLastDecisionAt(LocalDateTime.now());
    }

    private MemoryProvenance storedProvenance(UserWorkMemory memory) {
        return MemoryProvenance.fromStored(memory.getSourceType(), memory.getConfidence(),
                memory.getSourceMessageIds(), memory.getSourceMediaIds());
    }

    private void requireUserId(String userId) {
        if (!validUserId(userId)) {
            throw new BizException("用户标识不能为空");
        }
    }

    private boolean validUserId(String userId) {
        return userId != null && !userId.isBlank();
    }

    private MemoryAttributes workDefaults() {
        return new MemoryAttributes(3, defaultConfidence, List.of());
    }

    private int bounded(int value, int minimum, int maximum, int fallback) {
        return value < minimum || value > maximum ? fallback : value;
    }
}
