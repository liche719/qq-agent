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
        MemoryService memoryService = mock(MemoryService.class);
        MemoryForgetService forgetService = mock(MemoryForgetService.class);
        Memory goal = new Memory("u1", Memory.KIND_PROFILE, "用户的长期目标是考取南京理工大学研究生");
        goal.setId(7L);
        when(memoryService.list("u1")).thenReturn(List.of(goal));
        when(forgetService.forget("u1", "M", 7L))
                .thenReturn(new MemoryForgetService.ForgetOutcome(true, true, 1, false));
        MemoryManagementService service = service(mock(UserService.class), memoryService, null, forgetService);

        String result = service.handle("u1", "forget 南京理工");

        assertTrue(result.contains("彻底遗忘"));
        verify(forgetService).forget("u1", "M", 7L);
    }

    @Test
    void asksForIdWhenKeywordMatchesMultipleMemories() {
        MemoryService memoryService = mock(MemoryService.class);
        MemoryForgetService forgetService = mock(MemoryForgetService.class);
        Memory first = new Memory("u1", Memory.KIND_PROFILE, "南京理工大学考研目标");
        first.setId(1L);
        Memory second = new Memory("u1", Memory.KIND_TASK, "南京理工大学复习计划");
        second.setId(2L);
        when(memoryService.list("u1")).thenReturn(List.of(first, second));
        MemoryManagementService service = service(mock(UserService.class), memoryService, null, forgetService);

        String result = service.handle("u1", "forget 南京理工");

        assertTrue(result.contains("为避免删错"));
        // 提示里给的是新的 M 编号
        assertTrue(result.contains("M1"));
        verify(forgetService, never()).forget("u1", "M", 1L);
        verify(forgetService, never()).forget("u1", "M", 2L);
    }

    @Test
    void deletesUniqueMemoryWhenReferenceUsesTheUserOriginalWording() {
        MemoryService memoryService = mock(MemoryService.class);
        MemoryForgetService forgetService = mock(MemoryForgetService.class);
        Memory goal = new Memory("u1", Memory.KIND_PROFILE, "用户的长期目标是考取南京理工大学研究生");
        goal.setId(7L);
        when(memoryService.list("u1")).thenReturn(List.of(goal));
        when(forgetService.forget("u1", "M", 7L))
                .thenReturn(new MemoryForgetService.ForgetOutcome(true, true, 1, false));
        MemoryManagementService service = service(mock(UserService.class), memoryService, null, forgetService);

        String result = service.handle("u1", "forget 我想报考南京理工大学读研");

        assertTrue(result.contains("已彻底遗忘"));
        verify(forgetService).forget("u1", "M", 7L);
    }

    @Test
    void requiresAnIdWhenFuzzyReferenceHasMultipleCandidates() {
        MemoryService memoryService = mock(MemoryService.class);
        MemoryForgetService forgetService = mock(MemoryForgetService.class);
        Memory goal = new Memory("u1", Memory.KIND_PROFILE, "用户的长期目标是考取南京理工大学研究生");
        goal.setId(1L);
        Memory plan = new Memory("u1", Memory.KIND_TASK, "用户正在制定南京理工大学考研复习计划");
        plan.setId(2L);
        when(memoryService.list("u1")).thenReturn(List.of(goal, plan));
        MemoryManagementService service = service(mock(UserService.class), memoryService, null, forgetService);

        String result = service.handle("u1", "forget 我想删除南京理工大学相关的记忆");

        assertTrue(result.contains("为避免删错"));
        verify(forgetService, never()).forget("u1", "M", 1L);
        verify(forgetService, never()).forget("u1", "M", 2L);
    }

    @Test
    void neverSearchesOrDeletesAnotherUsersMemory() {
        MemoryService memoryService = mock(MemoryService.class);
        MemoryForgetService forgetService = mock(MemoryForgetService.class);
        Memory otherUsersGoal = new Memory("u2", Memory.KIND_PROFILE, "用户的长期目标是考取南京理工大学研究生");
        otherUsersGoal.setId(9L);
        when(memoryService.list("u1")).thenReturn(List.of());
        when(memoryService.list("u2")).thenReturn(List.of(otherUsersGoal));
        MemoryManagementService service = service(mock(UserService.class), memoryService, null, forgetService);

        String result = service.handle("u1", "删除南京理工大学");

        assertTrue(result.contains("没有找到"));
        verify(memoryService, never()).list("u2");
        verify(forgetService, never()).forget("u2", "M", 9L);
    }

    /** 旧编号（C3 / W12）仍在输入里出现：只提示编号形式已改，不再按旧前缀删 */
    @Test
    void tellsTheUserTheNumberingChangedWhenTheyUseTheOldPrefix() {
        MemoryService memoryService = mock(MemoryService.class);
        MemoryForgetService forgetService = mock(MemoryForgetService.class);
        MemoryManagementService service = service(mock(UserService.class), memoryService, null, forgetService);

        String coreResult = service.handle("u1", "forget C3");
        String workResult = service.handle("u1", "forget W12");

        assertTrue(coreResult.contains("编号形式已改"));
        assertTrue(workResult.contains("编号形式已改"));
        assertTrue(coreResult.contains("M 编号"));
        verify(forgetService, never()).forget(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void deletesByUnifiedMemoryNumber() {
        MemoryService memoryService = mock(MemoryService.class);
        MemoryForgetService forgetService = mock(MemoryForgetService.class);
        when(forgetService.forget("u1", "M", 34L))
                .thenReturn(new MemoryForgetService.ForgetOutcome(true, true, 1, false));
        MemoryManagementService service = service(mock(UserService.class), memoryService, null, forgetService);

        String result = service.handle("u1", "forget M34");

        assertTrue(result.contains("已彻底遗忘"));
        verify(forgetService).forget("u1", "M", 34L);
    }

    @Test
    void overviewGroupsMemoriesByKindAndUsesTheUnifiedNumber() {
        UserService userService = mock(UserService.class);
        MemoryService memoryService = mock(MemoryService.class);
        StoredMediaRepository mediaRepository = mock(StoredMediaRepository.class);
        Memory goal = new Memory("u1", Memory.KIND_PROFILE, "用户正在根据课程表安排本周学习");
        goal.setId(3L);
        goal.setSourceMediaIds("42");
        goal.setLastConfirmedAt(LocalDateTime.now());
        Memory task = new Memory("u1", Memory.KIND_TASK, "本周完成实验报告");
        task.setId(4L);
        Memory experience = new Memory("u1", Memory.KIND_EXPERIENCE, "考研报名那天很紧张");
        experience.setId(5L);
        StoredMedia media = new StoredMedia();
        media.setId(42L);
        media.setUserId("u1");
        media.setFileName("第5周课程表.png");
        when(userService.isMemoryEnabled("u1")).thenReturn(true);
        when(memoryService.listActive("u1", Memory.KIND_PROFILE)).thenReturn(List.of(goal));
        when(memoryService.listActive("u1", Memory.KIND_TASK)).thenReturn(List.of(task));
        when(memoryService.listActive("u1", Memory.KIND_EXPERIENCE)).thenReturn(List.of(experience));
        when(memoryService.listInactive("u1")).thenReturn(List.of());
        when(mediaRepository.findByUserIdAndIdInAndStatus("u1", List.of(42L), StoredMedia.ACTIVE))
                .thenReturn(List.of(media));
        MemoryManagementService service = service(userService, memoryService, mediaRepository,
                mock(MemoryForgetService.class));

        String overview = service.overview("u1");

        assertTrue(overview.contains("【长期设定】"));
        assertTrue(overview.contains("【中期事项】"));
        assertTrue(overview.contains("【经历】"));
        assertTrue(overview.contains("M3：用户正在根据课程表安排本周学习"));
        assertTrue(overview.contains("M4：本周完成实验报告"));
        assertTrue(overview.contains("M5：考研报名那天很紧张"));
        assertTrue(overview.contains("#42 第5周课程表.png"));
    }

    @Test
    void overviewSeparatesFinishedTasksIntoTheirOwnSection() {
        UserService userService = mock(UserService.class);
        MemoryService memoryService = mock(MemoryService.class);
        Memory done = new Memory("u1", Memory.KIND_TASK, "已经交过的报告");
        done.setId(9L);
        done.setStatus(MemoryStatus.COMPLETED.name());
        when(userService.isMemoryEnabled("u1")).thenReturn(true);
        when(memoryService.listActive("u1", Memory.KIND_PROFILE)).thenReturn(List.of());
        when(memoryService.listActive("u1", Memory.KIND_TASK)).thenReturn(List.of());
        when(memoryService.listActive("u1", Memory.KIND_EXPERIENCE)).thenReturn(List.of());
        when(memoryService.listInactive("u1")).thenReturn(List.of(done));
        MemoryManagementService service = service(userService, memoryService, null,
                mock(MemoryForgetService.class));

        String overview = service.overview("u1");

        assertTrue(overview.contains("【近期已结束/过期】"));
        assertTrue(overview.contains("M9（已完成）：已经交过的报告"));
    }

    private MemoryManagementService service(UserService userService,
                                            MemoryService memoryService,
                                            StoredMediaRepository mediaRepository,
                                            MemoryForgetService forgetService) {
        return new MemoryManagementService(userService, memoryService, mediaRepository,
                new MemoryContentSimilarity(0.8d), forgetService);
    }
}
