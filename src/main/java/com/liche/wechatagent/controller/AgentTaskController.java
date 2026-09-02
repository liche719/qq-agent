package com.liche.wechatagent.controller;

import com.liche.wechatagent.agent.AgentTaskStateStore;
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

    public AgentTaskController(AgentTaskStateStore store) {
        this.store = store;
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
