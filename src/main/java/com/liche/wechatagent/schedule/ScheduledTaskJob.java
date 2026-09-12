package com.liche.wechatagent.schedule;

import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.springframework.stereotype.Component;

/**
 * 定时任务的 Quartz 入口：到点后用任务里的指令跑一遍完整 Agent 链路，
 * 再把结果推给用户（执行与投递逻辑都在 {@link ScheduledTaskService}，这里只负责触发）。
 *
 * <p>Quartz 通过 SpringBeanJobFactory 实例化 Job，所以用无参构造 + 字段注入。
 */
@Component
public class ScheduledTaskJob extends QuartzJobBean {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskJob.class);

    @Autowired
    private ScheduledTaskRepository repository;

    @Autowired
    private ScheduledTaskService service;

    @Override
    protected void executeInternal(JobExecutionContext context) {
        JobDataMap data = context.getMergedJobDataMap();
        Long taskId = data.getLong("taskId");
        ScheduledTask task = repository.findById(taskId).orElse(null);
        if (task == null) {
            return;
        }
        if (!Boolean.TRUE.equals(task.getEnabled())) {
            return;
        }
        try {
            service.execute(task, false);
        } catch (RuntimeException exception) {
            log.warn("定时任务执行异常 id={}: {}", taskId, exception.toString());
        }
    }
}
