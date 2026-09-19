package com.liche.wechatagent.memory;

import com.liche.wechatagent.agent.ContextStore;
import com.liche.wechatagent.backup.MemoryBackupJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Coordinates an explicit user forget request across durable memory, context, extraction, and local backups. */
@Service
public class MemoryForgetService {

    private static final Logger log = LoggerFactory.getLogger(MemoryForgetService.class);

    public record ForgetOutcome(boolean contextCleared, boolean backupsComplete, int removedRecordCount,
                                boolean legacyContextReset) {
    }

    private final MemoryService memoryService;
    private final MemoryExtractionScheduler extractionScheduler;
    private final ContextStore contextStore;
    private final MemoryMutationLock mutationLock;
    private final MemoryBackupJob backupJob;
    private final ConversationMemoryService conversationMemoryService;

    @Autowired
    public MemoryForgetService(MemoryService memoryService,
                               MemoryExtractionScheduler extractionScheduler,
                               ContextStore contextStore,
                               MemoryMutationLock mutationLock,
                               MemoryBackupJob backupJob,
                               ConversationMemoryService conversationMemoryService) {
        this.memoryService = memoryService;
        this.extractionScheduler = extractionScheduler;
        this.contextStore = contextStore;
        this.mutationLock = mutationLock;
        this.backupJob = backupJob;
        this.conversationMemoryService = conversationMemoryService;
    }

    public MemoryForgetService(MemoryService memoryService,
                               MemoryExtractionScheduler extractionScheduler,
                               ContextStore contextStore,
                               MemoryMutationLock mutationLock,
                               MemoryBackupJob backupJob) {
        this(memoryService, extractionScheduler, contextStore, mutationLock, backupJob, null);
    }

    /**
     * 遗忘一条记忆。
     *
     * <p>三表合并（2026-09-18）后不再需要 layer：编号本身就是全局唯一的 M 编号，
     * {@link MemoryService#forget} 拿到 id 会自己校验归属。第二个参数保留只是为了不改动调用方签名。
     */
    public ForgetOutcome forget(String userId, String ignoredLayer, Long memoryId) {
        extractionScheduler.cancelPending(userId);
        ForgetState state = mutationLock.callExclusive(userId, () -> forgetDurableState(userId, memoryId));
        boolean backupsComplete = true;
        for (ForgottenMemory forgotten : state.forgotten()) {
            MemoryBackupJob.PurgeResult result = backupJob.purgeForgottenMemory(userId, forgotten.layer(), forgotten.id());
            backupsComplete &= result != null && result.complete();
            if (conversationMemoryService != null) {
                MemoryBackupJob.PurgeResult conversationResult = backupJob.purgeForgottenConversationEvidence(
                        userId, forgotten.sourceMessageIds(), forgotten.content());
                backupsComplete &= conversationResult != null && conversationResult.complete();
            }
        }
        if (!backupsComplete) {
            log.warn("记忆已遗忘，但历史本机备份未完全清理 user={} targetId={}", userId, memoryId);
        }
        return new ForgetOutcome(state.contextCleared(), backupsComplete, state.forgotten().size(),
                state.legacyContextReset());
    }

    private ForgetState forgetDurableState(String userId, Long memoryId) {
        List<ForgottenMemory> forgotten = new ArrayList<>();
        ForgottenMemory primary = memoryService.forget(userId, memoryId);
        if (primary == null) {
            // 不存在、或不属于这个用户：什么都不做（也不能往下面按 null 取 layer）
            return new ForgetState(List.of(), false, false);
        }
        forgotten.add(primary);
        // 用户要的是"这件事彻底消失"：把它替代链上的历史版本一起忘掉
        forgotten.addAll(memoryService.forgetSupersededHistory(userId, primary.id()));
        boolean contextCleared = false;
        boolean legacyContextReset = false;
        for (ForgottenMemory item : forgotten) {
            // 经历类记忆也要按"证据来源"清掉由同一段对话推出来的那些
            memoryService.forgetEvidence(userId, item.sourceMessageIds(), item.content());
            if (item.sourceMessageIds().isEmpty()) {
                if (conversationMemoryService != null) {
                    conversationMemoryService.forgetContent(userId, item.content());
                }
                if (!legacyContextReset) {
                    contextCleared |= contextStore.clearForMemoryForget(userId);
                }
                legacyContextReset = true;
                continue;
            }
            contextCleared |= contextStore.removeMemoryEvidence(userId, item.sourceMessageIds(), item.content());
            if (conversationMemoryService != null) {
                conversationMemoryService.forgetSourceMessageIds(userId, item.sourceMessageIds());
            }
        }
        return new ForgetState(List.copyOf(forgotten), contextCleared, legacyContextReset);
    }

    private record ForgetState(List<ForgottenMemory> forgotten, boolean contextCleared, boolean legacyContextReset) {
    }
}
