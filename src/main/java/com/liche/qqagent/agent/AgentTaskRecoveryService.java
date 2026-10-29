package com.liche.qqagent.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Automatically requeues only explicitly safe, text-only tasks interrupted by a restart. */
@Component
public class AgentTaskRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(AgentTaskRecoveryService.class);
    private final AgentTaskStateStore store;
    private final AgentOrchestrator orchestrator;
    private final boolean enabled;

    public AgentTaskRecoveryService(AgentTaskStateStore store, AgentOrchestrator orchestrator,
                                    @Value("${agent.task-auto-recovery-enabled:false}") boolean enabled) {
        this.store = store;
        this.orchestrator = orchestrator;
        this.enabled = enabled;
    }

    /**
     * 清理"卡住"的 UNKNOWN_RESULT 任务状态（2026-10-30）。
     *
     * <p>这类状态是"消息发出去后没收到平台确认"留下的，**永远不会自己转成成功或失败**。
     * 后果是面板顶上一直挂红字、整体状态一直 DEGRADED——用户看到的是"系统其实好好的"。
     * 原来只能手动点「清掉」；现在启动时自动清理超过保留期的记录，
     * 让"红着但没人管"这件事不再持续。
     *
     * @param retentionHours 保留多少小时；<=0 表示不自动清理
     */
    @Value("${agent.task-unknown-retention-hours:24}")
    private long unknownRetentionHours = 24;

    @EventListener(ApplicationReadyEvent.class)
    public void purgeStaleUnknownResults() {
        if (unknownRetentionHours <= 0) {
            return;
        }
        java.time.Instant cutoff = java.time.Instant.now().minusSeconds(unknownRetentionHours * 3600);
        int purged = 0;
        for (String taskId : store.findByStatus("UNKNOWN_RESULT")) {
            String updated = String.valueOf(store.find(taskId).getOrDefault("updatedAt", ""));
            try {
                if (!updated.isBlank() && java.time.Instant.parse(updated).isBefore(cutoff)) {
                    store.clear(taskId);
                    purged++;
                }
            } catch (RuntimeException exception) {
                log.debug("跳过无法解析时间的任务状态 taskId={} updatedAt={}", taskId, updated);
            }
        }
        if (purged > 0) {
            log.info("清理过期 UNKNOWN_RESULT 任务状态 count={}", purged);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        if (!enabled) return;
        int recovered = 0;
        for (String taskId : store.findByStatus("UNKNOWN_RESULT")) {
            if (orchestrator.retryTask(taskId)) recovered++;
        }
        if (recovered > 0) log.info("agent task safe recovery queued count={}", recovered);
    }
}
