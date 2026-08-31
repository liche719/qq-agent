package com.liche.wechatagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration
public class AgentConfig {

    @Bean(name = "taskScheduler")
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("scheduled-task-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }

    /** 记忆提取专用调度线程（3 秒静默窗口） */
    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService memoryExtractionThreadPool() {
        return Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "memory-extract");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean(name = "messageBatchScheduler", destroyMethod = "shutdownNow")
    public ScheduledExecutorService messageBatchScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "message-batch");
            t.setDaemon(true);
            return t;
        });
    }
}
