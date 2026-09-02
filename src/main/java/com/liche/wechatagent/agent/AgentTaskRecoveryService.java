package com.liche.wechatagent.agent;

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
                                    @Value("${agent.task-auto-recovery-enabled:true}") boolean enabled) {
        this.store = store;
        this.orchestrator = orchestrator;
        this.enabled = enabled;
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
