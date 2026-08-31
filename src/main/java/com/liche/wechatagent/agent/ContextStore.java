package com.liche.wechatagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private record RedactedContext(List<ContextTurn> remaining, boolean changed) {
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
            String json = serialize(fallbackTurn);
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
                    ContextTurn turn = deserialize(line);
                    turns.add(new ContextTurn(turn.role(), historicalMediaSafeText(turn.role(), turn.text()),
                            turn.sourceMessageIds()));
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

    /**
     * Removes only the context turns that supplied a forgotten memory, plus the directly associated reply.
     * Message IDs are authoritative; text matching is a conservative fallback for memories created by older versions.
     */
    public boolean removeMemoryEvidence(String userId, List<String> sourceMessageIds, String rememberedContent) {
        Set<String> sourceIds = normalizedSourceIds(sourceMessageIds);
        boolean fallbackChanged = redactFallback(userId, sourceIds, rememberedContent);
        try {
            String contextKey = key(userId);
            List<String> raw = redis.opsForList().range(contextKey, 0, -1);
            if (raw == null || raw.isEmpty()) {
                return fallbackChanged;
            }
            List<ContextTurn> chronological = new ArrayList<>();
            for (String entry : raw) {
                try {
                    chronological.add(deserialize(entry));
                } catch (Exception ignored) {
                    // Corrupted legacy context is not retained during a privacy cleanup.
                }
            }
            Collections.reverse(chronological);
            RedactedContext redacted = redact(chronological, sourceIds, rememberedContent);
            if (!redacted.changed()) {
                return fallbackChanged;
            }
            redis.delete(contextKey);
            for (ContextTurn turn : redacted.remaining()) {
                redis.opsForList().leftPush(contextKey, serialize(turn));
            }
            if (!redacted.remaining().isEmpty()) {
                redis.expire(contextKey, contextTtl);
            }
            return true;
        } catch (Exception exception) {
            log.warn("清理关联对话上下文失败（Redis 不可用？）userId={} reason={}", userId,
                    exception.getClass().getSimpleName());
            return fallbackChanged;
        }
    }

    /** Privacy fallback for legacy memories that have no source-message provenance to redact selectively. */
    public boolean clearForMemoryForget(String userId) {
        boolean fallbackChanged = fallbackByUser.remove(userId) != null;
        try {
            Boolean deleted = redis.delete(key(userId));
            return fallbackChanged || Boolean.TRUE.equals(deleted);
        } catch (Exception exception) {
            log.warn("清理短期对话上下文失败（Redis 不可用？）userId={} reason={}", userId,
                    exception.getClass().getSimpleName());
            return fallbackChanged;
        }
    }

    private void pushFallback(String userId, ContextTurn turn) {
        long expiresAt = System.currentTimeMillis() + contextTtl.toMillis();
        FallbackContext fallback = fallbackByUser.compute(userId, (ignored, existing) -> {
            FallbackContext target = existing == null ? new FallbackContext(expiresAt) : existing;
            target.expiresAtMillis = expiresAt;
            return target;
        });
        synchronized (fallback) {
            fallback.turns.addLast(turn);
            while (fallback.turns.size() > rounds * 2) {
                fallback.turns.pollFirst();
            }
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
        List<ContextTurn> turns;
        synchronized (fallback) {
            turns = new ArrayList<>(fallback.turns);
        }
        if (n < turns.size()) {
            turns = new ArrayList<>(turns.subList(turns.size() - n, turns.size()));
        }
        return turns.stream()
                .map(turn -> new ContextTurn(turn.role(), historicalMediaSafeText(turn.role(), turn.text()), turn.sourceMessageIds()))
                .toList();
    }

    private boolean redactFallback(String userId, Set<String> sourceIds, String rememberedContent) {
        FallbackContext fallback = fallbackByUser.get(userId);
        if (fallback == null) {
            return false;
        }
        synchronized (fallback) {
            RedactedContext redacted = redact(new ArrayList<>(fallback.turns), sourceIds, rememberedContent);
            if (!redacted.changed()) {
                return false;
            }
            fallback.turns.clear();
            fallback.turns.addAll(redacted.remaining());
            if (fallback.turns.isEmpty()) {
                fallbackByUser.remove(userId, fallback);
            }
            return true;
        }
    }

    private RedactedContext redact(List<ContextTurn> turns, Set<String> sourceIds, String rememberedContent) {
        List<ContextTurn> remaining = new ArrayList<>();
        boolean changed = false;
        boolean removeAssociatedAssistantReply = false;
        for (ContextTurn turn : turns) {
            boolean remove = hasEvidence(turn, sourceIds, rememberedContent);
            if (!remove && removeAssociatedAssistantReply && "assistant".equals(turn.role())) {
                remove = true;
            }
            if (remove) {
                changed = true;
                if ("user".equals(turn.role())) {
                    removeAssociatedAssistantReply = true;
                } else if ("assistant".equals(turn.role())) {
                    removeAssociatedAssistantReply = false;
                }
                continue;
            }
            if (!"assistant".equals(turn.role())) {
                removeAssociatedAssistantReply = false;
            }
            remaining.add(turn);
        }
        return new RedactedContext(List.copyOf(remaining), changed);
    }

    private boolean hasEvidence(ContextTurn turn, Set<String> sourceIds, String rememberedContent) {
        if (!sourceIds.isEmpty() && turn.sourceMessageIds().stream().anyMatch(sourceIds::contains)) {
            return true;
        }
        return hasDistinctiveTextOverlap(turn.text(), rememberedContent);
    }

    private boolean hasDistinctiveTextOverlap(String text, String rememberedContent) {
        String left = normalize(text);
        String right = normalize(rememberedContent);
        if (left.length() < 6 || right.length() < 6) {
            return false;
        }
        if (left.contains(right) || right.contains(left)) {
            return true;
        }
        int maximumLength = Math.min(20, left.length());
        for (int length = maximumLength; length >= 6; length--) {
            for (int start = 0; start + length <= left.length(); start++) {
                if (right.contains(left.substring(start, start + length))) {
                    return true;
                }
            }
        }
        return false;
    }

    private Set<String> normalizedSourceIds(List<String> sourceMessageIds) {
        Set<String> result = new HashSet<>();
        if (sourceMessageIds == null) {
            return result;
        }
        for (String sourceMessageId : sourceMessageIds) {
            if (sourceMessageId != null && !sourceMessageId.isBlank()) {
                result.add(sourceMessageId);
            }
        }
        return result;
    }

    private String serialize(ContextTurn turn) throws Exception {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("role", turn.role());
        value.put("text", turn.text());
        value.put("sourceMessageIds", turn.sourceMessageIds());
        return objectMapper.writeValueAsString(value);
    }

    private ContextTurn deserialize(String line) throws Exception {
        var node = objectMapper.readTree(line);
        List<String> sourceMessageIds = new ArrayList<>();
        for (var sourceId : node.path("sourceMessageIds")) {
            String value = sourceId.asText("");
            if (!value.isBlank()) {
                sourceMessageIds.add(value);
            }
        }
        return new ContextTurn(node.path("role").asText("user"), node.path("text").asText(""), sourceMessageIds);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase()
                .replaceAll("[\\p{P}\\p{Z}\\s]+", "")
                .trim();
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
