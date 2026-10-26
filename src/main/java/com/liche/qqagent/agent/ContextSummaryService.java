package com.liche.qqagent.agent;

import com.liche.qqagent.config.LlmScenario;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 长对话的滚动摘要（2026-10-26）。
 *
 * <p><b>为什么要有</b>：上下文只保留最近 `memory.context-max-turns`（40 轮）/ `context-max-chars`
 * （12000 字），超出的**直接丢掉**，既不告诉模型"前面被截了"，也没有任何压缩。结果是：
 * 事实类信息还能靠 `memory_fact` 召回，**叙事类上下文（那件事怎么发展到今天的）直接消失**，
 * 而它自己还以为记得。
 *
 * <p>做法：被丢掉的那段对话压成 ≤150 字的摘要，作为一条 `summary` 轮次插在历史最前面；
 * 结果按"最早被丢的那条消息 id"缓存在 Redis（默认 12 小时），**同一段只花一次调用**。
 */
@Component
public class ContextSummaryService {

    private static final Logger log = LoggerFactory.getLogger(ContextSummaryService.class);
    private static final String KEY_PREFIX = "ctx:summary:";

    private final ChatModel chatModel;
    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final int minTurns;
    private final int ttlHours;

    public ContextSummaryService(ChatModel chatModel,
                                 StringRedisTemplate redis,
                                 @Value("${memory.context-summary-enabled:true}") boolean enabled,
                                 @Value("${memory.context-summary-min-turns:6}") int minTurns,
                                 @Value("${memory.context-summary-ttl-hours:12}") int ttlHours) {
        this.chatModel = chatModel;
        this.redis = redis;
        this.enabled = enabled;
        this.minTurns = Math.max(2, Math.min(40, minTurns));
        this.ttlHours = Math.max(1, Math.min(168, ttlHours));
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * 给被截掉的那段对话生成摘要；不够长、关着、或调用失败都返回 null（调用方就当没有）。
     *
     * @param userId  用户
     * @param dropped 被截掉的（更早的）轮次，按时间正序
     */
    public String summarize(String userId, List<ContextTurn> dropped) {
        if (!enabled || dropped == null || dropped.size() < minTurns) {
            return null;
        }
        String cacheKey = KEY_PREFIX + userId + ":" + cacheTag(dropped);
        try {
            String cached = redis.opsForValue().get(cacheKey);
            if (cached != null && !cached.isBlank()) {
                return cached;
            }
        } catch (RuntimeException exception) {
            log.debug("读上下文摘要缓存失败（忽略）：{}", exception.getMessage());
        }
        try {
            String summary = chatModel.chat(buildPrompt(dropped));
            if (summary == null || summary.isBlank()) {
                return null;
            }
            String cleaned = summary.replaceAll("\\s+", " ").trim();
            if (cleaned.length() > 600) {
                cleaned = cleaned.substring(0, 600) + "…";
            }
            try {
                redis.opsForValue().set(cacheKey, cleaned, Duration.ofHours(ttlHours));
            } catch (RuntimeException exception) {
                log.debug("写上下文摘要缓存失败（忽略）：{}", exception.getMessage());
            }
            log.info("生成上下文摘要 user={} 被截轮次={} 字数={}", userId, dropped.size(), cleaned.length());
            return cleaned;
        } catch (RuntimeException exception) {
            log.warn("生成上下文摘要失败（这轮就不带摘要）：{}", exception.getMessage());
            return null;
        }
    }

    private String buildPrompt(List<ContextTurn> dropped) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("把下面这段较早的对话压成中文摘要，给同一个助手之后接着聊用。\n")
                .append("**必须按这四个小标题分段**（没有内容就写「无」）：\n")
                .append("【人物与关系】他是谁、在意谁、称呼与偏好\n")
                .append("【进行中的事】还没结束的事、卡在哪一步\n")
                .append("【已定结论】已经确定下来的决定与数值\n")
                .append("【待办】他要求过但还没做的事\n")
                .append("总共不超过 400 字；只写事实，不要编造、不要评价。\n")
                .append("对话里出现过明确计划的，原样保留在【进行中的事】里。\n\n");
        int chars = 0;
        for (ContextTurn turn : dropped) {
            if (turn == null || turn.text() == null || turn.text().isBlank()) {
                continue;
            }
            if (chars > 4000) {
                break;
            }
            String text = turn.text().replaceAll("\\s+", " ").trim();
            if (text.length() > 300) {
                text = text.substring(0, 300) + "…";
            }
            chars += text.length();
            prompt.append("assistant".equals(turn.role()) ? "助手：" : "他：").append(text).append('\n');
        }
        return prompt.toString();
    }

    /** 缓存标记：用最早那条消息 id；没有 id 就退化成文本哈希，保证"同一段对话只花一次" */
    private String cacheTag(List<ContextTurn> dropped) {
        ContextTurn first = dropped.get(0);
        if (first != null && first.sourceMessageIds() != null && !first.sourceMessageIds().isEmpty()) {
            return first.sourceMessageIds().get(0);
        }
        return Integer.toHexString(String.join("|",
                dropped.stream().map(ContextTurn::text).filter(t -> t != null).toList()).hashCode());
    }
}
