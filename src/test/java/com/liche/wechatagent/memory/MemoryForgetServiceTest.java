package com.liche.wechatagent.memory;

import com.liche.wechatagent.agent.ContextStore;
import com.liche.wechatagent.backup.MemoryBackupJob;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryForgetServiceTest {

    @Test
    void cancelsExtractionThenDeletesMemoryAndPurgesAssociatedData() {
        CoreMemoryService coreMemoryService = mock(CoreMemoryService.class);
        WorkMemoryService workMemoryService = mock(WorkMemoryService.class);
        MemoryArchiveService archiveService = mock(MemoryArchiveService.class);
        MemoryExtractionScheduler extractionScheduler = mock(MemoryExtractionScheduler.class);
        ContextStore contextStore = mock(ContextStore.class);
        MemoryBackupJob backupJob = mock(MemoryBackupJob.class);
        ForgottenMemory forgotten = new ForgottenMemory("CORE", 5L, "用户的私密长期目标", "", List.of("m-5"));
        when(coreMemoryService.delete("u1", 5L)).thenReturn(forgotten);
        when(contextStore.removeMemoryEvidence("u1", List.of("m-5"), "用户的私密长期目标")).thenReturn(true);
        when(backupJob.purgeForgottenMemory("u1", "CORE", 5L))
                .thenReturn(new MemoryBackupJob.PurgeResult(2, true));
        MemoryForgetService service = new MemoryForgetService(coreMemoryService, workMemoryService, archiveService,
                extractionScheduler, contextStore, new MemoryMutationLock(), backupJob);

        MemoryForgetService.ForgetOutcome outcome = service.forget("u1", "CORE", 5L);

        var order = inOrder(extractionScheduler, coreMemoryService);
        order.verify(extractionScheduler).cancelPending("u1");
        order.verify(coreMemoryService).delete("u1", 5L);
        verify(contextStore).removeMemoryEvidence("u1", List.of("m-5"), "用户的私密长期目标");
        verify(backupJob).purgeForgottenMemory("u1", "CORE", 5L);
        assertTrue(outcome.contextCleared());
        assertTrue(outcome.backupsComplete());
        assertEquals(1, outcome.removedRecordCount());
        assertFalse(outcome.legacyContextReset());
    }

    @Test
    void clearsShortContextWhenLegacyMemoryHasNoSourceMessageProvenance() {
        CoreMemoryService coreMemoryService = mock(CoreMemoryService.class);
        WorkMemoryService workMemoryService = mock(WorkMemoryService.class);
        MemoryArchiveService archiveService = mock(MemoryArchiveService.class);
        MemoryExtractionScheduler extractionScheduler = mock(MemoryExtractionScheduler.class);
        ContextStore contextStore = mock(ContextStore.class);
        MemoryBackupJob backupJob = mock(MemoryBackupJob.class);
        ForgottenMemory forgotten = new ForgottenMemory("CORE", 6L, "旧版记忆", "", List.of());
        when(coreMemoryService.delete("u1", 6L)).thenReturn(forgotten);
        when(contextStore.clearForMemoryForget("u1")).thenReturn(true);
        when(backupJob.purgeForgottenMemory("u1", "CORE", 6L))
                .thenReturn(new MemoryBackupJob.PurgeResult(0, true));
        MemoryForgetService service = new MemoryForgetService(coreMemoryService, workMemoryService, archiveService,
                extractionScheduler, contextStore, new MemoryMutationLock(), backupJob);

        MemoryForgetService.ForgetOutcome outcome = service.forget("u1", "CORE", 6L);

        verify(contextStore).clearForMemoryForget("u1");
        verify(contextStore, never()).removeMemoryEvidence(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyString());
        assertTrue(outcome.legacyContextReset());
    }
}
