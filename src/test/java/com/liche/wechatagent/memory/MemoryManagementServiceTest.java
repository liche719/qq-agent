package com.liche.wechatagent.memory;

import com.liche.wechatagent.user.UserService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryManagementServiceTest {

    @Test
    void deletesUniqueMemoryByNaturalLanguageKeyword() {
        CoreMemoryService coreService = mock(CoreMemoryService.class);
        WorkMemoryService workService = mock(WorkMemoryService.class);
        UserCoreMemory goal = new UserCoreMemory("u1", "用户的长期目标是考取南京理工大学研究生");
        goal.setId(7L);
        when(coreService.list("u1")).thenReturn(List.of(goal));
        when(workService.listActive("u1")).thenReturn(List.of());
        MemoryManagementService service = new MemoryManagementService(mock(UserService.class), coreService, workService);

        String result = service.handle("u1", "forget 南京理工");

        assertTrue(result.contains("南京理工大学研究生"));
        verify(coreService).delete("u1", 7L);
    }

    @Test
    void asksForIdWhenKeywordMatchesMultipleMemories() {
        CoreMemoryService coreService = mock(CoreMemoryService.class);
        WorkMemoryService workService = mock(WorkMemoryService.class);
        UserCoreMemory first = new UserCoreMemory("u1", "南京理工大学考研目标");
        first.setId(1L);
        UserCoreMemory second = new UserCoreMemory("u1", "南京理工大学复习计划");
        second.setId(2L);
        when(coreService.list("u1")).thenReturn(List.of(first, second));
        when(workService.listActive("u1")).thenReturn(List.of());
        MemoryManagementService service = new MemoryManagementService(mock(UserService.class), coreService, workService);

        String result = service.handle("u1", "forget 南京理工");

        assertTrue(result.contains("为避免删错"));
        verify(coreService, never()).delete("u1", 1L);
        verify(coreService, never()).delete("u1", 2L);
    }
}
