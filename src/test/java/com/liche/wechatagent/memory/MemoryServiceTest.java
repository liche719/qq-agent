package com.liche.wechatagent.memory;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 三表合一（2026-09-18）后的记忆服务用例：按 kind 组织。
 *
 * <p>合并前这里是 CoreMemoryServiceTest / WorkMemoryServiceTest / EpisodicMemoryServiceTest 三个类，
 * 现在合到一个类里——三种 kind 共用同一条持久化路径，只是行为分支不同。**覆盖一条都没丢**：
 * 判重确认、被替代留旧行（SUPERSEDED）、归属校验、有效期过期、标记完成、遗忘（含脱敏）、
 * 按 kind 过滤、以及按向量相关性排序。
 */
class MemoryServiceTest {

    private static final String U1 = "u1";

    private MemoryService service(MemoryRepository repository, MemoryChangeLogRepository changeLogs,
                                  double dedupThreshold) {
        return new MemoryService(repository, changeLogs, new MemoryContentSimilarity(dedupThreshold));
    }

    // ================= PROFILE（原 core） =================

    @Test
    void confirmsEquivalentProfileAndMergesItsSources() {
        MemoryRepository repository = mock(MemoryRepository.class);
        MemoryChangeLogRepository changeLogs = mock(MemoryChangeLogRepository.class);
        Memory existing = new Memory(U1, Memory.KIND_PROFILE, "用户的长期目标是考取南京理工大学研究生");
        existing.setId(3L);
        existing.setSourceMessageIds("message-old");
        existing.setSourceMediaIds("7");
        when(repository.findByUserIdAndKindOrderByUpdatedAtDesc(U1, Memory.KIND_PROFILE))
                .thenReturn(List.of(existing));
        when(repository.save(any(Memory.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MemoryService memoryService = service(repository, changeLogs, 0.8d);

        Memory result = memoryService.addProfile(U1, "用户长期目标是考南京理工大学研究生", "AUTO",
                MemoryProvenance.userExplicit(List.of("message-new"), List.of(9L)));

        assertSame(existing, result);
        assertEquals("message-old|message-new", existing.getSourceMessageIds());
        assertEquals("7|9", existing.getSourceMediaIds());
    }

    @Test
    void addsNewProfileWhenNothingSimilarExists() {
        MemoryRepository repository = mock(MemoryRepository.class);
        MemoryChangeLogRepository changeLogs = mock(MemoryChangeLogRepository.class);
        when(repository.findByUserIdAndKindOrderByUpdatedAtDesc(U1, Memory.KIND_PROFILE)).thenReturn(List.of());
        when(repository.save(any(Memory.class))).thenAnswer(invocation -> {
            Memory memory = invocation.getArgument(0);
            if (memory.getId() == null) {
                memory.setId(1L);
            }
            return memory;
        });
        MemoryService memoryService = service(repository, changeLogs, 0.8d);

        Memory saved = memoryService.addProfile(U1, "用户的长期目标是考取南京理工大学研究生", "AUTO");

        assertEquals(Memory.KIND_PROFILE, saved.getKind());
        assertTrue(saved.isAlwaysInject());
        verify(changeLogs).save(any(MemoryChangeLog.class));
    }

    @Test
    void permanentlyDeletesProfileAndRedactsEarlierAuditContent() {
        MemoryRepository repository = mock(MemoryRepository.class);
        MemoryChangeLogRepository changeLogs = mock(MemoryChangeLogRepository.class);
        Memory memory = new Memory(U1, Memory.KIND_PROFILE, "用户的私密长期目标");
        memory.setId(8L);
        memory.setSourceMessageIds("message-8");
        when(repository.findById(8L)).thenReturn(Optional.of(memory));
        MemoryService memoryService = service(repository, changeLogs, 0.8d);

        ForgottenMemory forgotten = memoryService.forget(U1, 8L);

        assertEquals(List.of("message-8"), forgotten.sourceMessageIds());
        assertEquals(Memory.KIND_PROFILE, forgotten.layer());
        verify(repository).delete(memory);
        verify(changeLogs).redactContentForMemory(U1, Memory.KIND_PROFILE, 8L);
        ArgumentCaptor<MemoryChangeLog> log = ArgumentCaptor.forClass(MemoryChangeLog.class);
        verify(changeLogs).save(log.capture());
        assertEquals("FORGET", log.getValue().getAction());
        assertNull(log.getValue().getBeforeContent());
        assertNull(log.getValue().getAfterContent());
        assertTrue(log.getValue().getReason().contains("正文已清除"));
    }

    @Test
    void keepsThePreviousProfileAsSupersededWhenUserCorrectsIt() {
        MemoryRepository repository = mock(MemoryRepository.class);
        MemoryChangeLogRepository changeLogs = mock(MemoryChangeLogRepository.class);
        Memory previous = new Memory(U1, Memory.KIND_PROFILE, "用户准备报考北京大学研究生");
        previous.setId(10L);
        when(repository.findById(10L)).thenReturn(Optional.of(previous));
        when(repository.save(any(Memory.class))).thenAnswer(invocation -> {
            Memory value = invocation.getArgument(0);
            if (value.getId() == null) {
                value.setId(11L);
            }
            return value;
        });
        MemoryService memoryService = service(repository, changeLogs, 0.8d);

        Memory replacement = memoryService.replaceProfile(U1, 10L,
                "用户准备报考南京理工大学研究生", "用户修正了报考目标", "AUTO",
                new MemoryProvenance("USER_DERIVED", 90, List.of("m-11"), List.of()),
                new MemoryAttributes(5, 90, List.of("南京理工")));

        assertEquals(MemoryStatus.SUPERSEDED.name(), previous.getStatus());
        assertEquals(11L, previous.getSupersededById());
        assertEquals("用户准备报考南京理工大学研究生", replacement.getContent());
        assertEquals(Memory.KIND_PROFILE, replacement.getKind());
    }

    @Test
    void deletingTheLatestProfileAlsoFindsOnlyItsOwnSupersededHistory() {
        MemoryRepository repository = mock(MemoryRepository.class);
        MemoryChangeLogRepository changeLogs = mock(MemoryChangeLogRepository.class);
        Memory old = new Memory(U1, Memory.KIND_PROFILE, "用户准备报考北京大学研究生");
        old.setId(10L);
        old.setStatus(MemoryStatus.SUPERSEDED.name());
        old.setSupersededById(11L);
        Memory foreign = new Memory("u2", Memory.KIND_PROFILE, "其他用户的学校信息");
        foreign.setId(12L);
        foreign.setStatus(MemoryStatus.SUPERSEDED.name());
        foreign.setSupersededById(11L);
        when(repository.findByUserIdOrderByUpdatedAtDesc(U1)).thenReturn(List.of(old, foreign));
        when(repository.findById(10L)).thenReturn(Optional.of(old));
        MemoryService memoryService = service(repository, changeLogs, 0.8d);

        List<ForgottenMemory> removed = memoryService.forgetSupersededHistory(U1, 11L);

        assertEquals(List.of(10L), removed.stream().map(ForgottenMemory::id).toList());
        verify(repository).delete(old);
        verify(repository, never()).delete(foreign);
    }

    // ================= TASK（原 work） =================

    @Test
    void addsNewTaskWhenNoActiveEquivalentExists() {
        MemoryRepository repository = mock(MemoryRepository.class);
        MemoryChangeLogRepository changeLogs = mock(MemoryChangeLogRepository.class);
        when(repository.findByUserIdAndKindOrderByUpdatedAtDesc(U1, Memory.KIND_TASK)).thenReturn(List.of());
        when(repository.save(any(Memory.class))).thenAnswer(invocation -> {
            Memory memory = invocation.getArgument(0);
            memory.setId(1L);
            return memory;
        });
        MemoryService memoryService = service(repository, changeLogs, 0.8d);

        Memory saved = memoryService.addTask(U1, "本周完成 Android 项目原型", 9, "extraction", "AUTO");

        assertEquals("本周完成 Android 项目原型", saved.getContent());
        assertEquals(Memory.KIND_TASK, saved.getKind());
        // 优先级夹到 1~5
        assertEquals(5, saved.getPriority());
        verify(repository).findByUserIdAndKindOrderByUpdatedAtDesc(U1, Memory.KIND_TASK);
        verify(changeLogs).save(any(MemoryChangeLog.class));
    }

    @Test
    void confirmsEquivalentTaskAndMergesEvidence() {
        MemoryRepository repository = mock(MemoryRepository.class);
        MemoryChangeLogRepository changeLogs = mock(MemoryChangeLogRepository.class);
        Memory existing = new Memory(U1, Memory.KIND_TASK, "本周完成 Android 项目原型");
        existing.setId(11L);
        existing.setPriority(3);
        existing.setSourceMessageIds("message-old");
        existing.setSourceMediaIds("5");
        when(repository.findByUserIdAndKindOrderByUpdatedAtDesc(U1, Memory.KIND_TASK))
                .thenReturn(List.of(existing));
        when(repository.save(any(Memory.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MemoryService memoryService = service(repository, changeLogs, 0.8d);

        Memory result = memoryService.addTask(U1, "本周完成 Android 项目原型", 5, "extraction", "AUTO",
                MemoryProvenance.userExplicit(List.of("message-new"), List.of(8L)), null);

        assertSame(existing, result);
        assertEquals(5, existing.getPriority());
        assertEquals("message-old|message-new", existing.getSourceMessageIds());
        assertEquals("5|8", existing.getSourceMediaIds());
    }

    @Test
    void permanentlyDeletesTaskInsteadOfOnlyArchivingIt() {
        MemoryRepository repository = mock(MemoryRepository.class);
        MemoryChangeLogRepository changeLogs = mock(MemoryChangeLogRepository.class);
        Memory memory = new Memory(U1, Memory.KIND_TASK, "下周完成私密项目");
        memory.setId(12L);
        memory.setSourceMessageIds("message-12");
        when(repository.findById(12L)).thenReturn(Optional.of(memory));
        MemoryService memoryService = service(repository, changeLogs, 0.8d);

        ForgottenMemory forgotten = memoryService.forget(U1, 12L);

        assertEquals(List.of("message-12"), forgotten.sourceMessageIds());
        verify(repository).delete(memory);
        verify(changeLogs).redactContentForMemory(U1, Memory.KIND_TASK, 12L);
        ArgumentCaptor<MemoryChangeLog> log = ArgumentCaptor.forClass(MemoryChangeLog.class);
        verify(changeLogs).save(log.capture());
        assertEquals("FORGET", log.getValue().getAction());
        assertNull(log.getValue().getBeforeContent());
        assertNull(log.getValue().getAfterContent());
    }

    @Test
    void keepsThePreviousTaskAsSupersededWhenItChanges() {
        MemoryRepository repository = mock(MemoryRepository.class);
        MemoryChangeLogRepository changeLogs = mock(MemoryChangeLogRepository.class);
        Memory previous = new Memory(U1, Memory.KIND_TASK, "本周在北京参加培训");
        previous.setId(20L);
        when(repository.findById(20L)).thenReturn(Optional.of(previous));
        when(repository.save(any(Memory.class))).thenAnswer(invocation -> {
            Memory value = invocation.getArgument(0);
            if (value.getId() == null) {
                value.setId(21L);
            }
            return value;
        });
        MemoryService memoryService = service(repository, changeLogs, 0.8d);

        Memory replacement = memoryService.replaceTask(U1, 20L,
                "本周在南京参加培训", new MemoryProvenance("USER_DERIVED", 90, List.of("m-21"), List.of()),
                null, new MemoryAttributes(3, 90, List.of("南京培训")));

        assertEquals(MemoryStatus.SUPERSEDED.name(), previous.getStatus());
        assertEquals(21L, previous.getSupersededById());
        assertEquals("本周在南京参加培训", replacement.getContent());
    }

    @Test
    void deletingTheLatestTaskAlsoFindsOnlyItsOwnSupersededHistory() {
        MemoryRepository repository = mock(MemoryRepository.class);
        MemoryChangeLogRepository changeLogs = mock(MemoryChangeLogRepository.class);
        Memory old = new Memory(U1, Memory.KIND_TASK, "本周在北京参加培训");
        old.setId(20L);
        old.setStatus(MemoryStatus.SUPERSEDED.name());
        old.setSupersededById(21L);
        Memory foreign = new Memory("u2", Memory.KIND_TASK, "其他用户的培训");
        foreign.setId(22L);
        foreign.setStatus(MemoryStatus.SUPERSEDED.name());
        foreign.setSupersededById(21L);
        when(repository.findByUserIdOrderByUpdatedAtDesc(U1)).thenReturn(List.of(old, foreign));
        when(repository.findById(20L)).thenReturn(Optional.of(old));
        MemoryService memoryService = service(repository, changeLogs, 0.8d);

        List<ForgottenMemory> removed = memoryService.forgetSupersededHistory(U1, 21L);

        assertEquals(List.of(20L), removed.stream().map(ForgottenMemory::id).toList());
        verify(repository).delete(old);
        verify(repository, never()).delete(foreign);
    }

    @Test
    void marksTaskCompletedOnlyWhileItIsStillActive() {
        MemoryRepository repository = mock(MemoryRepository.class);
        MemoryChangeLogRepository changeLogs = mock(MemoryChangeLogRepository.class);
        Memory active = new Memory(U1, Memory.KIND_TASK, "本周完成实验报告");
        active.setId(30L);
        Memory alreadyDone = new Memory(U1, Memory.KIND_TASK, "已经交过的报告");
        alreadyDone.setId(31L);
        alreadyDone.setStatus(MemoryStatus.COMPLETED.name());
        when(repository.findById(30L)).thenReturn(Optional.of(active));
        when(repository.findById(31L)).thenReturn(Optional.of(alreadyDone));
        MemoryService memoryService = service(repository, changeLogs, 0.8d);

        memoryService.markCompleted(U1, 30L, "用户说报告交了", MemoryProvenance.automatic("extraction"));
        memoryService.markCompleted(U1, 31L, "重复标记", MemoryProvenance.automatic("extraction"));

        assertEquals(MemoryStatus.COMPLETED.name(), active.getStatus());
        verify(repository).save(active);
        // 已经不是活跃状态的那条直接返回，不再写库
        verify(repository, never()).save(alreadyDone);
    }

    @Test
    void refusesToTouchAnotherUsersMemory() {
        MemoryRepository repository = mock(MemoryRepository.class);
        MemoryChangeLogRepository changeLogs = mock(MemoryChangeLogRepository.class);
        Memory foreign = new Memory("u2", Memory.KIND_TASK, "别人的中期事项");
        foreign.setId(40L);
        when(repository.findById(40L)).thenReturn(Optional.of(foreign));
        MemoryService memoryService = service(repository, changeLogs, 0.8d);

        assertNull(memoryService.find(U1, 40L));
        verify(repository, never()).delete(foreign);
    }

    // ================= EXPERIENCE（原 episode） =================

    @Test
    void persistsExperienceWithTitleAndTraceableSources() {
        MemoryRepository repository = mock(MemoryRepository.class);
        MemoryChangeLogRepository changeLogs = mock(MemoryChangeLogRepository.class);
        when(repository.findByUserIdAndKindOrderByUpdatedAtDesc(U1, Memory.KIND_EXPERIENCE)).thenReturn(List.of());
        when(repository.save(any(Memory.class))).thenAnswer(invocation -> {
            Memory memory = invocation.getArgument(0);
            if (memory.getId() == null) {
                memory.setId(50L);
            }
            return memory;
        });
        MemoryService memoryService = service(repository, changeLogs, 0.8d);
        LocalDateTime occurredAt = LocalDateTime.of(2026, 9, 2, 9, 30);

        memoryService.addExperience(U1, "打印申请表", "用户因申请表未及时打印而着急，要求重要提醒可靠确认",
                "milestone", 5, 92, List.of("申请表", "提醒"), occurredAt,
                MemoryProvenance.userExplicit(List.of("message-1"), List.of(8L)));

        ArgumentCaptor<Memory> saved = ArgumentCaptor.forClass(Memory.class);
        verify(repository).save(saved.capture());
        assertEquals(U1, saved.getValue().getUserId());
        assertEquals(Memory.KIND_EXPERIENCE, saved.getValue().getKind());
        assertEquals("MILESTONE", saved.getValue().getCategory());
        assertEquals(occurredAt, saved.getValue().getOccurredAt());
        // 经历原来是 summary，现在统一落在 content 上
        assertEquals("用户因申请表未及时打印而着急，要求重要提醒可靠确认", saved.getValue().getContent());
        assertEquals("message-1", saved.getValue().getSourceMessageIds());
        assertEquals("8", saved.getValue().getSourceMediaIds());
    }

    @Test
    void updatesEquivalentExperienceInsteadOfCreatingASecondCopy() {
        MemoryRepository repository = mock(MemoryRepository.class);
        MemoryChangeLogRepository changeLogs = mock(MemoryChangeLogRepository.class);
        Memory existing = new Memory(U1, Memory.KIND_EXPERIENCE,
                "用户因为申请表没有打印而很着急，希望提醒更可靠");
        existing.setId(60L);
        existing.setTitle("旧标题");
        existing.setCategory("EXPERIENCE");
        existing.setConfidence(85);
        existing.setOccurredAt(LocalDateTime.of(2026, 9, 1, 10, 0));
        existing.setSourceMessageIds("old");
        when(repository.findByUserIdAndKindOrderByUpdatedAtDesc(U1, Memory.KIND_EXPERIENCE))
                .thenReturn(List.of(existing));
        when(repository.save(any(Memory.class))).thenAnswer(invocation -> invocation.getArgument(0));
        MemoryService memoryService = service(repository, changeLogs, 0.6d);

        memoryService.addExperience(U1, "申请表提醒", "用户因为申请表没有打印而很着急，希望提醒更可靠",
                "EXPERIENCE", 5, 95, List.of("申请表"), LocalDateTime.of(2026, 9, 2, 10, 0),
                MemoryProvenance.userExplicit(List.of("new"), List.of()));

        verify(repository).save(existing);
        assertEquals("申请表提醒", existing.getTitle());
        assertEquals("old|new", existing.getSourceMessageIds());
        assertEquals(95, existing.getConfidence());
        // 还是同一条，没有新建
        assertEquals(60L, existing.getId());
    }

    // ================= 生命周期与向量 =================

    @Test
    void expiresOnlyStillActiveMemoriesWhoseExplicitDeadlineHasPassed() {
        MemoryRepository repository = mock(MemoryRepository.class);
        MemoryChangeLogRepository changeLogs = mock(MemoryChangeLogRepository.class);
        Memory overdue = new Memory(U1, Memory.KIND_TASK, "明天提交作业");
        overdue.setId(8L);
        overdue.setPriority(4);
        overdue.setValidUntil(LocalDateTime.now().minusMinutes(1));
        Memory completed = new Memory(U1, Memory.KIND_TASK, "已经提交的作业");
        completed.setId(9L);
        completed.setValidUntil(LocalDateTime.now().minusMinutes(1));
        completed.setStatus(MemoryStatus.COMPLETED.name());
        when(repository.findByValidUntilBefore(any(LocalDateTime.class)))
                .thenReturn(List.of(overdue, completed));
        MemoryService memoryService = service(repository, changeLogs, 0.8d);

        assertEquals(1, memoryService.expireDueMemories());
        assertEquals(MemoryStatus.EXPIRED.name(), overdue.getStatus());
        assertEquals(MemoryStatus.COMPLETED.name(), completed.getStatus());
        verify(repository).save(overdue);
        verify(changeLogs).save(any(MemoryChangeLog.class));
    }

    @Test
    void doesNotTreatCompletedOrExpiredMemoryAsActive() {
        LocalDateTime now = LocalDateTime.now();
        Memory active = new Memory(U1, Memory.KIND_TASK, "长期项目正在推进");
        Memory completed = new Memory(U1, Memory.KIND_TASK, "已经完成的任务");
        completed.setStatus(MemoryStatus.COMPLETED.name());
        Memory expired = new Memory(U1, Memory.KIND_TASK, "过期任务");
        expired.setValidUntil(now.minusSeconds(1));

        assertTrue(MemoryService.isActive(active, now));
        assertFalse(MemoryService.isActive(completed, now));
        assertFalse(MemoryService.isActive(expired, now));
    }

    /**
     * 这条原来断言的是 {@code saveAll}，而服务早就改成"只收集过期没刷的 id，走定向 UPDATE"
     * （整行 saveAll 会按旧快照把并发的状态变更冲掉，见 MemoryRepository.updateLastUsedAt 的注释）。
     * 所以按**现在的真实行为**改：只有超过节流窗口的那条 id 被 UPDATE，最近刷过的那条不动。
     */
    @Test
    void touchesOnlyMemoriesOutsideTheWriteThrottleWindow() {
        MemoryRepository repository = mock(MemoryRepository.class);
        MemoryChangeLogRepository changeLogs = mock(MemoryChangeLogRepository.class);
        Memory stale = new Memory(U1, Memory.KIND_EXPERIENCE, "一段需要刷新的长期经历摘要");
        stale.setId(70L);
        Memory recent = new Memory(U1, Memory.KIND_EXPERIENCE, "一段刚刚使用过的长期经历摘要");
        recent.setId(71L);
        LocalDateTime now = LocalDateTime.of(2026, 9, 2, 15, 0);
        stale.setLastUsedAt(now.minusHours(1));
        recent.setLastUsedAt(now.minusMinutes(2));
        MemoryService memoryService = service(repository, changeLogs, 0.8d);

        memoryService.touch(List.of(stale, recent), now, 15);

        verify(repository).updateLastUsedAt(List.of(70L), now);
        // 走定向 UPDATE，不再整行回写
        verify(repository, never()).saveAll(any());
    }

    @Test
    void ranksOnlyWantedKindsByVectorScore() {
        MemoryRepository repository = mock(MemoryRepository.class);
        MemoryChangeLogRepository changeLogs = mock(MemoryChangeLogRepository.class);
        Memory task = new Memory(U1, Memory.KIND_TASK, "南京理工大学的复习安排");
        task.setId(80L);
        task.setImportance(3);
        Memory experience = new Memory(U1, Memory.KIND_EXPERIENCE, "考研报名那天很紧张");
        experience.setId(81L);
        experience.setImportance(3);
        when(repository.findByUserIdOrderByUpdatedAtDesc(U1)).thenReturn(List.of(task, experience));
        MemoryService memoryService = service(repository, changeLogs, 0.8d);

        // 没有装配向量库时按向量取结果是空的（不做字面兜底）——这条覆盖"算不出向量就不注入"
        assertTrue(memoryService.rankByVector(U1, new float[]{0.1f, 0.2f}, 0.5d).isEmpty());
        assertEquals(0L, memoryService.countMissingEmbedding(U1));
        assertTrue(memoryService.embeddedIds(U1).isEmpty());
    }

    // ================= 按 kind 过滤 =================

    @Test
    void listsAlwaysInjectMemoriesAsActiveExplicitProfilesOnly() {
        MemoryRepository repository = mock(MemoryRepository.class);
        MemoryChangeLogRepository changeLogs = mock(MemoryChangeLogRepository.class);
        Memory profile = new Memory(U1, Memory.KIND_PROFILE, "用户的长期目标是考取南京理工大学研究生");
        profile.setId(90L);
        Memory nonExplicit = new Memory(U1, Memory.KIND_PROFILE, "模型推测出来的设定");
        nonExplicit.setId(91L);
        nonExplicit.setSourceType("MODEL_INFERRED");
        Memory superseded = new Memory(U1, Memory.KIND_PROFILE, "被替代掉的旧设定");
        superseded.setId(92L);
        superseded.setStatus(MemoryStatus.SUPERSEDED.name());
        when(repository.findByUserIdOrderByUpdatedAtDesc(U1))
                .thenReturn(List.of(profile, nonExplicit, superseded));
        when(repository.findByUserIdAndKindOrderByUpdatedAtDesc(U1, Memory.KIND_PROFILE))
                .thenReturn(List.of(profile, nonExplicit, superseded));
        when(repository.findByUserIdAndKindOrderByUpdatedAtDesc(U1, Memory.KIND_TASK))
                .thenReturn(List.of());
        MemoryService memoryService = service(repository, changeLogs, 0.8d);

        List<Memory> always = memoryService.listAlwaysInject(U1);
        assertEquals(List.of(90L), always.stream().map(Memory::getId).toList());

        List<Memory> inactive = memoryService.listInactive(U1);
        assertEquals(List.of(92L), inactive.stream().map(Memory::getId).toList());
        assertTrue(memoryService.listActive(U1, Memory.KIND_TASK).isEmpty());
    }
}
