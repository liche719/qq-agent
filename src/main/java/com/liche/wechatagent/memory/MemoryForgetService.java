package com.liche.wechatagent.memory;

import com.liche.wechatagent.agent.ContextStore;
import com.liche.wechatagent.backup.MemoryBackupJob;
import com.liche.wechatagent.exception.BizException;
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

    private final CoreMemoryService coreMemoryService;
    private final WorkMemoryService workMemoryService;
    private final MemoryArchiveService archiveService;
    private final MemoryExtractionScheduler extractionScheduler;
    private final ContextStore contextStore;
    private final MemoryMutationLock mutationLock;
    private final MemoryBackupJob backupJob;
    private final ConversationMemoryService conversationMemoryService;

    @Autowired
    public MemoryForgetService(CoreMemoryService coreMemoryService,
                               WorkMemoryService workMemoryService,
                               MemoryArchiveService archiveService,
                               MemoryExtractionScheduler extractionScheduler,
                               ContextStore contextStore,
                               MemoryMutationLock mutationLock,
                               MemoryBackupJob backupJob,
                               ConversationMemoryService conversationMemoryService) {
        this.coreMemoryService = coreMemoryService;
        this.workMemoryService = workMemoryService;
        this.archiveService = archiveService;
        this.extractionScheduler = extractionScheduler;
        this.contextStore = contextStore;
        this.mutationLock = mutationLock;
        this.backupJob = backupJob;
        this.conversationMemoryService = conversationMemoryService;
    }

    public MemoryForgetService(CoreMemoryService coreMemoryService,
                               WorkMemoryService workMemoryService,
                               MemoryArchiveService archiveService,
                               MemoryExtractionScheduler extractionScheduler,
                               ContextStore contextStore,
                               MemoryMutationLock mutationLock,
                               MemoryBackupJob backupJob) {
        this(coreMemoryService, workMemoryService, archiveService, extractionScheduler, contextStore,
                mutationLock, backupJob, null);
    }

    public ForgetOutcome forget(String userId, String layer, Long memoryId) {
        extractionScheduler.cancelPending(userId);
        ForgetState state = mutationLock.callExclusive(userId, () -> forgetDurableState(userId, layer, memoryId));
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
            log.warn("记忆已遗忘，但历史本机备份未完全清理 user={} layer={} targetId={}", userId, layer, memoryId);
        }
        return new ForgetOutcome(state.contextCleared(), backupsComplete, state.forgotten().size(),
                state.legacyContextReset());
    }

    private ForgetState forgetDurableState(String userId, String layer, Long memoryId) {
        ForgottenMemory primary = switch (layer) {
            case "CORE" -> coreMemoryService.delete(userId, memoryId);
            case "WORK" -> workMemoryService.forget(userId, memoryId);
            default -> throw new BizException("编号应以 C（核心）或 W（工作）开头");
        };
        List<ForgottenMemory> forgotten = new ArrayList<>();
        forgotten.add(primary);
        if ("CORE".equals(primary.layer())) {
            forgotten.addAll(coreMemoryService.forgetSupersededHistory(userId, primary.id()));
        } else if ("WORK".equals(primary.layer())) {
            forgotten.addAll(workMemoryService.forgetSupersededHistory(userId, primary.id()));
        }
        if (primary.isArchiveSummary()) {
            forgotten.addAll(archiveService.forgetSummary(userId, primary.content()));
        } else if ("WORK".equals(primary.layer())) {
            forgotten.addAll(archiveService.forgetSourceMemory(userId, primary.id()));
        }
        boolean contextCleared = false;
        boolean legacyContextReset = false;
        for (ForgottenMemory item : forgotten) {
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
