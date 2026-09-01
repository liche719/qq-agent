package com.liche.wechatagent.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** Durable, user-scoped lifecycle state for asynchronous agent executions. */
@Component
public class AgentTaskStateStore {
    private final StringRedisTemplate redis;
    private final Duration ttl;

    public AgentTaskStateStore(StringRedisTemplate redis,
                               @Value("${agent.task-state-ttl-hours:168}") long ttlHours) {
        this.redis = redis;
        this.ttl = Duration.ofHours(Math.max(1, Math.min(24 * 30, ttlHours)));
    }

    public void start(String taskId, String userId, String replyToMessageId) {
        save(taskId, Map.of("status", "RUNNING", "userId", safe(userId),
                "replyToMessageId", safe(replyToMessageId), "startedAt", Instant.now().toString()));
    }

    public void finish(String taskId, String status, String userId, String replyToMessageId) {
        save(taskId, Map.of("status", safe(status), "userId", safe(userId),
                "replyToMessageId", safe(replyToMessageId)));
    }

    public void step(String taskId, String step) {
        if (taskId == null || taskId.isBlank() || step == null || step.isBlank()) return;
        save(taskId, Map.of("currentStep", step));
    }

    public void fail(String taskId, String userId, String replyToMessageId, String reason) {
        save(taskId, Map.of("status", "FAILED", "userId", safe(userId),
                "replyToMessageId", safe(replyToMessageId), "failureReason", safe(reason)));
    }

    private void save(String taskId, Map<String, String> values) {
        if (taskId == null || taskId.isBlank()) return;
        try {
            String key = "agent:task:" + taskId;
            var updated = new java.util.HashMap<>(values);
            updated.put("updatedAt", Instant.now().toString());
            redis.opsForHash().putAll(key, updated);
            redis.opsForSet().add("agent:tasks:index", taskId);
            redis.expire(key, ttl);
        } catch (RuntimeException ignored) {
            // Redis availability must not prevent the live reply path.
        }
    }

    public Map<Object, Object> find(String taskId) {
        if (taskId == null || taskId.isBlank()) return Map.of();
        try {
            return redis.opsForHash().entries("agent:task:" + taskId);
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    @PostConstruct
    public void markInterruptedTasks() {
        try {
            var taskIds = redis.opsForSet().members("agent:tasks:index");
            if (taskIds == null) return;
            for (String taskId : taskIds) {
                String taskKey = "agent:task:" + taskId;
                Object status = redis.opsForHash().get(taskKey, "status");
                if (status == null) {
                    redis.opsForSet().remove("agent:tasks:index", taskId);
                    continue;
                }
                if ("RUNNING".equals(status)) {
                    redis.opsForHash().put(taskKey, "status", "UNKNOWN_RESULT");
                    redis.opsForHash().put(taskKey, "failureReason", "服务重启时任务尚未完成，无法安全判断是否已产生副作用");
                }
            }
        } catch (RuntimeException ignored) {
            // Recovery is best effort; normal startup must remain available without Redis.
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
