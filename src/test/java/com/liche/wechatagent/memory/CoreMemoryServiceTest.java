package com.liche.wechatagent.memory;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CoreMemoryServiceTest {

    @Test
    void confirmsEquivalentGoalAndMergesItsSources() {
        UserCoreMemoryRepository coreRepository = mock(UserCoreMemoryRepository.class);
        MemoryChangeLogRepository changeLogRepository = mock(MemoryChangeLogRepository.class);
        UserCoreMemory existing = new UserCoreMemory("u1", "用户的长期目标是考取南京理工大学研究生");
        existing.setId(3L);
        existing.setSourceMessageIds("message-old");
        existing.setSourceMediaIds("7");
        when(coreRepository.findByUserIdOrderByCreatedAtAsc("u1")).thenReturn(List.of(existing));
        when(coreRepository.save(any(UserCoreMemory.class))).thenAnswer(invocation -> invocation.getArgument(0));
        CoreMemoryService service = new CoreMemoryService(coreRepository, changeLogRepository,
                new MemoryContentSimilarity(0.8d));

        UserCoreMemory result = service.add("u1", "用户长期目标是考南京理工大学研究生", "AUTO",
                MemoryProvenance.userExplicit(List.of("message-new"), List.of(9L)));

        assertSame(existing, result);
        assertEquals("message-old|message-new", existing.getSourceMessageIds());
        assertEquals("7|9", existing.getSourceMediaIds());
    }

    @Test
    void permanentlyDeletesCoreMemoryAndRedactsEarlierAuditContent() {
        UserCoreMemoryRepository coreRepository = mock(UserCoreMemoryRepository.class);
        MemoryChangeLogRepository changeLogRepository = mock(MemoryChangeLogRepository.class);
        UserCoreMemory memory = new UserCoreMemory("u1", "用户的私密长期目标");
        memory.setId(8L);
        memory.setSourceMessageIds("message-8");
        when(coreRepository.findById(8L)).thenReturn(Optional.of(memory));
        CoreMemoryService service = new CoreMemoryService(coreRepository, changeLogRepository,
                new MemoryContentSimilarity(0.8d));

        ForgottenMemory forgotten = service.delete("u1", 8L);

        assertEquals(List.of("message-8"), forgotten.sourceMessageIds());
        verify(coreRepository).delete(memory);
        verify(changeLogRepository).redactContentForMemory("u1", "CORE", 8L);
        org.mockito.ArgumentCaptor<MemoryChangeLog> log = org.mockito.ArgumentCaptor.forClass(MemoryChangeLog.class);
        verify(changeLogRepository).save(log.capture());
        assertEquals("FORGET", log.getValue().getAction());
        assertNull(log.getValue().getBeforeContent());
        assertNull(log.getValue().getAfterContent());
        assertTrue(log.getValue().getReason().contains("正文已清除"));
    }
}
