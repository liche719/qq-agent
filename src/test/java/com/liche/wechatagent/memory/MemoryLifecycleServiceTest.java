package com.liche.wechatagent.memory;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 生命周期扫描现在只剩一层壳：真正的"到点才过期"逻辑在三表合一后的 {@link MemoryService#expireDueMemories()}，
 * 用例在 {@code MemoryServiceTest}（expiresOnlyStillActiveMemoriesWhoseExplicitDeadlineHasPassed）。
 * 这里只保证这条链路还接着。
 */
class MemoryLifecycleServiceTest {

    @Test
    void delegatesExpiryToTheMergedMemoryService() {
        MemoryService memoryService = mock(MemoryService.class);
        ConversationMemoryService conversations = mock(ConversationMemoryService.class);
        when(memoryService.expireDueMemories()).thenReturn(3);
        MemoryLifecycleService service = new MemoryLifecycleService(memoryService, conversations);

        service.expireDueMemories();

        verify(memoryService).expireDueMemories();
        verify(conversations).purgeExpired();
    }

    @Test
    void keepsScanningConversationEvidenceWhenMemoryExpiryFails() {
        MemoryService memoryService = mock(MemoryService.class);
        ConversationMemoryService conversations = mock(ConversationMemoryService.class);
        when(memoryService.expireDueMemories()).thenThrow(new IllegalStateException("database down"));
        MemoryLifecycleService service = new MemoryLifecycleService(memoryService, conversations);

        service.expireDueMemories();

        // 记忆扫描炸了不能连坐对话证据的清理
        verify(conversations).purgeExpired();
    }
}
