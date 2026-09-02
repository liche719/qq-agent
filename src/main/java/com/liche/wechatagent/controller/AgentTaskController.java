package com.liche.wechatagent.controller;

import com.liche.wechatagent.agent.AgentTaskStateStore;
import com.liche.wechatagent.agent.AgentOrchestrator;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Read-only task observability; never exposes attachment URLs or message bodies beyond the bounded envelope. */
@RestController
@RequestMapping("/api/agent/tasks")
public class AgentTaskController {
    private final AgentTaskStateStore store;
    private final AgentOrchestrator orchestrator;

    public AgentTaskController(AgentTaskStateStore store, AgentOrchestrator orchestrator) {
        this.store = store;
        this.orchestrator = orchestrator;
    }

    @PostMapping("/{taskId}/retry")
    public ResponseEntity<Map<String, Object>> retry(@PathVariable String taskId) {
        boolean accepted = orchestrator.retryTask(taskId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accepted", accepted);
        body.put("message", accepted ? "已安全重新排队；只重试文本快照任务。" : "任务不可重试：可能已有副作用、缺少安全快照或已被重试。");
        return accepted ? ResponseEntity.accepted().body(body) : ResponseEntity.badRequest().body(body);
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<Map<Object, Object>> task(@PathVariable String taskId) {
        Map<Object, Object> state = store.find(taskId);
        return state.isEmpty() ? ResponseEntity.notFound().build() : ResponseEntity.ok(state);
    }

    @GetMapping("/unknown")
    public Map<String, Object> unknown() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UNKNOWN_RESULT");
        result.put("taskIds", store.findByStatus("UNKNOWN_RESULT"));
        result.put("message", "这些任务在服务重启时无法确认是否产生副作用，请根据原消息人工确认后再重试。");
        return result;
    }
}
