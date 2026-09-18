package com.liche.wechatagent.memory;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryLifecycleServiceTest {

    @Test
    void expiresOnlyStillActiveMemoriesWhoseExplicitDeadlineHasPassed() {
        UserWorkMemoryRepository workRepository = mock(UserWorkMemoryRepository.class);
        MemoryChangeLogRepository changeLogRepository = mock(MemoryChangeLogRepository.class);
        UserWorkMemory overdue = new UserWorkMemory("u1", "明天提交作业", 4, "extraction");
        overdue.setId(8L);
        overdue.setValidUntil(LocalDateTime.now().minusMinutes(1));
        UserWorkMemory completed = new UserWorkMemory("u1", "已经提交的作业", 3, "extraction");
        completed.setId(9L);
        completed.setValidUntil(LocalDateTime.now().minusMinutes(1));
        completed.setStatus(MemoryStatus.COMPLETED.name());
        when(workRepository.findByValidUntilBefore(any(LocalDateTime.class)))
                .thenReturn(List.of(overdue, completed));

        WorkMemoryService service = new WorkMemoryService(workRepository, changeLogRepository);

        assertEquals(1, service.expireDueMemories());
        assertEquals(MemoryStatus.EXPIRED.name(), overdue.getStatus());
        assertEquals(MemoryStatus.COMPLETED.name(), completed.getStatus());
        verify(workRepository, times(1)).save(overdue);
        verify(changeLogRepository, times(1)).save(any(MemoryChangeLog.class));
    }

    @Test
    void doesNotLoadCompletedOrExpiredWorkMemoryIntoConversation() {
        UserWorkMemory active = new UserWorkMemory("u1", "长期项目正在推进", 5, "extraction");
        UserWorkMemory completed = new UserWorkMemory("u1", "已经完成的任务", 3, "extraction");
        completed.setStatus(MemoryStatus.COMPLETED.name());
        UserWorkMemory expired = new UserWorkMemory("u1", "过期任务", 3, "extraction");
        expired.setValidUntil(LocalDateTime.now().minusSeconds(1));

        assertTrue(WorkMemoryService.isActive(active, LocalDateTime.now()));
        assertTrue(!WorkMemoryService.isActive(completed, LocalDateTime.now()));
        assertTrue(!WorkMemoryService.isActive(expired, LocalDateTime.now()));
    }
}
