package com.liche.wechatagent.reminder;

import com.liche.wechatagent.channel.ProactiveDelivery;
import com.liche.wechatagent.channel.WeChatChannel;
import com.liche.wechatagent.log.UserLogService;
import com.liche.wechatagent.log.UserScope;
import com.liche.wechatagent.user.UserProfile;
import com.liche.wechatagent.user.UserProfileRepository;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.CronExpression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;

import java.text.ParseException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Map;

/**
 * Quartz 定时推送 Job：从 DB 读取提醒任务，通过微信通道推送（自然口语化文案）。
 * 说明：Quartz 通过 SpringBeanJobFactory 实例化 Job，需要无参构造 + 字段注入。
 */
@Component
public class ReminderPushJob extends QuartzJobBean {

    private static final Logger log = LoggerFactory.getLogger(ReminderPushJob.class);
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired
    private ReminderTaskRepository repository;

    @Autowired
    private java.util.List<WeChatChannel> channels;

    @Autowired
    private ReminderTextService textService;

    @Autowired
    private UserLogService userLogService;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @org.springframework.beans.factory.annotation.Value("${app.time-zone:Asia/Shanghai}")
    private String timeZoneId;

    @org.springframework.beans.factory.annotation.Value("${reminder.misfire-grace-seconds:60}")
    private long misfireGraceSeconds;

    @Override
    protected void executeInternal(JobExecutionContext context) {
        JobDataMap data = context.getMergedJobDataMap();
        Long reminderId = data.getLong("reminderId");
        String mode = data.getString("mode");

        ReminderTask task = repository.findById(reminderId).orElse(null);
        if (task == null || !ReminderTask.STATUS_PENDING.equals(task.getStatus())) {
            return;
        }
        if (task.getTriggerAt() == null) {
            return;
        }
        if ("ON_TIME".equals(mode) && isStaleOneShot(context, task)) {
            task.setStatus(ReminderTask.STATUS_EXPIRED);
            task.setUpdatedAt(LocalDateTime.now(zone()));
            repository.save(task);
            userLogService.record(task.getUserId(), "REMINDER_EXPIRED", Map.of("reminderId", reminderId));
            return;
        }
        if ("PREWARM".equals(mode) && !hasCron(task)
                && (!task.getTriggerAt().isAfter(LocalDateTime.now(zone())) || isLateMisfire(context))) {
            return;
        }
        MDC.put("userScope", UserScope.forUser(task.getUserId()));
        try {
            String text = "PREWARM".equals(mode) ? textService.prewarm(task) : textService.onTime(task);
            boolean sent = sendToRecordedDelivery(task, text);
            if (!sent) {
                log.warn("提醒推送未被通道接受 reminderId={} user={}", reminderId, task.getUserId());
                return;
            }

            if ("ON_TIME".equals(mode)) {
                if (task.getCron() == null || task.getCron().isBlank()) {
                    task.setStatus(ReminderTask.STATUS_COMPLETED);
                } else {
                    updateNextRecurringTime(task);
                }
                task.setUpdatedAt(LocalDateTime.now(zone()));
                repository.save(task);
            }
            userLogService.record(task.getUserId(), "REMINDER_PUSH",
                    Map.of("reminderId", reminderId));
        } finally {
            MDC.remove("userScope");
        }
    }

    /**
     * A reminder is an unsolicited message, so it must use the channel and bot
     * recorded from the user's own inbound message.  Guessing with the first
     * available channel can deliver a private reminder to the wrong platform.
     * 记录过期（例如排障时用过模拟器）时由 ProactiveDelivery 做保守兜底：
     * 只有唯一一个可用通道时才改用它。
     */
    private boolean sendToRecordedDelivery(ReminderTask task, String text) {
        UserProfile profile = userProfileRepository.findById(task.getUserId()).orElse(null);
        if (profile == null) {
            log.warn("提醒缺少用户资料 user={}", task.getUserId());
            return false;
        }
        return ProactiveDelivery.send(channels, profile, task.getUserId(), text);
    }

    private boolean hasCron(ReminderTask task) {
        return task.getCron() != null && !task.getCron().isBlank();
    }

    private boolean isStaleOneShot(JobExecutionContext context, ReminderTask task) {
        if (hasCron(task)) {
            return false;
        }
        Date scheduled = context.getScheduledFireTime();
        Date fired = context.getFireTime();
        if (scheduled != null && fired != null) {
            return fired.getTime() - scheduled.getTime() > misfireGraceSeconds() * 1_000L;
        }
        return !task.getTriggerAt().plusSeconds(misfireGraceSeconds())
                .isAfter(LocalDateTime.now(zone()));
    }

    private boolean isLateMisfire(JobExecutionContext context) {
        Date scheduled = context.getScheduledFireTime();
        Date fired = context.getFireTime();
        return scheduled != null && fired != null
                && fired.getTime() - scheduled.getTime() > misfireGraceSeconds() * 1_000L;
    }

    private void updateNextRecurringTime(ReminderTask task) {
        try {
            String cron = task.getCron().trim();
            if (cron.split("\\s+").length < 6) {
                cron = "0 " + cron;
            }
            CronExpression expression = new CronExpression(cron);
            ZoneId zone = zone();
            // 显式指定时区：容器 JVM 默认是 UTC，不指定会把「每天 8 点」算成当地 16 点
            expression.setTimeZone(java.util.TimeZone.getTimeZone(zone));
            Date next = expression.getNextValidTimeAfter(Date.from(LocalDateTime.now(zone)
                    .atZone(zone).toInstant()));
            if (next != null) {
                task.setTriggerAt(LocalDateTime.ofInstant(next.toInstant(), zone));
            }
        } catch (ParseException | RuntimeException exception) {
            // 保留当前触发时间；Quartz 仍按自身 Cron 继续调度。
        }
    }

    private ZoneId zone() {
        try {
            return ZoneId.of(timeZoneId);
        } catch (RuntimeException ignored) {
            return DEFAULT_ZONE;
        }
    }

    private long misfireGraceSeconds() {
        return Math.max(0, Math.min(3_600, misfireGraceSeconds));
    }
}
