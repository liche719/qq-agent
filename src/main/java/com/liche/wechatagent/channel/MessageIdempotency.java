package com.liche.wechatagent.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** 基于 msg_id + user_id 的消息幂等去重（Redis SETNX，24h 过期），避免重复回复 */
@Component
public class MessageIdempotency {

    private static final Logger log = LoggerFactory.getLogger(MessageIdempotency.class);
    private record DeliveryScope(String channel, String botId) {
    }

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final ThreadLocal<DeliveryScope> deliveryScope = new ThreadLocal<>();

    @Autowired
    public MessageIdempotency(StringRedisTemplate redis,
                              @Value("${agent.message-idempotency-ttl-hours:24}") long ttlHours) {
        this.redis = redis;
        this.ttl = Duration.ofHours(Math.max(1, Math.min(24 * 30, ttlHours)));
    }

    MessageIdempotency(StringRedisTemplate redis) {
        this(redis, 24);
    }

    public boolean tryAcquire(InboundMessage message) {
        if (message == null) {
            return false;
        }
        return tryAcquire(message.userId(), message.msgId(), message.channel(), message.botId());
    }

    /** Binds the inbound transport identity while preserving the legacy two-argument API. */
    public void bindDeliveryScope(String channel, String botId) {
        deliveryScope.set(new DeliveryScope(channel, botId));
    }

    public void clearDeliveryScope() {
        deliveryScope.remove();
    }

    public boolean tryAcquire(String userId, String msgId) {
        DeliveryScope scope = deliveryScope.get();
        return tryAcquire(userId, msgId,
                scope == null ? "" : scope.channel(), scope == null ? "" : scope.botId());
    }

    private boolean tryAcquire(String userId, String msgId, String channel, String botId) {
        if (msgId == null || msgId.isBlank()) {
            return true; // 无 msg_id（如内部测试）不判重
        }
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(keyFor(userId, msgId, channel, botId), "1", ttl);
            return Boolean.TRUE.equals(acquired);
        } catch (Exception e) {
            // Redis 不可用时放行（宁可偶发重复，不可丢消息）
            log.warn("幂等检查失败（Redis 不可用？），放行消息 userId={}", userId);
            return true;
        }
    }

    static String keyFor(String userId, String msgId, String channel, String botId) {
        return "dedup:" + encoded(channel) + ':' + encoded(botId) + ':' + encoded(userId) + ':' + encoded(msgId);
    }

    private static String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }
}
