package com.liche.wechatagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.time.Duration;

/**
 * 第一层记忆：近期对话上下文（Redis）。
 * 最近 N 轮原文跨重启保留但容量固定；长期事实由提取器晋升到 MySQL 记忆层。
 */
@Component
public class ContextStore {

    private static final Logger log = LoggerFactory.getLogger(ContextStore.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final int rounds;
    private final Duration contextTtl;
    private final ConcurrentMap<String, FallbackContext> fallbackByUser = new ConcurrentHashMap<>();

    private static final class FallbackContext {
        private final ConcurrentLinkedDeque<ContextTurn> turns = new ConcurrentLinkedDeque<>();
        private volatile long expiresAtMillis;

        private FallbackContext(long expiresAtMillis) {
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    public ContextStore(StringRedisTemplate redis,
                        ObjectMapper objectMapper,
                        @Value("${memory.context-rounds:10}") int rounds,
                        @Value("${memory.context-ttl-hours:720}") long contextTtlHours) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.rounds = Math.max(1, rounds);
        this.contextTtl = Duration.ofHours(Math.max(1, contextTtlHours));
    }

    private String key(String userId) {
        return "ctx:" + userId;
    }

    /** 追加一轮消息（user 或 assistant），保留最近 N 轮。Redis 异常时仅告警，不影响主流程 */
    public void push(String userId, String role, String text) {
        push(userId, role, text, List.of());
    }

    public void push(String userId, String role, String text, List<String> sourceMessageIds) {
        ContextTurn fallbackTurn = new ContextTurn(role, text, sourceMessageIds == null ? List.of() : List.copyOf(sourceMessageIds));
        pushFallback(userId, fallbackTurn);
        try {
            Map<String, Object> turn = new LinkedHashMap<>();
            turn.put("role", role);
            turn.put("text", text);
            turn.put("sourceMessageIds", sourceMessageIds == null ? List.of() : sourceMessageIds);
            String json = objectMapper.writeValueAsString(turn);
            String k = key(userId);
            redis.opsForList().leftPush(k, json);
            redis.opsForList().trim(k, 0, rounds * 2L - 1);
            redis.expire(k, contextTtl);
        } catch (Exception e) {
            log.warn("写入对话上下文失败（Redis 不可用？）userId={} reason={}", userId,
                    e.getClass().getSimpleName());
        }
    }

    /** 全部最近上下文（时间正序） */
    public List<ContextTurn> getRecent(String userId) {
        return getRecent(userId, Integer.MAX_VALUE);
    }

    /** 最近 N 条消息（时间正序） */
    public List<ContextTurn> getRecent(String userId, int n) {
        try {
            List<String> raw = redis.opsForList().range(key(userId), 0, Math.max(n - 1, 0));
            if (raw == null || raw.isEmpty()) {
                return fallbackRecent(userId, n);
            }
            // Redis list: 最新在头 → 翻转成时间正序
            List<String> chronological = new ArrayList<>(raw);
            Collections.reverse(chronological);
            List<ContextTurn> turns = new ArrayList<>();
            for (String line : chronological) {
                try {
                    var node = objectMapper.readTree(line);
                    List<String> sourceMessageIds = new ArrayList<>();
                    for (var sourceId : node.path("sourceMessageIds")) {
                        String value = sourceId.asText("");
                        if (!value.isBlank()) {
                            sourceMessageIds.add(value);
                        }
                    }
                    String role = node.path("role").asText("user");
                    String text = node.path("text").asText("");
                    turns.add(new ContextTurn(role, historicalMediaSafeText(role, text), sourceMessageIds));
                } catch (Exception ignored) {
                    // 跳过损坏条目
                }
            }
            return turns;
        } catch (Exception exception) {
            log.warn("读取对话上下文失败（Redis 不可用？）userId={} reason={}", userId,
                    exception.getClass().getSimpleName());
            return fallbackRecent(userId, n);
        }
    }

    private void pushFallback(String userId, ContextTurn turn) {
        long expiresAt = System.currentTimeMillis() + contextTtl.toMillis();
        FallbackContext fallback = fallbackByUser.compute(userId, (ignored, existing) -> {
            FallbackContext target = existing == null ? new FallbackContext(expiresAt) : existing;
            target.expiresAtMillis = expiresAt;
            return target;
        });
        fallback.turns.addLast(turn);
        while (fallback.turns.size() > rounds * 2) {
            fallback.turns.pollFirst();
        }
    }

    private List<ContextTurn> fallbackRecent(String userId, int n) {
        FallbackContext fallback = fallbackByUser.get(userId);
        if (fallback == null) {
            return List.of();
        }
        if (fallback.expiresAtMillis < System.currentTimeMillis()) {
            fallbackByUser.remove(userId, fallback);
            return List.of();
        }
        List<ContextTurn> turns = new ArrayList<>(fallback.turns);
        if (n < turns.size()) {
            turns = new ArrayList<>(turns.subList(turns.size() - n, turns.size()));
        }
        return turns.stream()
                .map(turn -> new ContextTurn(turn.role(), historicalMediaSafeText(turn.role(), turn.text()), turn.sourceMessageIds()))
                .toList();
    }

    /**
     * 兼容旧 Redis 上下文：旧版本用 [图片]/[文件] 作为媒体占位符，
     * 容易被模型误解为本轮仍可见。这里只在交给模型前标明它们属于历史。
     */
    private String historicalMediaSafeText(String role, String text) {
        if (!"user".equals(role) || text == null || text.isBlank()) {
            return text;
        }
        return text.replace("[图片]", "[历史消息曾附带图片；图片原件未随当前消息提供]")
                .replace("[文件]", "[历史消息曾附带文件；文件原件未随当前消息提供]")
                .replace("[引用消息]", "[历史消息曾引用一条消息]");
    }

}
