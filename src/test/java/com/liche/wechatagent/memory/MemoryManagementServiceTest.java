package com.liche.wechatagent.memory;

import com.liche.wechatagent.user.UserService;
import com.liche.wechatagent.media.StoredMedia;
import com.liche.wechatagent.media.StoredMediaRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
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
        MemoryForgetService forgetService = mock(MemoryForgetService.class);
        UserCoreMemory goal = new UserCoreMemory("u1", "用户的长期目标是考取南京理工大学研究生");
        goal.setId(7L);
        when(coreService.list("u1")).thenReturn(List.of(goal));
        when(workService.listActive("u1")).thenReturn(List.of());
        when(forgetService.forget("u1", "CORE", 7L))
                .thenReturn(new MemoryForgetService.ForgetOutcome(true, true, 1, false));
        MemoryManagementService service = service(mock(UserService.class), coreService, workService, null, forgetService);

        String result = service.handle("u1", "forget 南京理工");

        assertTrue(result.contains("彻底遗忘"));
        verify(forgetService).forget("u1", "CORE", 7L);
    }

    @Test
    void asksForIdWhenKeywordMatchesMultipleMemories() {
        CoreMemoryService coreService = mock(CoreMemoryService.class);
        WorkMemoryService workService = mock(WorkMemoryService.class);
        MemoryForgetService forgetService = mock(MemoryForgetService.class);
        UserCoreMemory first = new UserCoreMemory("u1", "南京理工大学考研目标");
        first.setId(1L);
        UserCoreMemory second = new UserCoreMemory("u1", "南京理工大学复习计划");
        second.setId(2L);
        when(coreService.list("u1")).thenReturn(List.of(first, second));
        when(workService.listActive("u1")).thenReturn(List.of());
        MemoryManagementService service = service(mock(UserService.class), coreService, workService, null, forgetService);

        String result = service.handle("u1", "forget 南京理工");

        assertTrue(result.contains("为避免删错"));
        verify(forgetService, never()).forget("u1", "CORE", 1L);
        verify(forgetService, never()).forget("u1", "CORE", 2L);
    }

    @Test
    void deletesUniqueMemoryWhenReferenceUsesTheUserOriginalWording() {
        CoreMemoryService coreService = mock(CoreMemoryService.class);
        WorkMemoryService workService = mock(WorkMemoryService.class);
        MemoryForgetService forgetService = mock(MemoryForgetService.class);
        UserCoreMemory goal = new UserCoreMemory("u1", "用户的长期目标是考取南京理工大学研究生");
        goal.setId(7L);
        when(coreService.list("u1")).thenReturn(List.of(goal));
        when(workService.listActive("u1")).thenReturn(List.of());
        when(forgetService.forget("u1", "CORE", 7L))
                .thenReturn(new MemoryForgetService.ForgetOutcome(true, true, 1, false));
        MemoryManagementService service = service(mock(UserService.class), coreService, workService, null, forgetService);

        String result = service.handle("u1", "forget 我想报考南京理工大学读研");

        assertTrue(result.contains("已彻底遗忘"));
        verify(forgetService).forget("u1", "CORE", 7L);
    }

    @Test
    void requiresAnIdWhenFuzzyReferenceHasMultipleCandidates() {
        CoreMemoryService coreService = mock(CoreMemoryService.class);
        WorkMemoryService workService = mock(WorkMemoryService.class);
        MemoryForgetService forgetService = mock(MemoryForgetService.class);
        UserCoreMemory goal = new UserCoreMemory("u1", "用户的长期目标是考取南京理工大学研究生");
        goal.setId(1L);
        UserCoreMemory plan = new UserCoreMemory("u1", "用户正在制定南京理工大学考研复习计划");
        plan.setId(2L);
        when(coreService.list("u1")).thenReturn(List.of(goal, plan));
        when(workService.listActive("u1")).thenReturn(List.of());
        MemoryManagementService service = service(mock(UserService.class), coreService, workService, null, forgetService);

        String result = service.handle("u1", "forget 我想删除南京理工大学相关的记忆");

        assertTrue(result.contains("为避免删错"));
        verify(forgetService, never()).forget("u1", "CORE", 1L);
        verify(forgetService, never()).forget("u1", "CORE", 2L);
    }

    @Test
    void neverSearchesOrDeletesAnotherUsersMemory() {
        CoreMemoryService coreService = mock(CoreMemoryService.class);
        WorkMemoryService workService = mock(WorkMemoryService.class);
        MemoryForgetService forgetService = mock(MemoryForgetService.class);
        UserCoreMemory otherUsersGoal = new UserCoreMemory("u2", "用户的长期目标是考取南京理工大学研究生");
        otherUsersGoal.setId(9L);
        when(coreService.list("u1")).thenReturn(List.of());
        when(coreService.list("u2")).thenReturn(List.of(otherUsersGoal));
        when(workService.listActive("u1")).thenReturn(List.of());
        MemoryManagementService service = service(mock(UserService.class), coreService, workService, null, forgetService);

        String result = service.handle("u1", "删除南京理工大学");

        assertTrue(result.contains("没有找到"));
        verify(coreService, never()).list("u2");
        verify(forgetService, never()).forget("u2", "CORE", 9L);
    }

    @Test
    void overviewUsesReadableNamesForCurrentUsersLinkedFiles() {
        UserService userService = mock(UserService.class);
        CoreMemoryService coreService = mock(CoreMemoryService.class);
        WorkMemoryService workService = mock(WorkMemoryService.class);
        StoredMediaRepository mediaRepository = mock(StoredMediaRepository.class);
        UserCoreMemory goal = new UserCoreMemory("u1", "用户正在根据课程表安排本周学习");
        goal.setId(3L);
        goal.setSourceMediaIds("42");
        goal.setLastConfirmedAt(LocalDateTime.now());
        StoredMedia media = new StoredMedia();
        media.setId(42L);
        media.setUserId("u1");
        media.setFileName("第5周课程表.png");
        when(userService.isMemoryEnabled("u1")).thenReturn(true);
        when(coreService.listActive("u1")).thenReturn(List.of(goal));
        when(workService.listActive("u1")).thenReturn(List.of());
        when(workService.listInactive("u1")).thenReturn(List.of());
        when(mediaRepository.findByUserIdAndIdInAndStatus("u1", List.of(42L), StoredMedia.ACTIVE))
                .thenReturn(List.of(media));
        MemoryManagementService service = service(userService, coreService, workService, mediaRepository,
                mock(MemoryForgetService.class));

        String overview = service.overview("u1");

        assertTrue(overview.contains("#42 第5周课程表.png"));
    }

    private MemoryManagementService service(UserService userService,
                                            CoreMemoryService coreService,
                                            WorkMemoryService workService,
                                            StoredMediaRepository mediaRepository,
                                            MemoryForgetService forgetService) {
        return new MemoryManagementService(userService, coreService, workService, mediaRepository,
                new MemoryContentSimilarity(0.8d), forgetService);
    }
}
