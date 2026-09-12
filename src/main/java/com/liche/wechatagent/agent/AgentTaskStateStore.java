package com.liche.wechatagent.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.LinkedHashMap;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Durable, user-scoped lifecycle state for asynchronous agent executions. */
@Component
public class AgentTaskStateStore {
    static final DefaultRedisScript<List> CLAIM_RETRY = new DefaultRedisScript<>("""
            local state = KEYS[1]
            if redis.call('HGET', state, 'status') ~= 'UNKNOWN_RESULT'
                or redis.call('HGET', state, 'replaySafe') ~= 'true'
                or redis.call('HGET', state, 'recoverySchema') ~= '2' then
                return {}
            end
            local content = redis.call('HGET', state, 'inputContent')
            if not content or not string.find(content, '%S') then return {} end
            if not redis.call('SET', KEYS[2], '1', 'NX', 'PX', ARGV[2]) then return {} end
            redis.call('HSET', state, 'status', 'RETRY_REQUESTED', 'retryRequestedAt', ARGV[1], 'updatedAt', ARGV[1])
            redis.call('PEXPIRE', state, ARGV[2])
            return redis.call('HGETALL', state)
            """, List.class);
    private final StringRedisTemplate redis;
    private static final String INDEX_KEY = "agent:tasks:index";

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
        boolean truncated = content.length() > 8000;
        if (truncated) content = content.substring(0, 8000) + "…";
        boolean hasQuote = !batch.quotedContent().isBlank() || !batch.quotedImages().isEmpty()
                || !batch.quotedAttachments().isEmpty();
        save(taskId, Map.of(
                "inputContent", safe(content),
                "inputMessageIds", safe(String.join(",", batch.messageIds())),
                "inputMediaCount", String.valueOf(batch.images().size() + batch.attachments().size()),
                "inputQuotedMediaCount", String.valueOf(batch.quotedImages().size() + batch.quotedAttachments().size()),
                "replaySafe", String.valueOf(!truncated && batch.attachments().isEmpty() && batch.images().isEmpty() && !hasQuote),
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
            redis.opsForSet().add(INDEX_KEY, taskId);
            redis.expire(key, ttl);
            // 索引本身也要有过期时间：任务 hash 会自然过期，但 Set 成员不会，
            // 索引于是无限增长（每条入站消息都往里加一个成员，要等下次重启才清理）。
            // 每次写入续一次期是安全的——索引过期时，比它更早写入的任务 hash 必然都已过期。
            redis.expire(INDEX_KEY, ttl);
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
            var ids = redis.opsForSet().members(INDEX_KEY);
            if (ids == null) return Set.of();
            Set<String> matched = new java.util.LinkedHashSet<>();
            Set<String> expired = new java.util.LinkedHashSet<>();
            for (String id : ids) {
                Object value = redis.opsForHash().get("agent:task:" + id, "status");
                if (value == null) {
                    // 任务 hash 已经过期：顺手把索引里的陈旧成员摘掉，避免索引只增不减
                    expired.add(id);
                } else if (status.equals(value)) {
                    matched.add(id);
                }
            }
            if (!expired.isEmpty()) {
                redis.opsForSet().remove(INDEX_KEY, expired.toArray());
            }
            return Set.copyOf(matched);
        } catch (RuntimeException ignored) {
            return Set.of();
        }
    }

    public Set<String> findTaskIds() {
        try {
            Set<String> ids = redis.opsForSet().members(INDEX_KEY);
            return ids == null ? Set.of() : Set.copyOf(ids);
        } catch (RuntimeException ignored) { return Set.of(); }
    }

    /** Atomically claims a single manual retry and returns its immutable task envelope. */
    public Map<Object, Object> claimManualRetry(String taskId) {
        if (taskId == null || taskId.isBlank()) return Map.of();
        Map<Object, Object> state = find(taskId);
        if (!"UNKNOWN_RESULT".equals(String.valueOf(state.get("status")))
                || !"true".equals(String.valueOf(state.get("replaySafe")))
                || !"2".equals(String.valueOf(state.get("recoverySchema")))
                || state.get("inputContent") == null || String.valueOf(state.get("inputContent")).isBlank()) return Map.of();
        try {
            String taskKey = "agent:task:" + taskId;
            List<?> claimed = redis.execute(CLAIM_RETRY, List.of(taskKey, taskKey + ":retry-claimed"),
                    Instant.now().toString(), String.valueOf(ttl.toMillis()));
            if (claimed == null || claimed.isEmpty()) return Map.of();
            Map<Object, Object> envelope = new LinkedHashMap<>();
            for (int index = 0; index + 1 < claimed.size(); index += 2) {
                envelope.put(claimed.get(index), claimed.get(index + 1));
            }
            return java.util.Collections.unmodifiableMap(envelope);
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    @PostConstruct
    public void markInterruptedTasks() {
        try {
            var taskIds = redis.opsForSet().members(INDEX_KEY);
            if (taskIds == null) return;
            for (String taskId : taskIds) {
                String taskKey = "agent:task:" + taskId;
                Object status = redis.opsForHash().get(taskKey, "status");
                if (status == null) {
                    redis.opsForSet().remove(INDEX_KEY, taskId);
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
