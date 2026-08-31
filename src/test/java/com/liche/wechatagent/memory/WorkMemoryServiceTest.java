package com.liche.wechatagent.memory;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkMemoryServiceTest {

    @Test
    void addsNewMemoryWhenNoActiveEquivalentExists() {
        UserWorkMemoryRepository workRepository = mock(UserWorkMemoryRepository.class);
        MemoryChangeLogRepository changeLogRepository = mock(MemoryChangeLogRepository.class);
        when(workRepository.findByUserIdAndArchivedFalse("u1")).thenReturn(java.util.List.of());
        when(workRepository.save(any(UserWorkMemory.class))).thenAnswer(invocation -> {
            UserWorkMemory memory = invocation.getArgument(0);
            memory.setId(1L);
            return memory;
        });
        WorkMemoryService service = new WorkMemoryService(workRepository, changeLogRepository);

        UserWorkMemory saved = service.add("u1", "本周完成 Android 项目原型", 9, "extraction", "AUTO");

        assertEquals("本周完成 Android 项目原型", saved.getContent());
        assertEquals(5, saved.getPriority());
        verify(workRepository).findByUserIdAndArchivedFalse("u1");
        verify(changeLogRepository).save(any(MemoryChangeLog.class));
    }

    @Test
    void confirmsEquivalentWorkMemoryAndMergesEvidence() {
        UserWorkMemoryRepository workRepository = mock(UserWorkMemoryRepository.class);
        MemoryChangeLogRepository changeLogRepository = mock(MemoryChangeLogRepository.class);
        UserWorkMemory existing = new UserWorkMemory("u1", "本周完成 Android 项目原型", 3, "extraction");
        existing.setId(11L);
        existing.setSourceMessageIds("message-old");
        existing.setSourceMediaIds("5");
        when(workRepository.findByUserIdAndArchivedFalse("u1")).thenReturn(java.util.List.of(existing));
        when(workRepository.save(any(UserWorkMemory.class))).thenAnswer(invocation -> invocation.getArgument(0));
        WorkMemoryService service = new WorkMemoryService(workRepository, changeLogRepository);

        UserWorkMemory result = service.add("u1", "本周完成 Android 项目原型", 5, "extraction", "AUTO",
                MemoryProvenance.userExplicit(java.util.List.of("message-new"), java.util.List.of(8L)), null);

        assertSame(existing, result);
        assertEquals(5, existing.getPriority());
        assertEquals("message-old|message-new", existing.getSourceMessageIds());
        assertEquals("5|8", existing.getSourceMediaIds());
    }

    @Test
    void permanentlyDeletesWorkMemoryInsteadOfOnlyArchivingIt() {
        UserWorkMemoryRepository workRepository = mock(UserWorkMemoryRepository.class);
        MemoryChangeLogRepository changeLogRepository = mock(MemoryChangeLogRepository.class);
        UserWorkMemory memory = new UserWorkMemory("u1", "下周完成私密项目", 3, "extraction");
        memory.setId(12L);
        memory.setArchived(true);
        memory.setSourceMessageIds("message-12");
        when(workRepository.findById(12L)).thenReturn(Optional.of(memory));
        WorkMemoryService service = new WorkMemoryService(workRepository, changeLogRepository);

        ForgottenMemory forgotten = service.forget("u1", 12L);

        assertEquals(List.of("message-12"), forgotten.sourceMessageIds());
        verify(workRepository).delete(memory);
        verify(changeLogRepository).redactContentForMemory("u1", "WORK", 12L);
        org.mockito.ArgumentCaptor<MemoryChangeLog> log = org.mockito.ArgumentCaptor.forClass(MemoryChangeLog.class);
        verify(changeLogRepository).save(log.capture());
        assertEquals("FORGET", log.getValue().getAction());
        assertNull(log.getValue().getBeforeContent());
        assertNull(log.getValue().getAfterContent());
    }
}
