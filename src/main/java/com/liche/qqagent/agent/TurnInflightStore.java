package com.liche.qqagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「这一轮正在做什么」的落盘标记（2026-11-04）。
 *
 * <p><b>为什么需要</b>：用户说「继续」时，模型要能知道**上一轮到底在做什么**。
 * 这个信息原来只活在进程内存里，遇到重启/崩溃就没了——实测那天他说「继续」，
 * 模型翻遍历史也找不到"上一轮的动作"，于是顺着系统提示词往下念规则，看起来很傻。
 *
 * <p><b>做法</b>：一轮开始时写一条 Redis（用户原话 + 开始时间 + 状态），正常结束就删；
 * 下一轮开始前如果发现**残留**，说明上一轮被打断，就把这条作为提示注入，让它能接着做。
 *
 * <p>放 Redis 而不是内存：重启后标记还在，重启也不丢。
 */
@Component
public class TurnInflightStore {

    private static final Logger log = LoggerFactory.getLogger(TurnInflightStore.class);
    private static final String PREFIX = "turn:inflight:";
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 超过这个时长的残留就不再当作"上一轮"，避免几天前的标记翻出来 */
    private static final Duration TTL = Duration.ofHours(6);

    private final ObjectProvider<StringRedisTemplate> redisProvider;

    public TurnInflightStore(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redisProvider = redisProvider;
    }

    /** 一轮开始：记下他在做什么 */
    public void markStart(String userId, String userText) {
        if (userId == null || userId.isBlank() || userText == null || userText.isBlank()) {
            return;
        }
        StringRedisTemplate redis = redis();
        if (redis == null) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("text", clip(userText, 300));
            payload.put("at", LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm")));
            redis.opsForValue().set(PREFIX + userId, JSON.writeValueAsString(payload), TTL);
        } catch (Exception exception) {
            log.debug("写 inflight 标记失败：{}", exception.getMessage());
        }
    }

    /** 一轮正常结束：清掉标记 */
    public void clear(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        StringRedisTemplate redis = redis();
        if (redis == null) {
            return;
        }
        try {
            redis.delete(PREFIX + userId);
        } catch (Exception exception) {
            log.debug("清 inflight 标记失败：{}", exception.getMessage());
        }
    }

    /**
     * 取上一轮留下的残留（如果有）。
     *
     * @return 形如「06:15 你说过：…（上一轮没跑完）」；没有残留返回 null
     */
    public String takeStale(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        StringRedisTemplate redis = redis();
        if (redis == null) {
            return null;
        }
        try {
            String raw = redis.opsForValue().get(PREFIX + userId);
            if (raw == null || raw.isBlank()) {
                return null;
            }
            com.fasterxml.jackson.databind.JsonNode payload = JSON.readTree(raw);
            String text = payload.path("text").asText("");
            String at = payload.path("at").asText("");
            if (text.isBlank()) {
                return null;
            }
            return at + " 你说过：「" + text + "」，那一轮没跑完（服务重启/中断）。";
        } catch (Exception exception) {
            log.debug("读 inflight 标记失败：{}", exception.getMessage());
            return null;
        }
    }

    private StringRedisTemplate redis() {
        return redisProvider == null ? null : redisProvider.getIfAvailable();
    }

    private static String clip(String text, int max) {
        String value = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
