package com.liche.qqagent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.function.Function;

/**
 * 副作用工具的幂等保护（2026-10-29）。
 *
 * <p><b>要解决的具体问题</b>：发评论、发文件、发语音这类操作，如果**超时**或**结果未知**，
 * 模型下一轮很可能会再调一次——用户就收到两遍。之前只有"提示模型别重试"，没有机制拦。
 *
 * <p><b>为什么要区分两种记忆</b>：
 * <ul>
 *   <li><b>成功过</b>：60 秒内同样的「工具+参数」再来一次，直接返回上次结果，不再执行
 *       （防的是模型自己在一轮里重试）；超过 60 秒再调，视为用户真的又要一次
 *       （"再提醒我一次"是合法需求，不能永久去重）；</li>
 *   <li><b>结果未知</b>：5 分钟内同样的调用再来，**直接跳过**并说明"上次没确认，先别重做"，
 *       因为可能已经生效了。</li>
 * </ul>
 *
 * <p>Redis 不可用时**放行**：宁可偶尔重复，也不能因为缓存挂了导致工具全不能用。
 */
public class ToolIdempotencyGuard {

    private static final Logger log = LoggerFactory.getLogger(ToolIdempotencyGuard.class);

    private static final String PREFIX = "tool:idem:";
    private static final Duration SUCCESS_TTL = Duration.ofSeconds(60);
    private static final Duration UNKNOWN_TTL = Duration.ofMinutes(5);

    /** 上一次调用的状态 */
    public enum State {
        /** 没记录 */
        NONE,
        /** 最近成功过（带结果内容） */
        SUCCEEDED,
        /** 最近一次结果未知（可能已生效） */
        UNKNOWN
    }

    public record Verdict(State state, String content) {
        static final Verdict NONE = new Verdict(State.NONE, "");
    }

    private final Function<String, String> reader;
    private final TriConsumer writer;

    /** 方便单测：直接给"读/写"两个函数，不依赖 Redis */
    public interface TriConsumer {
        void accept(String key, String value, Duration ttl);
    }

    public ToolIdempotencyGuard(StringRedisTemplate redis) {
        this(key -> {
            try {
                return redis.opsForValue().get(key);
            } catch (RuntimeException exception) {
                return null;
            }
        }, (key, value, ttl) -> {
            try {
                redis.opsForValue().set(key, value, ttl);
            } catch (RuntimeException exception) {
                log.debug("写幂等标记失败（忽略）：{}", exception.getMessage());
            }
        });
    }

    ToolIdempotencyGuard(Function<String, String> reader, TriConsumer writer) {
        this.reader = reader;
        this.writer = writer;
    }

    /** 「工具 + 参数」的指纹：同一个用户、同一个工具、同样的入参 → 同一个 key */
    static String keyOf(String userId, String toolName, String arguments) {
        String raw = (userId == null ? "" : userId) + "|" + (toolName == null ? "" : toolName) + "|"
                + normalize(arguments);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte value : hash) {
                hex.append(String.format("%02x", value));
            }
            return PREFIX + hex.substring(0, 32);
        } catch (Exception exception) {
            return PREFIX + Integer.toHexString(raw.hashCode());
        }
    }

    private static String normalize(String arguments) {
        return arguments == null ? "" : arguments.replaceAll("\\s+", "");
    }

    /** 查上一次的状态 */
    public Verdict check(String key) {
        String value = reader.apply(key);
        if (value == null || value.isBlank()) {
            return Verdict.NONE;
        }
        if (value.startsWith("OK:")) {
            return new Verdict(State.SUCCEEDED, value.substring(3));
        }
        if (value.startsWith("UNKNOWN")) {
            return new Verdict(State.UNKNOWN, "");
        }
        return Verdict.NONE;
    }

    public void markSucceeded(String key, String content) {
        writer.accept(key, "OK:" + clip(content), SUCCESS_TTL);
    }

    public void markUnknown(String key) {
        writer.accept(key, "UNKNOWN", UNKNOWN_TTL);
    }

    private static String clip(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= 2_000 ? content : content.substring(0, 2_000) + "…";
    }
}
