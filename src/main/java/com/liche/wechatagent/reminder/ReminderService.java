package com.liche.wechatagent.reminder;

import com.liche.wechatagent.log.UserLogService;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronExpression;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提醒业务层：校验 → 持久化（MySQL）→ Quartz JDBC 调度（重启自动恢复）。
 * 时间校验：过去的时间直接拒绝；时间描述模糊由解析器返回待澄清项。
 */
@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String GROUP = "reminders";

    private final ReminderTaskRepository repository;
    private final Scheduler scheduler;
    private final ReminderTextService textService;
    private final UserLogService userLogService;
    private final int defaultPrewarmMinutes;
    private final long misfireGraceSeconds;
    private final ZoneId zone;
    private final int maxRecentStatusResults;
    private final Map<String, Object> userLocks = new ConcurrentHashMap<>();

    @Autowired
    public ReminderService(ReminderTaskRepository repository,
                           Scheduler scheduler,
                           ReminderTextService textService,
                           UserLogService userLogService,
                           @Value("${reminder.default-prewarm-minutes:10}") int defaultPrewarmMinutes,
                           @Value("${reminder.misfire-grace-seconds:60}") long misfireGraceSeconds,
                           @Value("${app.time-zone:Asia/Shanghai}") String timeZoneId,
                           @Value("${reminder.max-recent-status-results:12}") int maxRecentStatusResults) {
        this.repository = repository;
        this.scheduler = scheduler;
        this.textService = textService;
        this.userLogService = userLogService;
        this.defaultPrewarmMinutes = Math.max(0, Math.min(24 * 60, defaultPrewarmMinutes));
        this.misfireGraceSeconds = Math.max(0, Math.min(3_600, misfireGraceSeconds));
        this.zone = parseZone(timeZoneId);
        this.maxRecentStatusResults = Math.max(1, Math.min(100, maxRecentStatusResults));
    }

    ReminderService(ReminderTaskRepository repository,
                    Scheduler scheduler,
                    ReminderTextService textService,
                    UserLogService userLogService,
                    int defaultPrewarmMinutes) {
        this(repository, scheduler, textService, userLogService, defaultPrewarmMinutes, 60, "Asia/Shanghai", 12);
    }

    ReminderService(ReminderTaskRepository repository,
                    Scheduler scheduler,
                    ReminderTextService textService,
                    UserLogService userLogService,
                    int defaultPrewarmMinutes,
                    long misfireGraceSeconds,
                    String timeZoneId) {
        this(repository, scheduler, textService, userLogService, defaultPrewarmMinutes,
                misfireGraceSeconds, timeZoneId, 12);
    }

    /** 保留原有字符串接口，供非工具调用方兼容。 */
    public String createFromParsed(ReminderParseService.ParsedReminder parsed, String userId) {
        return createFromParsedResult(parsed, userId).message();
    }

    /** parseReminder 工具的业务落地：校验 → 入库 → 调度。 */
    public ReminderOperationResult createFromParsedResult(ReminderParseService.ParsedReminder parsed, String userId) {
        ReminderOperationResult validation = validateParsed(parsed);
        if (!validation.completed()) {
            return validation;
        }
        requireUserId(userId);
        Object lock = userLocks.computeIfAbsent(userId, ignored -> new Object());
        synchronized (lock) {
            String content = textService.taskContent(parsed.content());
            int prewarmMinutes = parsed.prewarmMinutes() == null ? defaultPrewarmMinutes : parsed.prewarmMinutes();
            ReminderTask duplicate = findPendingDuplicate(userId, content, parsed.triggerAt(), prewarmMinutes,
                    parsed.repeatCron());
            if (duplicate != null) {
                return ReminderOperationResult.completed("这个提醒已经存在：" + textService.created(duplicate));
            }
            ReminderTask task = new ReminderTask(userId, content, parsed.triggerAt(), prewarmMinutes, parsed.repeatCron(),
                    zone, defaultPrewarmMinutes);
            try {
                task = repository.save(task);
                schedule(task);
            } catch (Exception e) {
                cancelPersistedAfterScheduleFailure(task);
                log.error("提醒调度失败 reminderId={}", task.getId(), e);
                return ReminderOperationResult.notCompleted("提醒创建时出了点问题，请稍后再试。");
            }
            userLogService.record(userId, "REMINDER_CREATE",
                    Map.of("reminderId", task.getId(), "triggerAt", task.getTriggerAt()));
            return ReminderOperationResult.completed(textService.created(task));
        }
    }

    private ReminderTask findPendingDuplicate(String userId, String content, LocalDateTime triggerAt,
                                              int prewarmMinutes, String repeatCron) {
        List<ReminderTask> pending = repository.findByUserIdAndStatus(userId, ReminderTask.STATUS_PENDING);
        if (pending == null) {
            return null;
        }
        return pending.stream()
                .filter(task -> task != null
                        && Objects.equals(task.getContent(), content)
                        && Objects.equals(task.getTriggerAt(), triggerAt)
                        && Objects.equals(task.getPrewarmMinutes(), prewarmMinutes)
                        && Objects.equals(normalizeCronValue(task.getCron()), normalizeCronValue(repeatCron)))
                .findFirst()
                .orElse(null);
    }

    private String normalizeCronValue(String cron) {
        return cron == null || cron.isBlank() ? null : cron.trim();
    }

    private void requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalStateException("当前用户上下文不存在");
        }
    }

    private ReminderOperationResult validateParsed(ReminderParseService.ParsedReminder parsed) {
        if (parsed == null) {
            return ReminderOperationResult.notCompleted("我没听清要提醒你什么事，再说一遍？");
        }
        if (parsed.missing() != null && !parsed.missing().isEmpty()) {
            return ReminderOperationResult.notCompleted("我需要确认一下：" + String.join("；", parsed.missing())
                    + "（请补充具体事项和时间）");
        }
        if (parsed.content() == null || parsed.content().isBlank()) {
            return ReminderOperationResult.notCompleted("我没听清要提醒你什么事，再说一遍？");
        }
        if (parsed.triggerAt() == null) {
            return ReminderOperationResult.notCompleted("我还不知道具体在什么时候提醒你，告诉我个时间？");
        }
        if (!parsed.triggerAt().isAfter(now())) {
            return ReminderOperationResult.notCompleted("这个时间已经过去了，换个未来的时间试试？");
        }
        return ReminderOperationResult.completed("");
    }

    private void cancelPersistedAfterScheduleFailure(ReminderTask task) {
        if (task == null || task.getId() == null) {
            return;
        }
        deleteQuartzJobsQuietly(task.getId());
        task.setStatus(ReminderTask.STATUS_CANCELLED);
        task.setUpdatedAt(now());
        try {
            repository.save(task);
        } catch (RuntimeException saveException) {
            log.error("提醒调度失败后的状态回写也失败 reminderId={}", task.getId(), saveException);
        }
    }

    /** 原子替换：新提醒完全落地后才取消旧提醒。 */
    public ReminderOperationResult replaceFromParsed(ReminderParseService.ParsedReminder parsed,
                                                      String userId, Long reminderId) {
        requireUserId(userId);
        ReminderOperationResult validation = validateParsed(parsed);
        if (!validation.completed()) {
            return validation;
        }
        return withUserLock(userId, () -> replaceFromParsedLocked(parsed, userId, reminderId));
    }

    private ReminderOperationResult replaceFromParsedLocked(ReminderParseService.ParsedReminder parsed,
                                                             String userId, Long reminderId) {
        if (reminderId == null) {
            return ReminderOperationResult.notCompleted("还没有要调整的提醒 ID，请先查看当前提醒。");
        }
        ReminderTask oldTask = repository.findByIdAndUserId(reminderId, userId).orElse(null);
        if (oldTask == null) {
            return ReminderOperationResult.notCompleted("没有找到这个提醒，确认一下 ID？");
        }
        if (!ReminderTask.STATUS_PENDING.equals(oldTask.getStatus())) {
            return ReminderOperationResult.notCompleted("这个提醒已经结束了，不能再调整。");
        }

        ReminderTask replacement = new ReminderTask(userId, textService.taskContent(parsed.content()), parsed.triggerAt(),
                parsed.prewarmMinutes() == null ? oldTask.getPrewarmMinutes() : parsed.prewarmMinutes(),
                parsed.repeatCron(), zone, defaultPrewarmMinutes);
        try {
            replacement = repository.save(replacement);
            schedule(replacement);
        } catch (Exception exception) {
            cancelPersistedAfterScheduleFailure(replacement);
            log.error("替换提醒的新任务创建失败 oldReminderId={}", reminderId, exception);
            return ReminderOperationResult.notCompleted("新的提醒没有创建成功，原来的提醒仍保留。");
        }

        try {
            cancelTaskStrict(oldTask);
        } catch (Exception exception) {
            deleteQuartzJobsQuietly(replacement.getId());
            replacement.setStatus(ReminderTask.STATUS_CANCELLED);
            replacement.setUpdatedAt(now());
            repository.save(replacement);
            log.error("替换提醒时旧任务取消失败 oldReminderId={} newReminderId={}", reminderId,
                    replacement.getId(), exception);
            return ReminderOperationResult.notCompleted("新提醒已暂存但旧提醒未能安全替换，原来的提醒仍保留。");
        }

        userLogService.record(userId, "REMINDER_REPLACE",
                Map.of("oldReminderId", reminderId, "newReminderId", replacement.getId(),
                        "triggerAt", replacement.getTriggerAt()));
        return ReminderOperationResult.completed("好的，已将提醒改为：" + textService.created(replacement));
    }

    private void schedule(ReminderTask task) throws SchedulerException {
        List<JobKey> createdJobs = new ArrayList<>();
        try {
            JobDetail job = JobBuilder.newJob(ReminderPushJob.class)
                    .withIdentity("reminder-" + task.getId(), GROUP)
                    .usingJobData("reminderId", task.getId())
                    .usingJobData("mode", "ON_TIME")
                    .build();
            createdJobs.add(job.getKey());
            Date startAt = Date.from(task.getTriggerAt().atZone(zone).toInstant());
            if (hasCron(task)) {
                CronTrigger trigger = TriggerBuilder.newTrigger()
                        .withIdentity("trigger-" + task.getId(), GROUP)
                        .startAt(startAt)
                        .withSchedule(CronScheduleBuilder.cronSchedule(normalizeCron(task.getCron()))
                                .inTimeZone(java.util.TimeZone.getTimeZone(zone))
                                .withMisfireHandlingInstructionDoNothing())
                        .forJob(job)
                        .build();
                scheduler.scheduleJob(job, trigger);
            } else {
                Trigger trigger = TriggerBuilder.newTrigger()
                        .withIdentity("trigger-" + task.getId(), GROUP)
                        .startAt(startAt)
                        .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                                .withMisfireHandlingInstructionNextWithRemainingCount())
                        .forJob(job)
                        .build();
                scheduler.scheduleJob(job, trigger);

                // 提前预热提醒（默认提前 10 分钟）
                if (task.getPrewarmMinutes() != null && task.getPrewarmMinutes() > 0) {
                    LocalDateTime prewarmAt = task.getTriggerAt().minusMinutes(task.getPrewarmMinutes());
                    if (prewarmAt.isAfter(now())) {
                        JobDetail prewarmJob = JobBuilder.newJob(ReminderPushJob.class)
                                .withIdentity("prewarm-" + task.getId(), GROUP)
                                .usingJobData("reminderId", task.getId())
                                .usingJobData("mode", "PREWARM")
                                .build();
                        createdJobs.add(prewarmJob.getKey());
                        Trigger prewarmTrigger = TriggerBuilder.newTrigger()
                                .withIdentity("prewarm-trigger-" + task.getId(), GROUP)
                                .startAt(Date.from(prewarmAt.atZone(zone).toInstant()))
                                .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                                        .withMisfireHandlingInstructionNextWithRemainingCount())
                                .forJob(prewarmJob)
                                .build();
                        scheduler.scheduleJob(prewarmJob, prewarmTrigger);
                    }
                }
            }
        } catch (SchedulerException exception) {
            for (JobKey key : createdJobs) {
                try {
                    scheduler.deleteJob(key);
                } catch (SchedulerException cleanupException) {
                    log.warn("清理半成品 Quartz 任务失败 job={}", key, cleanupException);
                }
            }
            throw exception;
        } catch (RuntimeException exception) {
            for (JobKey key : createdJobs) {
                try {
                    scheduler.deleteJob(key);
                } catch (SchedulerException cleanupException) {
                    log.warn("清理半成品 Quartz 任务失败 job={}", key, cleanupException);
                }
            }
            throw exception;
        }
    }

    /** 兼容 5 段 Cron（缺秒位）：Quartz 需要 6 段（秒 分 时 日 月 周），缺秒位时补 "0 " */
    private String normalizeCron(String cron) {
        String c = cron == null ? "" : cron.trim();
        int segments = c.isEmpty() ? 0 : c.split("\\s+").length;
        return segments < 6 ? "0 " + c : c;
    }

    /** 取消提醒：删除 Quartz 任务（准时 + 预热）+ 标记 CANCELLED */
    public String cancel(String userId, Long reminderId) {
        return cancelResult(userId, reminderId).message();
    }

    public ReminderOperationResult cancelResult(String userId, Long reminderId) {
        requireUserId(userId);
        return withUserLock(userId, () -> cancelResultLocked(userId, reminderId));
    }

    private ReminderOperationResult cancelResultLocked(String userId, Long reminderId) {
        if (reminderId == null || reminderId <= 0) {
            return ReminderOperationResult.notCompleted("提醒 ID 无效，请先查看当前提醒。");
        }
        ReminderTask task = repository.findByIdAndUserId(reminderId, userId)
                .orElse(null);
        if (task == null) {
            return ReminderOperationResult.notCompleted("没有找到这个提醒，确认一下 ID？");
        }
        if (!ReminderTask.STATUS_PENDING.equals(task.getStatus())) {
            return ReminderOperationResult.notCompleted("这个提醒已经结束了，不用取消。");
        }
        try {
            cancelTaskStrict(task);
        } catch (Exception exception) {
            log.error("取消提醒失败 reminderId={}", reminderId, exception);
            return ReminderOperationResult.notCompleted("取消提醒没有完成，原提醒仍保留。");
        }
        return ReminderOperationResult.completed("好的，已取消这个提醒：「" + task.getContent() + "」");
    }

    private <T> T withUserLock(String userId, java.util.function.Supplier<T> operation) {
        Object lock = userLocks.computeIfAbsent(userId, ignored -> new Object());
        synchronized (lock) {
            return operation.get();
        }
    }

    private void cancelTaskStrict(ReminderTask task) throws SchedulerException {
        deleteQuartzJobs(task.getId());
        task.setStatus(ReminderTask.STATUS_CANCELLED);
        task.setUpdatedAt(now());
        repository.save(task);
        userLogService.record(task.getUserId(), "REMINDER_CANCEL", Map.of("reminderId", task.getId()));
    }

    private void deleteQuartzJobs(Long reminderId) throws SchedulerException {
        scheduler.deleteJob(new JobKey("reminder-" + reminderId, GROUP));
        scheduler.deleteJob(new JobKey("prewarm-" + reminderId, GROUP));
    }

    public String listPendingText(String userId) {
        requireUserId(userId);
        expireOverduePending();
        List<ReminderTask> list = repository.findByUserIdAndStatusOrderByTriggerAtAsc(userId, ReminderTask.STATUS_PENDING)
                .stream()
                .filter(this::isVisiblePending)
                .toList();
        if (list.isEmpty()) {
            return "目前没有待执行的提醒。";
        }
        StringBuilder sb = new StringBuilder("你当前的待执行提醒：\n");
        for (ReminderTask t : list) {
            sb.append(t.getId()).append(". ").append(textService.displayContent(t.getContent()))
                    .append(" — ").append(textService.absoluteTime(t.getTriggerAt()))
                    .append(t.getCron() != null && !t.getCron().isBlank() ? "（重复）" : "")
                    .append("\n");
        }
        return sb.toString().trim();
    }

    public String listRecentStatusText(String userId) {
        requireUserId(userId);
        expireOverduePending();
        List<ReminderTask> tasks = repository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .limit(maxRecentStatusResults)
                .toList();
        if (tasks.isEmpty()) {
            return "目前没有找到提醒记录。";
        }
        StringBuilder result = new StringBuilder("最近的提醒状态：\n");
        for (ReminderTask task : tasks) {
            result.append(task.getId()).append(". ")
                    .append(textService.displayContent(task.getContent())).append(" — ")
                    .append(textService.absoluteTime(task.getTriggerAt()))
                    .append("，状态：").append(statusText(task.getStatus())).append('\n');
        }
        return result.toString().trim();
    }

    private String statusText(String status) {
        return switch (status) {
            case ReminderTask.STATUS_COMPLETED -> "已推送";
            case ReminderTask.STATUS_CANCELLED -> "已取消";
            case ReminderTask.STATUS_EXPIRED -> "已过期，未补发";
            default -> "待执行";
        };
    }

    private boolean isVisiblePending(ReminderTask task) {
        return task.getTriggerAt() != null
                && (hasCron(task) || task.getTriggerAt().isAfter(now()));
    }

    private boolean hasCron(ReminderTask task) {
        return task.getCron() != null && !task.getCron().isBlank();
    }

    /** 将错过的一次性任务标记过期，避免列表继续显示或重启后补发。 */
    public void expireOverduePending() {
        LocalDateTime current = now();
        LocalDateTime cutoff = current.minusSeconds(misfireGraceSeconds);
        List<ReminderTask> overdue = repository.findByStatusAndTriggerAtBefore(ReminderTask.STATUS_PENDING, cutoff);
        if (overdue == null) {
            return;
        }
        for (ReminderTask task : overdue) {
            if (task == null || task.getId() == null || task.getTriggerAt() == null || hasCron(task)) {
                continue;
            }
            withUserLock(task.getUserId(), () -> {
                ReminderTask currentTask = repository.findByIdAndUserId(task.getId(), task.getUserId()).orElse(task);
                if (currentTask == null || !ReminderTask.STATUS_PENDING.equals(currentTask.getStatus())
                        || currentTask.getTriggerAt() == null || hasCron(currentTask)
                        || currentTask.getTriggerAt().isAfter(current.minusSeconds(misfireGraceSeconds))
                        || hasActiveQuartzJobQuietly(currentTask.getId())) {
                    return null;
                }
                currentTask.setStatus(ReminderTask.STATUS_EXPIRED);
                currentTask.setUpdatedAt(current);
                deleteQuartzJobsQuietly(currentTask.getId());
                repository.save(currentTask);
                log.info("提醒已标记过期 reminderId={} triggerAt={}", currentTask.getId(), currentTask.getTriggerAt());
                return null;
            });
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverPendingReminders() {
        expireOverduePending();
        List<ReminderTask> pending = repository.findByStatus(ReminderTask.STATUS_PENDING);
        if (pending == null) {
            return;
        }
        LocalDateTime current = now();
        for (ReminderTask task : pending) {
            if (task == null || task.getId() == null) {
                continue;
            }
            recoverPendingReminder(task, current);
        }
    }

    private void recoverPendingReminder(ReminderTask task, LocalDateTime current) {
        withUserLock(task.getUserId(), () -> {
            ReminderTask currentTask = repository.findByIdAndUserId(task.getId(), task.getUserId()).orElse(task);
            if (currentTask == null || !ReminderTask.STATUS_PENDING.equals(currentTask.getStatus())) {
                return null;
            }
            try {
                if (hasCron(currentTask) && (currentTask.getTriggerAt() == null || !currentTask.getTriggerAt().isAfter(current))) {
                    updateNextRecurringTime(currentTask);
                }
                if (currentTask.getTriggerAt() == null || (!hasCron(currentTask) && !currentTask.getTriggerAt().isAfter(current))) {
                    return null;
                }
                if (!hasRequiredQuartzJobs(currentTask)) {
                    deleteQuartzJobs(currentTask.getId());
                    schedule(currentTask);
                    log.info("提醒调度已恢复 reminderId={} triggerAt={}", currentTask.getId(), currentTask.getTriggerAt());
                }
            } catch (Exception exception) {
                log.error("恢复提醒调度失败 reminderId={}", currentTask.getId(), exception);
            }
            return null;
        });
    }

    @Scheduled(fixedDelayString = "${reminder.recovery-scan-interval-ms:300000}",
            initialDelayString = "${reminder.recovery-initial-delay-ms:120000}")
    public void scanPendingReminders() {
        recoverPendingReminders();
    }

    private void deleteQuartzJobsQuietly(Long reminderId) {
        if (reminderId == null) {
            return;
        }
        try {
            deleteQuartzJobs(reminderId);
        } catch (Exception exception) {
            log.warn("清理过期提醒 Quartz 任务失败 reminderId={}", reminderId, exception);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(zone);
    }

    private boolean hasRequiredQuartzJobs(ReminderTask task) throws SchedulerException {
        if (!hasActiveQuartzJob(task.getId())) {
            return false;
        }
        if (hasCron(task) || task.getPrewarmMinutes() == null || task.getPrewarmMinutes() <= 0) {
            return true;
        }
        LocalDateTime prewarmAt = task.getTriggerAt().minusMinutes(task.getPrewarmMinutes());
        return !prewarmAt.isAfter(now()) || hasActiveQuartzJob("prewarm-" + task.getId());
    }

    private boolean hasActiveQuartzJob(Long reminderId) throws SchedulerException {
        if (reminderId == null) {
            return false;
        }
        return hasActiveQuartzJob("reminder-" + reminderId);
    }

    private boolean hasActiveQuartzJob(String jobName) throws SchedulerException {
        JobKey key = new JobKey(jobName, GROUP);
        if (!scheduler.checkExists(key)) {
            return false;
        }
        List<? extends Trigger> triggers = scheduler.getTriggersOfJob(key);
        return triggers != null && triggers.stream().anyMatch(trigger -> trigger.getNextFireTime() != null);
    }

    private boolean hasActiveQuartzJobQuietly(Long reminderId) {
        try {
            return hasActiveQuartzJob(reminderId);
        } catch (Exception exception) {
            log.warn("查询提醒 Quartz 状态失败 reminderId={}", reminderId, exception);
            return true;
        }
    }

    private void updateNextRecurringTime(ReminderTask task) {
        try {
            CronExpression expression = new CronExpression(normalizeCron(task.getCron()));
            Date next = expression.getNextValidTimeAfter(Date.from(now().atZone(zone).toInstant()));
            if (next != null) {
                task.setTriggerAt(LocalDateTime.ofInstant(next.toInstant(), zone));
                task.setUpdatedAt(now());
                repository.save(task);
            }
        } catch (ParseException | RuntimeException exception) {
            log.warn("无法计算重复提醒下一次时间 reminderId={}", task.getId(), exception);
        }
    }

    private ZoneId parseZone(String value) {
        try {
            return ZoneId.of(value);
        } catch (RuntimeException ignored) {
            return DEFAULT_ZONE;
        }
    }
}
