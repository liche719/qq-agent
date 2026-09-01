package com.liche.wechatagent.memory;

import com.liche.wechatagent.config.MemoryPolicyProperties;
import com.liche.wechatagent.exception.BizException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 第三层核心记忆服务：稳定身份、长期目标和原则由提取器自动维护，用户遗忘会脱敏历史正文。 */
@Service
public class CoreMemoryService {

    private final UserCoreMemoryRepository coreRepository;
    private final MemoryChangeLogRepository changeLogRepository;
    private final MemoryContentSimilarity similarity;
    private final int maxContentChars;
    private final int defaultConfidence;

    @Autowired
    public CoreMemoryService(UserCoreMemoryRepository coreRepository,
                             MemoryChangeLogRepository changeLogRepository,
                             MemoryContentSimilarity similarity,
                             MemoryPolicyProperties policyProperties) {
        this.coreRepository = coreRepository;
        this.changeLogRepository = changeLogRepository;
        this.similarity = similarity;
        MemoryPolicyProperties policies = policyProperties == null ? new MemoryPolicyProperties() : policyProperties;
        this.maxContentChars = bounded(policies.getCoreMaxContentChars(), 128,
                MemoryPolicyProperties.CORE_CONTENT_COLUMN_MAX_CHARS,
                MemoryPolicyProperties.DEFAULT_CORE_MAX_CONTENT_CHARS);
        this.defaultConfidence = bounded(policies.getExtractionDefaultConfidence(), 0, 100,
                MemoryPolicyProperties.DEFAULT_EXTRACTION_CONFIDENCE);
    }

    public CoreMemoryService(UserCoreMemoryRepository coreRepository,
                             MemoryChangeLogRepository changeLogRepository,
                             MemoryContentSimilarity similarity) {
        this(coreRepository, changeLogRepository, similarity, new MemoryPolicyProperties());
    }

    public List<UserCoreMemory> list(String userId) {
        if (!validUserId(userId)) {
            return List.of();
        }
        List<UserCoreMemory> records = coreRepository.findByUserIdOrderByCreatedAtAsc(userId);
        if (records == null) {
            return List.of();
        }
        return records.stream().filter(memory -> memory != null && userId != null
                && userId.equals(memory.getUserId())).toList();
    }

    public List<UserCoreMemory> listActive(String userId) {
        return list(userId).stream().filter(CoreMemoryService::isActive)
                .filter(CoreMemoryService::isExplicit).toList();
    }

    @Transactional
    public UserCoreMemory add(String userId, String content, String operator) {
        return add(userId, content, operator, MemoryProvenance.automatic("extraction"));
    }

    @Transactional
    public UserCoreMemory add(String userId, String content, String operator, MemoryProvenance provenance) {
        return add(userId, content, operator, provenance, coreDefaults());
    }

    @Transactional
    public UserCoreMemory add(String userId, String content, String operator, MemoryProvenance provenance,
                              MemoryAttributes attributes) {
        requireUserId(userId);
        String normalized = normalizeContent(content);
        MemoryAttributes normalizedAttributes = attributes == null ? coreDefaults() : attributes;
        List<UserCoreMemory> existingRecords = coreRepository.findByUserIdOrderByCreatedAtAsc(userId);
        UserCoreMemory existing = (existingRecords == null ? List.<UserCoreMemory>of() : existingRecords).stream()
                .filter(Objects::nonNull)
                .filter(memory -> userId.equals(memory.getUserId()))
                .filter(CoreMemoryService::isActive)
                .filter(memory -> similarity.isDuplicate(normalized, memory.getContent()))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            applyProvenance(existing, provenance, true);
            applyAttributes(existing, MemoryAttributes.fromStored(existing.getImportance(), existing.getConfidence(),
                    existing.getKeywords()).merge(normalizedAttributes));
            existing.setLastConfirmedAt(LocalDateTime.now());
            existing.setUpdatedAt(LocalDateTime.now());
            coreRepository.save(existing);
            changeLogRepository.save(new MemoryChangeLog(userId, "CONFIRM", "CORE", existing.getId(), existing.getContent(),
                    existing.getContent(), "用户再次明确确认已有长期记忆", operator));
            return existing;
        }
        UserCoreMemory mem = coreRepository.save(new UserCoreMemory(userId, normalized));
        applyProvenance(mem, provenance, false);
        applyAttributes(mem, normalizedAttributes);
        mem.setLastConfirmedAt(LocalDateTime.now());
        coreRepository.save(mem);
        changeLogRepository.save(new MemoryChangeLog(userId, "ADD", "CORE", mem.getId(), null, normalized,
                "自动提取长期稳定记忆", operator));
        return mem;
    }

    @Transactional
    public void update(String userId, Long coreId, String newContent, String reason, String operator) {
        update(userId, coreId, newContent, reason, operator, MemoryProvenance.automatic("extraction"));
    }

    @Transactional
    public void update(String userId, Long coreId, String newContent, String reason, String operator,
                       MemoryProvenance provenance) {
        update(userId, coreId, newContent, reason, operator, provenance, coreDefaults());
    }

    @Transactional
    public void update(String userId, Long coreId, String newContent, String reason, String operator,
                       MemoryProvenance provenance, MemoryAttributes attributes) {
        replaceFromExtraction(userId, coreId, newContent, reason, operator, provenance, attributes);
    }

    /** Replaces a fact while retaining its previous version for audit and historical retrieval. */
    @Transactional
    public UserCoreMemory replaceFromExtraction(String userId, Long coreId, String newContent, String reason,
                                                String operator, MemoryProvenance provenance,
                                                MemoryAttributes attributes) {
        requireUserId(userId);
        if (coreId == null) {
            throw new BizException("核心记忆编号不能为空");
        }
        UserCoreMemory mem = coreRepository.findById(coreId)
                .orElseThrow(() -> new BizException("核心记忆不存在"));
        if (!userId.equals(mem.getUserId())) {
            throw new BizException("无权操作其他用户的核心记忆");
        }
        String normalized = normalizeContent(newContent);
        LocalDateTime now = LocalDateTime.now();
        if (normalized.equals(mem.getContent())) {
            applyProvenance(mem, provenance, true);
            applyAttributes(mem, attributes == null ? coreDefaults() : attributes);
            mem.setLastConfirmedAt(now);
            mem.setUpdatedAt(now);
            coreRepository.save(mem);
            changeLogRepository.save(new MemoryChangeLog(userId, "CONFIRM", "CORE", coreId,
                    mem.getContent(), mem.getContent(), "重复事实再次确认", operator));
            return mem;
        }
        UserCoreMemory replacement = new UserCoreMemory(userId, normalized);
        MemoryProvenance mergedProvenance = storedProvenance(mem).merge(
                provenance == null ? MemoryProvenance.automatic("extraction") : provenance);
        applyProvenance(replacement, mergedProvenance, false);
        applyAttributes(replacement, attributes == null ? coreDefaults() : attributes);
        replacement.setLastConfirmedAt(now);
        replacement.setUpdatedAt(now);
        UserCoreMemory savedReplacement = coreRepository.save(replacement);
        if (savedReplacement == null) {
            savedReplacement = replacement;
        }
        mem.setStatus(MemoryStatus.SUPERSEDED.name());
        mem.setSupersededById(savedReplacement.getId());
        mem.setUpdatedAt(now);
        mem.setLastDecisionAt(now);
        coreRepository.save(mem);
        changeLogRepository.save(new MemoryChangeLog(userId, "SUPERSEDE", "CORE", coreId,
                mem.getContent(), normalized, reason, operator));
        changeLogRepository.save(new MemoryChangeLog(userId, "ADD", "CORE", savedReplacement.getId(),
                null, normalized, "替代旧核心记忆", operator));
        return savedReplacement;
    }

    @Transactional
    public ForgottenMemory delete(String userId, Long coreId) {
        requireUserId(userId);
        if (coreId == null) {
            throw new BizException("核心记忆编号不能为空");
        }
        UserCoreMemory mem = coreRepository.findById(coreId)
                .orElseThrow(() -> new BizException("核心记忆不存在"));
        if (!userId.equals(mem.getUserId())) {
            throw new BizException("无权操作其他用户的记忆");
        }
        ForgottenMemory forgotten = new ForgottenMemory("CORE", mem.getId(), mem.getContent(), "",
                MemoryProvenance.fromStored(mem.getSourceType(), mem.getConfidence(),
                        mem.getSourceMessageIds(), mem.getSourceMediaIds()).sourceMessageIds());
        coreRepository.delete(mem);
        changeLogRepository.redactContentForMemory(userId, "CORE", coreId);
        changeLogRepository.save(new MemoryChangeLog(userId, "FORGET", "CORE", coreId, null, null,
                "用户主动遗忘；审计正文已清除", "USER"));
        return forgotten;
    }

    @Transactional
    public List<ForgottenMemory> forgetSupersededHistory(String userId, Long memoryId) {
        List<ForgottenMemory> forgotten = new ArrayList<>();
        if (memoryId == null) {
            return forgotten;
        }
        List<UserCoreMemory> all = list(userId);
        if (all == null || all.isEmpty()) {
            return forgotten;
        }
        java.util.Set<Long> related = new java.util.LinkedHashSet<>();
        related.add(memoryId);
        boolean changed;
        do {
            changed = false;
            for (UserCoreMemory memory : all) {
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
            UserCoreMemory memory = all.stream().filter(item -> id.equals(item.getId())).findFirst().orElse(null);
            if (memory != null && userId.equals(memory.getUserId())) {
                forgotten.add(delete(userId, id));
            }
        }
        return forgotten;
    }

    private String normalizeContent(String content) {
        if (content == null || content.isBlank()) {
            throw new BizException("核心记忆内容不能为空");
        }
        String normalized = content.trim();
        if (normalized.length() > maxContentChars) {
            throw new BizException("核心记忆内容太长");
        }
        return normalized;
    }

    public static boolean isActive(UserCoreMemory memory) {
        return memory != null && (memory.getStatus() == null || memory.getStatus().isBlank()
                || MemoryStatus.ACTIVE.name().equals(memory.getStatus()));
    }

    public static boolean isExplicit(UserCoreMemory memory) {
        return memory != null && (memory.getSourceType() == null || memory.getSourceType().isBlank()
                || "USER_EXPLICIT".equalsIgnoreCase(memory.getSourceType()));
    }

    private void applyProvenance(UserCoreMemory memory, MemoryProvenance provenance, boolean mergeExisting) {
        MemoryProvenance normalized = provenance == null ? MemoryProvenance.automatic("extraction") : provenance;
        if (mergeExisting) {
            normalized = storedProvenance(memory).merge(normalized);
        }
        memory.setSourceType(normalized.sourceType());
        memory.setConfidence(normalized.confidence());
        memory.setSourceMessageIds(normalized.messageIdsColumn());
        memory.setSourceMediaIds(normalized.mediaIdsColumn());
    }

    private MemoryProvenance storedProvenance(UserCoreMemory memory) {
        return MemoryProvenance.fromStored(memory.getSourceType(), memory.getConfidence(),
                memory.getSourceMessageIds(), memory.getSourceMediaIds());
    }

    private void applyAttributes(UserCoreMemory memory, MemoryAttributes attributes) {
        MemoryAttributes normalized = attributes == null ? coreDefaults() : attributes;
        memory.setImportance(normalized.importance());
        int existingConfidence = memory.getConfidence() == null ? 0 : memory.getConfidence();
        memory.setConfidence(Math.max(existingConfidence, normalized.confidence()));
        memory.setKeywords(normalized.keywordsColumn());
        memory.setLastDecisionAt(LocalDateTime.now());
    }

    private void requireUserId(String userId) {
        if (!validUserId(userId)) {
            throw new BizException("用户标识不能为空");
        }
    }

    private boolean validUserId(String userId) {
        return userId != null && !userId.isBlank();
    }

    private MemoryAttributes coreDefaults() {
        return new MemoryAttributes(5, defaultConfidence, List.of());
    }

    private int bounded(int value, int minimum, int maximum, int fallback) {
        return value < minimum || value > maximum ? fallback : value;
    }
}
