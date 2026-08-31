package com.liche.wechatagent.reminder;

import com.liche.wechatagent.exception.BizException;
import com.liche.wechatagent.log.UserLogService;
import org.quartz.CronScheduleBuilder;
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
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

/**
 * 提醒业务层：校验 → 持久化（MySQL）→ Quartz JDBC 调度（重启自动恢复）。
 * 时间校验：过去的时间直接拒绝；时间描述模糊由解析器返回待澄清项。
 */
@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String GROUP = "reminders";

    private final ReminderTaskRepository repository;
    private final Scheduler scheduler;
    private final ReminderTextService textService;
    private final UserLogService userLogService;
    private final int defaultPrewarmMinutes;

    public ReminderService(ReminderTaskRepository repository,
                           Scheduler scheduler,
                           ReminderTextService textService,
                           UserLogService userLogService,
                           @Value("${reminder.default-prewarm-minutes:10}") int defaultPrewarmMinutes) {
        this.repository = repository;
        this.scheduler = scheduler;
        this.textService = textService;
        this.userLogService = userLogService;
        this.defaultPrewarmMinutes = defaultPrewarmMinutes;
    }

    /** parseReminder 工具的业务落地：校验 → 入库 → 调度 */
    public String createFromParsed(ReminderParseService.ParsedReminder parsed, String userId) {
        if (parsed.missing() != null && !parsed.missing().isEmpty()) {
            return "我需要确认一下：" + String.join("；", parsed.missing())
                    + "（也可以直接说完整，比如：提醒我明天上午10点给老板发周报）";
        }
        if (parsed.content() == null || parsed.content().isBlank()) {
            return "我没听清要提醒你什么事，再说一遍？";
        }
        if (parsed.triggerAt() == null) {
            return "我还不知道具体在什么时候提醒你，告诉我个时间？";
        }
        if (parsed.triggerAt().isBefore(LocalDateTime.now())) {
            return "这个时间已经过去了，换个未来的时间试试？";
        }
        int prewarm = parsed.prewarmMinutes() == null ? defaultPrewarmMinutes : parsed.prewarmMinutes();
        ReminderTask task = repository.save(new ReminderTask(userId, parsed.content(), parsed.triggerAt(),
                prewarm, parsed.repeatCron()));
        try {
            schedule(task);
        } catch (Exception e) {
            task.setStatus(ReminderTask.STATUS_CANCELLED);
            task.setUpdatedAt(LocalDateTime.now());
            repository.save(task);
            log.error("提醒调度失败 reminderId={}", task.getId(), e);
            return "提醒创建时出了点问题，请稍后再试。";
        }
        userLogService.record(userId, "REMINDER_CREATE",
                "id=" + task.getId() + " content=" + task.getContent() + " at=" + task.getTriggerAt());
        return textService.created(task);
    }

    private void schedule(ReminderTask task) throws SchedulerException {
        JobDetail job = JobBuilder.newJob(ReminderPushJob.class)
                .withIdentity("reminder-" + task.getId(), GROUP)
                .usingJobData("reminderId", task.getId())
                .usingJobData("mode", "ON_TIME")
                .build();

        if (task.getCron() != null && !task.getCron().isBlank()) {
            Date startAt = Date.from(task.getTriggerAt().atZone(ZONE).toInstant());
            CronTrigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity("trigger-" + task.getId(), GROUP)
                    .startAt(startAt)
                    .withSchedule(CronScheduleBuilder.cronSchedule(normalizeCron(task.getCron()))
                            .withMisfireHandlingInstructionFireAndProceed())
                    .forJob(job)
                    .build();
            scheduler.scheduleJob(job, trigger);
        } else {
            Date startAt = Date.from(task.getTriggerAt().atZone(ZONE).toInstant());
            Trigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity("trigger-" + task.getId(), GROUP)
                    .startAt(startAt)
                    .withSchedule(SimpleScheduleBuilder.simpleSchedule().withMisfireHandlingInstructionFireNow())
                    .forJob(job)
                    .build();
            scheduler.scheduleJob(job, trigger);

            // 提前预热提醒（默认提前 10 分钟）
            if (task.getPrewarmMinutes() != null && task.getPrewarmMinutes() > 0) {
                LocalDateTime prewarmAt = task.getTriggerAt().minusMinutes(task.getPrewarmMinutes());
                if (prewarmAt.isAfter(LocalDateTime.now())) {
                    JobDetail prewarmJob = JobBuilder.newJob(ReminderPushJob.class)
                            .withIdentity("prewarm-" + task.getId(), GROUP)
                            .usingJobData("reminderId", task.getId())
                            .usingJobData("mode", "PREWARM")
                            .build();
                    Trigger prewarmTrigger = TriggerBuilder.newTrigger()
                            .withIdentity("prewarm-trigger-" + task.getId(), GROUP)
                            .startAt(Date.from(prewarmAt.atZone(ZONE).toInstant()))
                            .withSchedule(SimpleScheduleBuilder.simpleSchedule().withMisfireHandlingInstructionFireNow())
                            .forJob(prewarmJob)
                            .build();
                    scheduler.scheduleJob(prewarmJob, prewarmTrigger);
                }
            }
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
        ReminderTask task = repository.findByIdAndUserId(reminderId, userId)
                .orElseThrow(() -> new BizException("没有找到这个提醒，确认一下 ID？"));
        if (!ReminderTask.STATUS_PENDING.equals(task.getStatus())) {
            return "这个提醒已经结束了，不用取消。";
        }
        try {
            scheduler.deleteJob(new JobKey("reminder-" + task.getId(), GROUP));
            scheduler.deleteJob(new JobKey("prewarm-" + task.getId(), GROUP));
        } catch (SchedulerException e) {
            log.warn("删除Quartz任务失败 reminderId={}", reminderId, e);
        }
        task.setStatus(ReminderTask.STATUS_CANCELLED);
        task.setUpdatedAt(LocalDateTime.now());
        repository.save(task);
        userLogService.record(userId, "REMINDER_CANCEL", "id=" + reminderId);
        return "好的，已取消这个提醒：「" + task.getContent() + "」";
    }

    public String listPendingText(String userId) {
        List<ReminderTask> list = repository.findByUserIdAndStatusOrderByTriggerAtAsc(userId, ReminderTask.STATUS_PENDING);
        if (list.isEmpty()) {
            return "目前没有待执行的提醒。";
        }
        StringBuilder sb = new StringBuilder("你当前的待执行提醒：\n");
        for (ReminderTask t : list) {
            sb.append(t.getId()).append(". ").append(t.getContent())
                    .append(" — ").append(t.getTriggerAt().format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")))
                    .append(t.getCron() != null && !t.getCron().isBlank() ? "（重复）" : "")
                    .append("\n");
        }
        return sb.toString().trim();
    }
}
