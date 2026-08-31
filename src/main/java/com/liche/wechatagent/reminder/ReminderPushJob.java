package com.liche.wechatagent.reminder;

import com.liche.wechatagent.channel.WeChatChannel;
import com.liche.wechatagent.log.UserLogService;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Quartz 定时推送 Job：从 DB 读取提醒任务，通过微信通道推送（自然口语化文案）。
 * 说明：Quartz 通过 SpringBeanJobFactory 实例化 Job，需要无参构造 + 字段注入。
 */
@Component
public class ReminderPushJob extends QuartzJobBean {

    @Autowired
    private ReminderTaskRepository repository;

    @Autowired
    private java.util.List<WeChatChannel> channels;

    @Autowired
    private ReminderTextService textService;

    @Autowired
    private UserLogService userLogService;

    @Override
    protected void executeInternal(JobExecutionContext context) {
        JobDataMap data = context.getMergedJobDataMap();
        Long reminderId = data.getLong("reminderId");
        String mode = data.getString("mode");

        ReminderTask task = repository.findById(reminderId).orElse(null);
        if (task == null || !ReminderTask.STATUS_PENDING.equals(task.getStatus())) {
            return;
        }
        MDC.put("userId", task.getUserId());
        try {
            String text = "PREWARM".equals(mode) ? textService.prewarm(task) : textService.onTime(task);
            // 按用户归属通道发送（微信或 QQ）
            boolean sent = false;
            for (WeChatChannel c : channels) {
                String botId = c.botIdForUser(task.getUserId());
                if (botId != null) {
                    c.sendTextFrom(botId, task.getUserId(), text);
                    sent = true;
                    break;
                }
            }
            if (!sent && !channels.isEmpty()) {
                channels.get(0).sendTextFrom(null, task.getUserId(), text);
            }

            // 一次性提醒：准时推送后标记完成；重复提醒由 Quartz Cron 继续触发
            if ("ON_TIME".equals(mode) && (task.getCron() == null || task.getCron().isBlank())) {
                task.setStatus(ReminderTask.STATUS_COMPLETED);
                task.setUpdatedAt(LocalDateTime.now());
                repository.save(task);
            }
            userLogService.record(task.getUserId(), "REMINDER_PUSH",
                    "id=" + reminderId + " mode=" + mode + " content=" + task.getContent());
        } finally {
            MDC.remove("userId");
        }
    }
}
