package com.liche.wechatagent.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DeepSeek 搜索的**每日配额**（2026-10-20 加）。
 *
 * <p>用户的要求：搜索全部优先用 DeepSeek 的搜索 API（按次收费），**但当天一个对话账号最多 20 次**，
 * 超了就用自托管的 SearXNG 兜底。所以这里要回答一个问题：**这个账号今天还能不能再用一次**。
 *
 * <p>为什么用 Redis 计数：进程重启后配额不该被重置（否则重启一次就白得 20 次）。
 * Redis 不可用时**降级成进程内计数**——宁可计数不准，也不能让搜索直接挂掉。
 */
@Service
public class DeepSeekSearchQuota {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekSearchQuota.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final String KEY_PREFIX = "ds-search:";

    private final StringRedisTemplate redis;
    private final int dailyLimit;
    /** Redis 挂掉时的兜底计数（key=日期:账号） */
    private final Map<String, AtomicLong> local = new ConcurrentHashMap<>();

    public DeepSeekSearchQuota(StringRedisTemplate redis,
                               @Value("${deepseek.search.daily-limit-per-user:20}") int dailyLimit) {
        this.redis = redis;
        this.dailyLimit = Math.max(0, dailyLimit);
    }

    /**
     * 试着占用一次配额。
     *
     * @return true = 今天还能用（已扣一次）；false = 用完了，调用方该走兜底
     */
    public boolean tryConsume(String userId) {
        if (dailyLimit <= 0) {
            return false;
        }
        String key = KEY_PREFIX + LocalDate.now(ZONE) + ":" + (userId == null || userId.isBlank() ? "anon" : userId);
        try {
            String current = redis.opsForValue().get(key);
            long used = current == null ? 0 : Long.parseLong(current);
            if (used >= dailyLimit) {
                return false;
            }
            Long now = redis.opsForValue().increment(key);
            if (now != null && now == 1) {
                // 第二天自动消失；多给一小时防止跨零点边界抖动
                redis.expire(key, Duration.ofHours(25));
            }
            return now != null && now <= dailyLimit;
        } catch (RuntimeException exception) {
            log.warn("DS 搜索配额计数失败，降级为进程内计数：{}", exception.getMessage());
            AtomicLong counter = local.computeIfAbsent(key, ignored -> new AtomicLong());
            return counter.incrementAndGet() <= dailyLimit;
        }
    }

    /** 今天这个账号已经用了几次（面板/排障用） */
    public long usedToday(String userId) {
        String key = KEY_PREFIX + LocalDate.now(ZONE) + ":" + (userId == null || userId.isBlank() ? "anon" : userId);
        try {
            String current = redis.opsForValue().get(key);
            return current == null ? 0 : Long.parseLong(current);
        } catch (RuntimeException exception) {
            AtomicLong counter = local.get(key);
            return counter == null ? 0 : counter.get();
        }
    }

    public int dailyLimit() {
        return dailyLimit;
    }
}
