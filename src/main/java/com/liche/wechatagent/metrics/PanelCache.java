package com.liche.wechatagent.metrics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

/**
 * 面板聚合结果的 Redis 缓存（2026-10-22 用户要求）。
 *
 * <p><b>只缓存"算出来的结果"，不缓存账本本身</b>：真源永远是 PostgreSQL 的
 * {@code llm_call_audit}（每次调用一行，可回算、可对账）。这里存的只是
 * {@code /balance}、{@code /usage} 两个接口组装好的 JSON，TTL 一过重新从库里算。
 *
 * <p>为什么值得缓存：面板 10 秒刷新一次，而每次都要在几千行上做 group by、
 * 还要调一次 DeepSeek 余额接口。缓存 30 秒能把这些压到 1/3 以下。
 *
 * <p>Redis 不可用时**静默降级**（返回 null → 调用方直接查库）：面板慢一点没关系，
 * 不能因为缓存挂了就让面板打不开。
 */
@Service
public class PanelCache {

    private static final Logger log = LoggerFactory.getLogger(PanelCache.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final long ttlSeconds;

    public PanelCache(StringRedisTemplate redis,
                      @Value("${panel.cache.enabled:true}") boolean enabled,
                      @Value("${panel.cache.ttl-seconds:30}") long ttlSeconds) {
        this.redis = redis;
        this.enabled = enabled;
        this.ttlSeconds = Math.max(1, Math.min(3600, ttlSeconds));
    }

    /** 读缓存；没有/过期/Redis 出错都返回 null（调用方回落到查库） */
    public Map<String, Object> get(String key) {
        if (!enabled || key == null || key.isBlank()) {
            return null;
        }
        try {
            String raw = redis.opsForValue().get(key);
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return JSON.readValue(raw, MAP_TYPE);
        } catch (Exception exception) {
            log.warn("面板缓存读取失败（忽略，回落到查库）key={} reason={}", key, exception.getMessage());
            return null;
        }
    }

    public void put(String key, Map<String, Object> value) {
        if (!enabled || key == null || key.isBlank() || value == null) {
            return;
        }
        try {
            redis.opsForValue().set(key, JSON.writeValueAsString(value), Duration.ofSeconds(ttlSeconds));
        } catch (Exception exception) {
            log.warn("面板缓存写入失败（忽略）key={} reason={}", key, exception.getMessage());
        }
    }

    /** 供"刚花了钱、想立刻看到"的场景手动失效（目前没有调用方，留给以后） */
    public void evict(String key) {
        if (!enabled || key == null || key.isBlank()) {
            return;
        }
        try {
            redis.delete(key);
        } catch (Exception exception) {
            log.warn("面板缓存失效失败（忽略）key={} reason={}", key, exception.getMessage());
        }
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }
}
