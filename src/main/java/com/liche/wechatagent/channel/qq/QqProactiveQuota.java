package com.liche.wechatagent.channel.qq;

import com.liche.wechatagent.config.QqRuntimeProperties;
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
 * 主动消息（不带 msg_id 的消息）的日额度账本。
 *
 * <p>为什么要记账：QQ 主动消息在平台侧是**有配额的**（被动回复不算），配额用光之后发送会失败，
 * 而失败原因只体现在接口返回里——历史上从面板上完全看不出来，用户看到的现象就是"提醒没发到"
 * 或者"某天的推送没了"。这里把每天的主动发送次数记在 Redis 里（进程重启不清零、随 Redis 一起持久化），
 * 面板「QQ 通道」页直接可见。
 *
 * <p>{@code qq.proactive-daily-limit} 默认 0 = 不限（只观测、不拦截）；配成正数时，
 * 超过上限的主动消息**直接放弃发送并记 WARN**（带当天计数），而不是发出去等平台拒绝。
 * 注意这是**全实例**的口径，不是按用户：QQ 的配额既有总量也有按用户的部分，
 * 这里只守住"今天发了多少条"这个最容易被忽略的账。
 */
@Component
public class QqProactiveQuota {

    private static final Logger log = LoggerFactory.getLogger(QqProactiveQuota.class);
    private static final String KEY_PREFIX = "qq:proactive:";
    private static final Duration KEY_TTL = Duration.ofDays(2);

    private final StringRedisTemplate redis;
    private final int dailyLimit;
    private final ZoneId zone;
    /** Redis 挂了只提醒一次，别把日志刷满（记账失败绝不能影响发消息） */
    private final AtomicBoolean redisFailureLogged = new AtomicBoolean(false);

    public QqProactiveQuota(StringRedisTemplate redis, QqRuntimeProperties properties,
                            @Value("${app.time-zone:Asia/Shanghai}") String timeZone) {
        this.redis = redis;
        int limit = properties == null ? 0 : properties.getProactiveDailyLimit();
        this.dailyLimit = limit < 0 ? 0 : limit;
        this.zone = parseZone(timeZone);
    }

    /** 今天已经发出的主动消息条数；Redis 不可用时返回 -1（面板据此显示"不可用"） */
    public long used() {
        try {
            String value = redis.opsForValue().get(key());
            return value == null ? 0L : Long.parseLong(value);
        } catch (Exception exception) {
            logRedisFailure(exception);
            return -1L;
        }
    }

    public int limit() {
        return dailyLimit;
    }

    public String day() {
        return LocalDate.now(zone).toString();
    }

    /** 配额是否还没用完（0 = 不限，永远可用） */
    public boolean allows() {
        if (dailyLimit <= 0) {
            return true;
        }
        long used = used();
        return used < dailyLimit;
    }

    /** 记一次**已确认发出**的主动消息 */
    public void record() {
        try {
            String key = key();
            Long value = redis.opsForValue().increment(key);
            if (value != null && value == 1L) {
                redis.expire(key, KEY_TTL);
            }
        } catch (Exception exception) {
            logRedisFailure(exception);
        }
    }

    private String key() {
        return KEY_PREFIX + day();
    }

    private void logRedisFailure(Exception exception) {
        if (redisFailureLogged.compareAndSet(false, true)) {
            log.warn("主动消息额度账本不可用（Redis 异常），本次仅记录一条告警：{}", exception.getMessage());
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
