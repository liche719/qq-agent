package com.liche.wechatagent.memory;

import com.liche.wechatagent.exception.BizException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 第二层中期工作记忆：系统归档只标记，用户主动遗忘会永久删除并脱敏历史正文。 */
@Service
public class WorkMemoryService {

    private final UserWorkMemoryRepository workRepository;
    private final MemoryChangeLogRepository changeLogRepository;
    private final MemoryContentSimilarity similarity;

    @Autowired
    public WorkMemoryService(UserWorkMemoryRepository workRepository,
                             MemoryChangeLogRepository changeLogRepository,
                             MemoryContentSimilarity similarity) {
        this.workRepository = workRepository;
        this.changeLogRepository = changeLogRepository;
        this.similarity = similarity;
    }

    WorkMemoryService(UserWorkMemoryRepository workRepository, MemoryChangeLogRepository changeLogRepository) {
        this(workRepository, changeLogRepository, new MemoryContentSimilarity(0.8d));
    }

    @Transactional
    public UserWorkMemory add(String userId, String content, Integer priority, String source, String operator) {
        return add(userId, content, priority, source, operator, MemoryProvenance.automatic(source), null);
    }

    @Transactional
    public UserWorkMemory add(String userId, String content, Integer priority, String source, String operator,
                              MemoryProvenance provenance, LocalDateTime validUntil) {
        String normalized = normalizeContent(content);
        LocalDateTime now = LocalDateTime.now();
        UserWorkMemory existing = workRepository.findByUserIdAndArchivedFalse(userId).stream()
                .filter(memory -> isActive(memory, now))
                .filter(memory -> similarity.isDuplicate(normalized, memory.getContent()))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            existing.setPriority(Math.max(existing.getPriority() == null ? 3 : existing.getPriority(), normalizedPriority(priority)));
            existing.setLastConfirmedAt(now);
            existing.setUpdatedAt(now);
            if (validUntil != null && (existing.getValidUntil() == null || existing.getValidUntil().isBefore(validUntil))) {
                existing.setValidUntil(validUntil);
            }
            applyProvenance(existing, provenance, true);
            workRepository.save(existing);
            changeLogRepository.save(new MemoryChangeLog(userId, "CONFIRM", "WORK", existing.getId(),
                    existing.getContent(), existing.getContent(), "用户再次明确确认已有工作记忆", operator));
            return existing;
        }
        UserWorkMemory mem = workRepository.save(new UserWorkMemory(userId, normalized, normalizedPriority(priority), source));
        applyLifecycle(mem, provenance, validUntil, false);
        workRepository.save(mem);
        changeLogRepository.save(new MemoryChangeLog(userId, "ADD", "WORK", mem.getId(), null, normalized,
                "archive_summary".equals(source) ? "归档摘要回填" : "自动记忆提取", operator));
        return mem;
    }

    private String normalizeContent(String content) {
        if (content == null || content.isBlank()) {
            throw new BizException("记忆内容不能为空");
        }
        String normalized = content.trim();
        if (normalized.length() > 2000) {
            throw new BizException("记忆内容太长");
        }
        return normalized;
    }

    private int normalizedPriority(Integer priority) {
        return Math.max(1, Math.min(5, priority == null ? 3 : priority));
    }

    public List<UserWorkMemory> listActive(String userId) {
        LocalDateTime now = LocalDateTime.now();
        return workRepository.findByUserIdAndArchivedFalse(userId).stream()
                .filter(memory -> isActive(memory, now))
                .toList();
    }

    public List<UserWorkMemory> listInactive(String userId) {
        LocalDateTime now = LocalDateTime.now();
        return workRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .filter(memory -> !Boolean.TRUE.equals(memory.getArchived()))
                .filter(memory -> !isActive(memory, now))
                .toList();
    }

    public long countActive(String userId) {
        return listActive(userId).size();
    }

    @Transactional
    public void updateFromExtraction(String userId, Long workId, String newContent) {
        updateFromExtraction(userId, workId, newContent, MemoryProvenance.automatic("extraction"), null);
    }

    @Transactional
    public void updateFromExtraction(String userId, Long workId, String newContent,
                                     MemoryProvenance provenance, LocalDateTime validUntil) {
        UserWorkMemory mem = workRepository.findById(workId)
                .orElseThrow(() -> new BizException("中期记忆不存在"));
        if (!mem.getUserId().equals(userId)) {
            throw new BizException("无权操作其他用户的记忆");
        }
        String before = mem.getContent();
        mem.setContent(normalizeContent(newContent));
        mem.setUpdatedAt(LocalDateTime.now());
        mem.setLastConfirmedAt(LocalDateTime.now());
        mem.setStatus(MemoryStatus.ACTIVE.name());
        applyLifecycle(mem, provenance, validUntil, false);
        workRepository.save(mem);
        changeLogRepository.save(new MemoryChangeLog(userId, "UPDATE", "WORK", workId, before, mem.getContent(),
                "用户明确陈述的新事实替代旧事实", "AUTO"));
    }

    @Transactional
    public ForgottenMemory forget(String userId, Long workId) {
        UserWorkMemory mem = workRepository.findById(workId)
                .orElseThrow(() -> new BizException("工作记忆不存在"));
        if (!mem.getUserId().equals(userId)) {
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

    /** 归档：仅标记 is_archived=1，不删除原始记录 */
    @Transactional
    public void markArchived(String userId, List<Long> ids, Long archiveId) {
        for (Long id : ids) {
            workRepository.findById(id).ifPresent(mem -> {
                if (!mem.getUserId().equals(userId)) {
                    return;
                }
                mem.setArchived(true);
                mem.setUpdatedAt(LocalDateTime.now());
                workRepository.save(mem);
                changeLogRepository.save(new MemoryChangeLog(userId, "ARCHIVE", "WORK", id, mem.getContent(), null,
                        "归档到记录#" + archiveId, "SYSTEM"));
            });
        }
    }

    @Transactional
    public void markCompleted(String userId, Long workId, String reason, MemoryProvenance provenance) {
        UserWorkMemory mem = workRepository.findById(workId)
                .orElseThrow(() -> new BizException("中期记忆不存在"));
        if (!mem.getUserId().equals(userId)) {
            throw new BizException("无权操作其他用户的记忆");
        }
        if (!isActive(mem, LocalDateTime.now())) {
            return;
        }
        mem.setStatus(MemoryStatus.COMPLETED.name());
        mem.setUpdatedAt(LocalDateTime.now());
        mem.setLastConfirmedAt(LocalDateTime.now());
        applyProvenance(mem, provenance, true);
        workRepository.save(mem);
        changeLogRepository.save(new MemoryChangeLog(userId, "COMPLETE", "WORK", workId, mem.getContent(), mem.getContent(),
                reason == null || reason.isBlank() ? "用户明确表示该事项已完成" : reason, "AUTO"));
    }

    @Transactional
    public int expireDueMemories() {
        LocalDateTime now = LocalDateTime.now();
        int changed = 0;
        for (UserWorkMemory memory : workRepository.findByArchivedFalseAndValidUntilBefore(now)) {
            if (!isStoredActive(memory)) {
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
        if (memory == null || Boolean.TRUE.equals(memory.getArchived()) || !isStoredActive(memory)) {
            return false;
        }
        return memory.getValidUntil() == null || !memory.getValidUntil().isBefore(now);
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

    private MemoryProvenance storedProvenance(UserWorkMemory memory) {
        return MemoryProvenance.fromStored(memory.getSourceType(), memory.getConfidence(),
                memory.getSourceMessageIds(), memory.getSourceMediaIds());
    }
}
