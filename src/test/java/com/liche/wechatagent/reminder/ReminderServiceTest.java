package com.liche.wechatagent.reminder;

import com.liche.wechatagent.log.UserLogService;
import com.liche.wechatagent.channel.WeChatChannel;
import com.liche.wechatagent.user.UserProfile;
import com.liche.wechatagent.user.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.Scheduler;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReminderServiceTest {

    @Test
    void incompleteParsedReminderDoesNotWriteOrSchedule() throws Exception {
        ReminderTaskRepository repository = mock(ReminderTaskRepository.class);
        Scheduler scheduler = mock(Scheduler.class);
        ReminderService service = service(repository, scheduler);

        ReminderOperationResult result = service.createFromParsedResult(
                new ReminderParseService.ParsedReminder("打印申请表", null, 10, null, List.of("具体时间")),
                "user-a");

        assertFalse(result.completed());
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(any(ReminderTask.class));
        org.mockito.Mockito.verify(scheduler, org.mockito.Mockito.never())
                .scheduleJob(any(org.quartz.JobDetail.class), any(org.quartz.Trigger.class));
    }

    @Test
    void replacementKeepsOldReminderWhenNewSchedulingFails() throws Exception {
        ReminderTaskRepository repository = mock(ReminderTaskRepository.class);
        Scheduler scheduler = mock(Scheduler.class);
        ReminderTask oldTask = new ReminderTask("user-a", "打印申请表",
                LocalDateTime.now().plusHours(2), 10, null);
        oldTask.setId(16L);
        when(repository.findByIdAndUserId(16L, "user-a")).thenReturn(Optional.of(oldTask));
        AtomicLong ids = new AtomicLong(16);
        doAnswer(invocation -> {
            ReminderTask task = invocation.getArgument(0);
            if (task.getId() == null) {
                task.setId(ids.incrementAndGet());
            }
            return task;
        }).when(repository).save(any(ReminderTask.class));
        doThrow(new org.quartz.SchedulerException("scheduler down"))
                .when(scheduler).scheduleJob(any(org.quartz.JobDetail.class), any(org.quartz.Trigger.class));

        ReminderOperationResult result = service(repository, scheduler).replaceFromParsed(
                new ReminderParseService.ParsedReminder("打印申请表", LocalDateTime.now().plusHours(3), 10, null, List.of()),
                "user-a", 16L);

        assertFalse(result.completed());
        assertTrue(ReminderTask.STATUS_PENDING.equals(oldTask.getStatus()));
        org.mockito.Mockito.verify(scheduler, org.mockito.Mockito.never())
                .deleteJob(new org.quartz.JobKey("reminder-16", "reminders"));
    }

    @Test
    void marksMissedOneShotReminderExpiredInsteadOfLeavingItPending() throws Exception {
        ReminderTaskRepository repository = mock(ReminderTaskRepository.class);
        Scheduler scheduler = mock(Scheduler.class);
        ReminderTask task = new ReminderTask("user-a", "打印申请表",
                LocalDateTime.now().minusMinutes(2), 10, null);
        task.setId(13L);
        when(repository.findByStatusAndTriggerAtBefore(any(String.class), any(LocalDateTime.class)))
                .thenReturn(List.of(task));

        ReminderService service = service(repository, scheduler);
        service.expireOverduePending();

        assertTrue(ReminderTask.STATUS_EXPIRED.equals(task.getStatus()));
        org.mockito.Mockito.verify(repository).save(task);
        org.mockito.Mockito.verify(scheduler).deleteJob(new org.quartz.JobKey("reminder-13", "reminders"));
    }

    @Test
    void recoveryRecreatesMissingFutureQuartzJob() throws Exception {
        ReminderTaskRepository repository = mock(ReminderTaskRepository.class);
        Scheduler scheduler = mock(Scheduler.class);
        ReminderTask task = new ReminderTask("user-a", "开会",
                LocalDateTime.now().plusHours(2), 0, null);
        task.setId(19L);
        when(repository.findByStatusAndTriggerAtBefore(any(String.class), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(repository.findByStatus(ReminderTask.STATUS_PENDING)).thenReturn(List.of(task));
        when(scheduler.checkExists(new org.quartz.JobKey("reminder-19", "reminders"))).thenReturn(false);

        service(repository, scheduler).recoverPendingReminders();

        org.mockito.Mockito.verify(scheduler).scheduleJob(any(org.quartz.JobDetail.class), any(org.quartz.Trigger.class));
    }

    @Test
    void recoveryRepairsExistingMainJobWhenItsTriggerIsMissing() throws Exception {
        ReminderTaskRepository repository = mock(ReminderTaskRepository.class);
        Scheduler scheduler = mock(Scheduler.class);
        ReminderTask task = new ReminderTask("user-a", "开会",
                LocalDateTime.now().plusHours(2), 0, null);
        task.setId(20L);
        when(repository.findByStatusAndTriggerAtBefore(any(String.class), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(repository.findByStatus(ReminderTask.STATUS_PENDING)).thenReturn(List.of(task));
        org.quartz.JobKey mainKey = new org.quartz.JobKey("reminder-20", "reminders");
        when(scheduler.checkExists(mainKey)).thenReturn(true);
        when(scheduler.getTriggersOfJob(mainKey)).thenReturn(List.of());

        service(repository, scheduler).recoverPendingReminders();

        verify(scheduler).deleteJob(mainKey);
        verify(scheduler).deleteJob(new org.quartz.JobKey("prewarm-20", "reminders"));
        verify(scheduler).scheduleJob(any(org.quartz.JobDetail.class), any(org.quartz.Trigger.class));
    }

    @Test
    void recoveryRecreatesMissingPrewarmJobWhenMainTriggerStillExists() throws Exception {
        ReminderTaskRepository repository = mock(ReminderTaskRepository.class);
        Scheduler scheduler = mock(Scheduler.class);
        ReminderTask task = new ReminderTask("user-a", "开会",
                LocalDateTime.now().plusHours(2), 10, null);
        task.setId(21L);
        Trigger mainTrigger = mock(Trigger.class);
        when(mainTrigger.getNextFireTime()).thenReturn(new Date(System.currentTimeMillis() + 7_200_000));
        when(repository.findByStatusAndTriggerAtBefore(any(String.class), any(LocalDateTime.class)))
                .thenReturn(List.of());
        when(repository.findByStatus(ReminderTask.STATUS_PENDING)).thenReturn(List.of(task));
        org.quartz.JobKey mainKey = new org.quartz.JobKey("reminder-21", "reminders");
        org.quartz.JobKey prewarmKey = new org.quartz.JobKey("prewarm-21", "reminders");
        when(scheduler.checkExists(mainKey)).thenReturn(true);
        doReturn(List.of(mainTrigger)).when(scheduler).getTriggersOfJob(mainKey);
        when(scheduler.checkExists(prewarmKey)).thenReturn(false);

        service(repository, scheduler).recoverPendingReminders();

        verify(scheduler, times(2)).scheduleJob(any(org.quartz.JobDetail.class), any(org.quartz.Trigger.class));
        verify(scheduler).deleteJob(mainKey);
        verify(scheduler).deleteJob(prewarmKey);
    }

    @Test
    void recoveryExpiresOverdueOneShotWithoutSchedulingOrReplayingIt() throws Exception {
        ReminderTaskRepository repository = mock(ReminderTaskRepository.class);
        Scheduler scheduler = mock(Scheduler.class);
        ReminderTask task = new ReminderTask("user-a", "提交材料",
                LocalDateTime.now().minusMinutes(2), 10, null);
        task.setId(22L);
        when(repository.findByStatusAndTriggerAtBefore(any(String.class), any(LocalDateTime.class)))
                .thenReturn(List.of(task));
        when(repository.findByStatus(ReminderTask.STATUS_PENDING)).thenReturn(List.of(task));

        service(repository, scheduler).recoverPendingReminders();

        assertEquals(ReminderTask.STATUS_EXPIRED, task.getStatus());
        verify(repository).save(task);
        verify(scheduler, never()).scheduleJob(any(org.quartz.JobDetail.class), any(org.quartz.Trigger.class));
    }

    @Test
    void expiryDoesNotRaceAnActiveQuartzJob() throws Exception {
        ReminderTaskRepository repository = mock(ReminderTaskRepository.class);
        Scheduler scheduler = mock(Scheduler.class);
        ReminderTask task = new ReminderTask("user-a", "提交材料",
                LocalDateTime.now().minusMinutes(2), 10, null);
        task.setId(23L);
        Trigger mainTrigger = mock(Trigger.class);
        when(mainTrigger.getNextFireTime()).thenReturn(new Date(System.currentTimeMillis() + 30_000));
        when(repository.findByStatusAndTriggerAtBefore(any(String.class), any(LocalDateTime.class)))
                .thenReturn(List.of(task));
        org.quartz.JobKey mainKey = new org.quartz.JobKey("reminder-23", "reminders");
        when(scheduler.checkExists(mainKey)).thenReturn(true);
        doReturn(List.of(mainTrigger)).when(scheduler).getTriggersOfJob(mainKey);

        service(repository, scheduler).expireOverduePending();

        assertEquals(ReminderTask.STATUS_PENDING, task.getStatus());
        verify(repository, never()).save(task);
        verify(scheduler, never()).deleteJob(mainKey);
    }

    @Test
    void oneShotTriggerSkipsAStaleQuartzMisfire() {
        ReminderTaskRepository repository = mock(ReminderTaskRepository.class);
        UserLogService userLogService = mock(UserLogService.class);
        WeChatChannel channel = mock(WeChatChannel.class);
        ReminderTask task = new ReminderTask("user-a", "提交材料",
                LocalDateTime.now().minusMinutes(10), 0, null);
        task.setId(24L);
        when(repository.findById(24L)).thenReturn(Optional.of(task));

        ReminderPushJob job = new ReminderPushJob();
        ReflectionTestUtils.setField(job, "repository", repository);
        ReflectionTestUtils.setField(job, "channels", List.of(channel));
        ReflectionTestUtils.setField(job, "userLogService", userLogService);
        ReflectionTestUtils.setField(job, "userProfileRepository", mock(UserProfileRepository.class));
        JobExecutionContext context = mock(JobExecutionContext.class);
        JobDataMap data = new JobDataMap();
        data.put("reminderId", 24L);
        data.put("mode", "ON_TIME");
        when(context.getMergedJobDataMap()).thenReturn(data);
        when(context.getScheduledFireTime()).thenReturn(new Date(System.currentTimeMillis() - 600_000));
        when(context.getFireTime()).thenReturn(new Date());

        job.executeInternal(context);

        assertEquals(ReminderTask.STATUS_EXPIRED, task.getStatus());
        verify(channel, never()).sendTextFrom(any(), any(), any());
    }

    @Test
    void reminderDoesNotGuessAnotherChannelWhenDeliveryRouteIsUnknown() {
        ReminderTaskRepository repository = mock(ReminderTaskRepository.class);
        UserLogService userLogService = mock(UserLogService.class);
        UserProfileRepository profiles = mock(UserProfileRepository.class);
        WeChatChannel qq = mock(WeChatChannel.class);
        WeChatChannel wechat = mock(WeChatChannel.class);
        ReminderTask task = new ReminderTask("user-a", "开会", LocalDateTime.now().plusHours(1), 0, null);
        task.setId(25L);
        when(repository.findById(25L)).thenReturn(Optional.of(task));
        when(profiles.findById("user-a")).thenReturn(Optional.empty());

        ReminderPushJob job = new ReminderPushJob();
        ReflectionTestUtils.setField(job, "repository", repository);
        ReflectionTestUtils.setField(job, "channels", List.of(qq, wechat));
        ReflectionTestUtils.setField(job, "textService", new ReminderTextService());
        ReflectionTestUtils.setField(job, "userLogService", userLogService);
        ReflectionTestUtils.setField(job, "userProfileRepository", profiles);
        ReflectionTestUtils.setField(job, "timeZoneId", "Asia/Shanghai");
        ReflectionTestUtils.setField(job, "misfireGraceSeconds", 60L);
        JobExecutionContext context = mock(JobExecutionContext.class);
        JobDataMap data = new JobDataMap();
        data.put("reminderId", 25L);
        data.put("mode", "ON_TIME");
        when(context.getMergedJobDataMap()).thenReturn(data);

        job.executeInternal(context);

        verify(qq, never()).sendTextFrom(any(), any(), any());
        verify(wechat, never()).sendTextFrom(any(), any(), any());
        assertEquals(ReminderTask.STATUS_PENDING, task.getStatus());
        verify(repository, never()).save(task);
    }

    @Test
    void reminderUsesOnlyTheRecordedInboundChannel() {
        ReminderTaskRepository repository = mock(ReminderTaskRepository.class);
        UserLogService userLogService = mock(UserLogService.class);
        UserProfileRepository profiles = mock(UserProfileRepository.class);
        WeChatChannel qq = mock(WeChatChannel.class);
        WeChatChannel wechat = mock(WeChatChannel.class);
        ReminderTask task = new ReminderTask("user-a", "开会", LocalDateTime.now().plusHours(1), 0, null);
        task.setId(26L);
        UserProfile profile = new UserProfile("user-a", "persona");
        profile.setLastChannel("qq");
        profile.setLastBotId("bot-a");
        when(repository.findById(26L)).thenReturn(Optional.of(task));
        when(profiles.findById("user-a")).thenReturn(Optional.of(profile));
        when(qq.channel()).thenReturn("qq");

        ReminderPushJob job = new ReminderPushJob();
        ReflectionTestUtils.setField(job, "repository", repository);
        ReflectionTestUtils.setField(job, "channels", List.of(wechat, qq));
        ReflectionTestUtils.setField(job, "textService", new ReminderTextService());
        ReflectionTestUtils.setField(job, "userLogService", userLogService);
        ReflectionTestUtils.setField(job, "userProfileRepository", profiles);
        ReflectionTestUtils.setField(job, "timeZoneId", "Asia/Shanghai");
        ReflectionTestUtils.setField(job, "misfireGraceSeconds", 60L);
        JobExecutionContext context = mock(JobExecutionContext.class);
        JobDataMap data = new JobDataMap();
        data.put("reminderId", 26L);
        data.put("mode", "ON_TIME");
        when(context.getMergedJobDataMap()).thenReturn(data);

        job.executeInternal(context);

        verify(qq).sendTextFrom(eq("bot-a"), eq("user-a"), contains("开会"));
        verify(wechat, never()).sendTextFrom(any(), any(), any());
        assertEquals(ReminderTask.STATUS_COMPLETED, task.getStatus());
    }

    @Test
    void oneShotTriggerUsesNonReplayMisfirePolicy() throws Exception {
        ReminderTaskRepository repository = mock(ReminderTaskRepository.class);
        Scheduler scheduler = mock(Scheduler.class);
        AtomicLong ids = new AtomicLong(24);
        doAnswer(invocation -> {
            ReminderTask task = invocation.getArgument(0);
            if (task.getId() == null) {
                task.setId(ids.incrementAndGet());
            }
            return task;
        }).when(repository).save(any(ReminderTask.class));

        ReminderOperationResult result = service(repository, scheduler).createFromParsedResult(
                new ReminderParseService.ParsedReminder("提交材料", LocalDateTime.now().plusHours(2), 0,
                        null, List.of()), "user-a");

        assertTrue(result.completed());
        ArgumentCaptor<Trigger> captor = ArgumentCaptor.forClass(Trigger.class);
        verify(scheduler).scheduleJob(any(org.quartz.JobDetail.class), captor.capture());
        assertEquals(SimpleTrigger.MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_REMAINING_COUNT,
                captor.getValue().getMisfireInstruction());
    }

    private ReminderService service(ReminderTaskRepository repository, Scheduler scheduler) {
        return new ReminderService(repository, scheduler, new ReminderTextService(), mock(UserLogService.class), 10);
    }
}
