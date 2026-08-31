package com.liche.wechatagent.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkMemoryServiceTest {

    @Test
    void addsNewMemoryWithoutSilentlyReadingOrReplacingExistingFacts() {
        UserWorkMemoryRepository workRepository = mock(UserWorkMemoryRepository.class);
        MemoryChangeLogRepository changeLogRepository = mock(MemoryChangeLogRepository.class);
        when(workRepository.save(any(UserWorkMemory.class))).thenAnswer(invocation -> {
            UserWorkMemory memory = invocation.getArgument(0);
            memory.setId(1L);
            return memory;
        });
        WorkMemoryService service = new WorkMemoryService(workRepository, changeLogRepository);

        UserWorkMemory saved = service.add("u1", "本周完成 Android 项目原型", 9, "extraction", "AUTO");

        assertEquals("本周完成 Android 项目原型", saved.getContent());
        assertEquals(5, saved.getPriority());
        verify(workRepository, never()).findByUserIdAndArchivedFalse("u1");
        verify(changeLogRepository).save(any(MemoryChangeLog.class));
    }
}
