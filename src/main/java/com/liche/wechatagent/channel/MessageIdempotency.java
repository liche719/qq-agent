package com.liche.wechatagent.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 基于 msg_id + user_id 的消息幂等去重（Redis SETNX，24h 过期），避免重复回复 */
@Component
public class MessageIdempotency {

    private static final Logger log = LoggerFactory.getLogger(MessageIdempotency.class);
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redis;

    public MessageIdempotency(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean tryAcquire(String userId, String msgId) {
        if (msgId == null || msgId.isBlank()) {
            return true; // 无 msg_id（如内部测试）不判重
        }
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent("dedup:" + userId + ":" + msgId, "1", TTL);
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            // Redis 不可用时放行（宁可偶发重复，不可丢消息）
            log.warn("幂等检查失败（Redis 不可用？），放行消息 userId={}", userId);
            return true;
        }
    }
}
