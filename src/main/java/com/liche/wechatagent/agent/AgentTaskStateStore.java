package com.liche.wechatagent.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

    /** Stores a bounded, replay-safe task envelope. Binary media and URLs are deliberately excluded. */
    public void captureInput(String taskId, InboundMessageBatch batch) {
        if (batch == null || taskId == null || taskId.isBlank()) return;
        String content = batch.historyContent();
        if (content.length() > 8000) content = content.substring(0, 8000) + "…";
        boolean hasQuote = !batch.quotedContent().isBlank() || !batch.quotedImages().isEmpty()
                || !batch.quotedAttachments().isEmpty();
        save(taskId, Map.of(
                "inputContent", safe(content),
                "inputMessageIds", safe(String.join(",", batch.messageIds())),
                "inputMediaCount", String.valueOf(batch.images().size() + batch.attachments().size()),
                "inputQuotedMediaCount", String.valueOf(batch.quotedImages().size() + batch.quotedAttachments().size()),
                "replaySafe", String.valueOf(batch.attachments().isEmpty() && batch.images().isEmpty() && !hasQuote),
                "recoverySchema", "2",
                "channel", safe(batch.channel()), "botId", safe(batch.botId())
        ));
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

    public void markReplySent(String taskId) {
        if (taskId == null || taskId.isBlank()) return;
        save(taskId, Map.of("status", "REPLY_SENT", "replySentAt", Instant.now().toString(),
                "currentStep", "REPLY_SENT"));
    }

    public void markUnsafeToReplay(String taskId, String reason) {
        if (taskId == null || taskId.isBlank()) return;
        save(taskId, Map.of("replaySafe", "false", "replayBlockedReason", safe(reason)));
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

    public Set<String> findByStatus(String status) {
        if (status == null || status.isBlank()) return Set.of();
        try {
            var ids = redis.opsForSet().members("agent:tasks:index");
            if (ids == null) return Set.of();
            return ids.stream().filter(id -> status.equals(redis.opsForHash().get("agent:task:" + id, "status")))
                    .collect(Collectors.toUnmodifiableSet());
        } catch (RuntimeException ignored) {
            return Set.of();
        }
    }

    public Set<String> findTaskIds() {
        try {
            Set<String> ids = redis.opsForSet().members("agent:tasks:index");
            return ids == null ? Set.of() : Set.copyOf(ids);
        } catch (RuntimeException ignored) { return Set.of(); }
    }

    /** Atomically claims a single manual retry and returns its immutable task envelope. */
    public Map<Object, Object> claimManualRetry(String taskId) {
        Map<Object, Object> state = find(taskId);
        if (!"UNKNOWN_RESULT".equals(String.valueOf(state.get("status")))
                || !"true".equals(String.valueOf(state.get("replaySafe")))
                || !"2".equals(String.valueOf(state.get("recoverySchema")))
                || String.valueOf(state.get("inputContent")).isBlank()) return Map.of();
        try {
            String claimKey = "agent:task:" + taskId + ":retry-claimed";
            Boolean claimed = redis.opsForValue().setIfAbsent(claimKey, "1", ttl);
            if (!Boolean.TRUE.equals(claimed)) return Map.of();
            save(taskId, Map.of("status", "RETRY_REQUESTED", "retryRequestedAt", Instant.now().toString()));
            return find(taskId);
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
