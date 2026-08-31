package com.liche.wechatagent.memory;

import com.liche.wechatagent.exception.BizException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 第三层核心记忆服务：稳定身份、长期目标和原则由提取器自动维护，所有变更均留痕。 */
@Service
public class CoreMemoryService {

    private final UserCoreMemoryRepository coreRepository;
    private final MemoryChangeLogRepository changeLogRepository;

    public CoreMemoryService(UserCoreMemoryRepository coreRepository, MemoryChangeLogRepository changeLogRepository) {
        this.coreRepository = coreRepository;
        this.changeLogRepository = changeLogRepository;
    }

    public List<UserCoreMemory> list(String userId) {
        return coreRepository.findByUserIdOrderByCreatedAtAsc(userId);
    }

    public List<UserCoreMemory> listActive(String userId) {
        return list(userId).stream().filter(CoreMemoryService::isActive).toList();
    }

    @Transactional
    public UserCoreMemory add(String userId, String content, String operator) {
        return add(userId, content, operator, MemoryProvenance.automatic("extraction"));
    }

    @Transactional
    public UserCoreMemory add(String userId, String content, String operator, MemoryProvenance provenance) {
        String normalized = normalizeContent(content);
        UserCoreMemory existing = coreRepository.findByUserIdOrderByCreatedAtAsc(userId).stream()
                .filter(memory -> normalized.equalsIgnoreCase(memory.getContent().trim()))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            applyProvenance(existing, provenance);
            existing.setLastConfirmedAt(LocalDateTime.now());
            existing.setUpdatedAt(LocalDateTime.now());
            coreRepository.save(existing);
            changeLogRepository.save(new MemoryChangeLog(userId, "CONFIRM", "CORE", existing.getId(), existing.getContent(),
                    existing.getContent(), "用户再次明确确认已有长期记忆", operator));
            return existing;
        }
        UserCoreMemory mem = coreRepository.save(new UserCoreMemory(userId, normalized));
        applyProvenance(mem, provenance);
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
        UserCoreMemory mem = coreRepository.findById(coreId)
                .orElseThrow(() -> new BizException("核心记忆不存在"));
        if (!mem.getUserId().equals(userId)) {
            throw new BizException("无权操作其他用户的核心记忆");
        }
        String before = mem.getContent();
        mem.setContent(normalizeContent(newContent));
        mem.setUpdatedAt(LocalDateTime.now());
        mem.setLastConfirmedAt(LocalDateTime.now());
        mem.setStatus(MemoryStatus.ACTIVE.name());
        applyProvenance(mem, provenance);
        coreRepository.save(mem);
        changeLogRepository.save(new MemoryChangeLog(userId, "UPDATE", "CORE", coreId, before, mem.getContent(),
                reason, operator));
    }

    @Transactional
    public void delete(String userId, Long coreId) {
        UserCoreMemory mem = coreRepository.findById(coreId)
                .orElseThrow(() -> new BizException("核心记忆不存在"));
        if (!mem.getUserId().equals(userId)) {
            throw new BizException("无权操作其他用户的记忆");
        }
        String content = mem.getContent();
        coreRepository.delete(mem);
        changeLogRepository.save(new MemoryChangeLog(userId, "DELETE", "CORE", coreId, content, null,
                "用户主动删除", "USER"));
    }

    private String normalizeContent(String content) {
        if (content == null || content.isBlank()) {
            throw new BizException("核心记忆内容不能为空");
        }
        String normalized = content.trim();
        if (normalized.length() > 4000) {
            throw new BizException("核心记忆内容太长");
        }
        return normalized;
    }

    public static boolean isActive(UserCoreMemory memory) {
        return memory != null && (memory.getStatus() == null || memory.getStatus().isBlank()
                || MemoryStatus.ACTIVE.name().equals(memory.getStatus()));
    }

    private void applyProvenance(UserCoreMemory memory, MemoryProvenance provenance) {
        MemoryProvenance normalized = provenance == null ? MemoryProvenance.automatic("extraction") : provenance;
        memory.setSourceType(normalized.sourceType());
        memory.setConfidence(normalized.confidence());
        memory.setSourceMessageIds(normalized.messageIdsColumn());
        memory.setSourceMediaIds(normalized.mediaIdsColumn());
    }
}
