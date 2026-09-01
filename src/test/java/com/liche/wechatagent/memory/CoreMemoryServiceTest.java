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
import static org.mockito.Mockito.never;
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

    @Test
    void keepsThePreviousCoreFactAsSupersededWhenUserCorrectsIt() {
        UserCoreMemoryRepository coreRepository = mock(UserCoreMemoryRepository.class);
        MemoryChangeLogRepository changeLogRepository = mock(MemoryChangeLogRepository.class);
        UserCoreMemory previous = new UserCoreMemory("u1", "用户准备报考北京大学研究生");
        previous.setId(10L);
        when(coreRepository.findById(10L)).thenReturn(Optional.of(previous));
        when(coreRepository.save(any(UserCoreMemory.class))).thenAnswer(invocation -> {
            UserCoreMemory value = invocation.getArgument(0);
            if (value.getId() == null) {
                value.setId(11L);
            }
            return value;
        });
        CoreMemoryService service = new CoreMemoryService(coreRepository, changeLogRepository,
                new MemoryContentSimilarity(0.8d));

        UserCoreMemory replacement = service.replaceFromExtraction("u1", 10L,
                "用户准备报考南京理工大学研究生", "用户修正了报考目标", "AUTO",
                new MemoryProvenance("USER_DERIVED", 90, List.of("m-11"), List.of()),
                new MemoryAttributes(5, 90, List.of("南京理工")));

        assertEquals(MemoryStatus.SUPERSEDED.name(), previous.getStatus());
        assertEquals(11L, previous.getSupersededById());
        assertEquals("用户准备报考南京理工大学研究生", replacement.getContent());
    }

    @Test
    void deletingTheLatestFactAlsoFindsOnlyItsOwnSupersededHistory() {
        UserCoreMemoryRepository coreRepository = mock(UserCoreMemoryRepository.class);
        MemoryChangeLogRepository changeLogRepository = mock(MemoryChangeLogRepository.class);
        UserCoreMemory old = new UserCoreMemory("u1", "用户准备报考北京大学研究生");
        old.setId(10L);
        old.setStatus(MemoryStatus.SUPERSEDED.name());
        old.setSupersededById(11L);
        UserCoreMemory latest = new UserCoreMemory("u1", "用户准备报考南京理工大学研究生");
        latest.setId(11L);
        UserCoreMemory foreign = new UserCoreMemory("u2", "其他用户的学校信息");
        foreign.setId(12L);
        foreign.setStatus(MemoryStatus.SUPERSEDED.name());
        foreign.setSupersededById(11L);
        when(coreRepository.findByUserIdOrderByCreatedAtAsc("u1")).thenReturn(List.of(old, foreign));
        when(coreRepository.findById(10L)).thenReturn(Optional.of(old));
        CoreMemoryService service = new CoreMemoryService(coreRepository, changeLogRepository,
                new MemoryContentSimilarity(0.8d));

        List<ForgottenMemory> removed = service.forgetSupersededHistory("u1", 11L);

        assertEquals(List.of(10L), removed.stream().map(ForgottenMemory::id).toList());
        verify(coreRepository).delete(old);
        verify(coreRepository, never()).delete(foreign);
    }
}
