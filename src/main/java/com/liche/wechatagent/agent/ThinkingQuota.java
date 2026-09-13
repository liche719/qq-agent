package com.liche.wechatagent.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@code thinkDeeper}（临时升档）的**按用户日额度**账本。
 *
 * <p>为什么要记账：升档会让这一轮用更贵的档位（思考 + 更多工具轮 + 更长超时），而"要不要升档"是模型自己决定的。
 * 模型判断失手时（把简单问题当难题），没有额度就会被无限放大成持续的成本。记账写在 Redis（进程重启不清零），
 * 键 {@code llm:think:<yyyy-MM-dd>:<userId>}。
 *
 * <p>{@code agent.thinking-daily-limit-per-user} 默认 5，**0 = 不限**（只观测）。额度用尽时 {@link #allows} 返回 false，
 * 工具会明确告诉模型"今天升不了档了，按当前档位尽力回答"——**不改变正确性**，只是不升级。
 *
 * <p>Redis 异常时**放行（fail-open）**：额度是防滥用、不是安全边界，不能因为 Redis 抖一下就让功能不可用；
 * 异常只记一条 WARN（避免刷日志）。
 */
@Component
public class ThinkingQuota {

    private static final Logger log = LoggerFactory.getLogger(ThinkingQuota.class);
    private static final String KEY_PREFIX = "llm:think:";
    private static final Duration KEY_TTL = Duration.ofDays(2);

    private final StringRedisTemplate redis;
    private final int dailyLimit;
    private final ZoneId zone;
    private final AtomicBoolean redisFailureLogged = new AtomicBoolean(false);

    public ThinkingQuota(StringRedisTemplate redis,
                         @Value("${agent.thinking-daily-limit-per-user:5}") int dailyLimit,
                         @Value("${app.time-zone:Asia/Shanghai}") String timeZone) {
        this.redis = redis;
        this.dailyLimit = Math.max(0, dailyLimit);
        this.zone = parseZone(timeZone);
    }

    public int limit() {
        return dailyLimit;
    }

    public String day() {
        return LocalDate.now(zone).toString();
    }

    /** 今天该用户已用的升档次数；Redis 不可用返回 -1（面板据此显示"不可用"） */
    public long used(String userId) {
        if (userId == null || userId.isBlank()) {
            return 0L;
        }
        try {
            String value = redis.opsForValue().get(key(userId));
            return value == null ? 0L : Long.parseLong(value);
        } catch (Exception exception) {
            logRedisFailure(exception);
            return -1L;
        }
    }

    /** 还能不能升档（0 = 不限；Redis 异常放行） */
    public boolean allows(String userId) {
        if (dailyLimit <= 0) {
            return true;
        }
        long used = used(userId);
        return used < 0 || used < dailyLimit;
    }

    /** 记一次**已生效**的升档 */
    public void record(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        try {
            String key = key(userId);
            Long value = redis.opsForValue().increment(key);
            if (value != null && value == 1L) {
                redis.expire(key, KEY_TTL);
            }
        } catch (Exception exception) {
            logRedisFailure(exception);
        }
    }

    private String key(String userId) {
        return KEY_PREFIX + day() + ":" + userId;
    }

    private void logRedisFailure(Exception exception) {
        if (redisFailureLogged.compareAndSet(false, true)) {
            log.warn("升档额度账本不可用（Redis 异常），本次只记一条告警，额度暂时不拦：{}", exception.getMessage());
        }
    }

    private static ZoneId parseZone(String value) {
        try {
            return value == null || value.isBlank() ? ZoneId.of("Asia/Shanghai") : ZoneId.of(value.trim());
        } catch (Exception ignored) {
            return ZoneId.of("Asia/Shanghai");
        }
    }
}
