package com.liche.wechatagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration
public class AgentConfig {

    @Bean(name = "taskScheduler")
    public TaskScheduler taskScheduler(@Value("${scheduler.task-pool-size:2}") int poolSize,
                                       @Value("${scheduler.thread-name-prefix:scheduled-task-}") String threadNamePrefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(1, Math.min(32, poolSize)));
        scheduler.setThreadNamePrefix(threadNamePrefix == null || threadNamePrefix.isBlank()
                ? "scheduled-task-" : threadNamePrefix);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }

    /** 记忆提取专用调度线程（3 秒静默窗口） */
    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService memoryExtractionThreadPool(
            @Value("${memory.extraction-thread-pool-size:2}") int poolSize,
            @Value("${memory.extraction-thread-name-prefix:memory-extract-}") String threadNamePrefix) {
        return Executors.newScheduledThreadPool(Math.max(1, Math.min(16, poolSize)), r -> {
            Thread t = new Thread(r, threadNamePrefix == null || threadNamePrefix.isBlank()
                    ? "memory-extract-" : threadNamePrefix);
            t.setDaemon(true);
            return t;
        });
    }

    @Bean(name = "messageBatchScheduler", destroyMethod = "shutdownNow")
    public ScheduledExecutorService messageBatchScheduler(
            @Value("${agent.message-batch-thread-name-prefix:message-batch-}") String threadNamePrefix) {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, threadNamePrefix == null || threadNamePrefix.isBlank()
                    ? "message-batch-" : threadNamePrefix);
            t.setDaemon(true);
            return t;
        });
    }
}
