package com.liche.qqagent.memory;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 指代型查询的改写（2026-11-04）。
 *
 * <p><b>解决什么</b>：实测 250 题里有 **68% 的未命中是"纯指代"**——
 * 「我上次说的你还清楚吧」「我那晚到底在烦啥」。这类问题里**没有任何可检索的词**，
 * 向量、BM25、RRF、rerank 全都无从下手。53% 的召回率不是调参问题，是它们的问题。
 *
 * <p><b>怎么做</b>：判断它是不是指代型（短、含「这/那/上次/刚才/后来/它」、几乎没有名词），
 * 是的话用**最近几轮对话**把它补全成一句具体的查询，再拿去检索。
 *
 * <p><b>为什么只对指代型触发</b>：改写要多一次模型调用。正常问题（"6e206 是哪门课"）
 * 本来就能检索到，没有改写的必要——**只在有收益的地方花钱**。
 */
@Component
public class MemoryQueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(MemoryQueryRewriter.class);

    /** 指代型标记词：出现任意一个就认为"它自己没说清" */
    private static final List<String> REFERENTIAL = List.of(
            "上次", "上回", "刚才", "刚刚", "之前", "那次", "那件事", "那个", "那个事",
            "后来", "那次", "这事儿", "那事", "前面说", "跟你说过");

    private final boolean enabled;
    private final dev.langchain4j.model.chat.ChatModel chatModel;
    private final int maxChars;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private ConversationMemoryService conversationMemoryService;

    public MemoryQueryRewriter(
            @Value("${memory.rewrite.enabled:true}") boolean enabled,
            @Value("${memory.rewrite.max-chars:60}") int maxChars,
            org.springframework.beans.factory.ObjectProvider<dev.langchain4j.model.chat.ChatModel> chatModelProvider) {
        this.enabled = enabled;
        this.maxChars = Math.max(20, Math.min(200, maxChars));
        this.chatModel = chatModelProvider == null ? null : chatModelProvider.getIfAvailable();
    }

    public boolean enabled() {
        return enabled && chatModel != null;
    }

    /** 是不是"自己没说清"的问题 */
    public boolean referential(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String text = query.trim();
        for (String marker : REFERENTIAL) {
            if (text.contains(marker)) {
                return true;
            }
        }
        // 2026-11-04 踩过：原来用"很短 + 没数字"当判据，把"哈哈记得真好"这种**夸奖**
        // 也当成指代型去改写了（结果它去检索"恩儿 电话手表 521"，纯粹浪费一次调用）。
        // 现在只在**确实在追问**时才触发：短句 + 疑问词，或明确指向过去。
        boolean asking = text.matches(".*(吗|呢|啥|什么|哪|谁|多少|咋|怎么|还记得|记不记得|来着).*");
        boolean pointingBack = text.contains("上次") || text.contains("之前") || text.contains("那次")
                || text.contains("那件事") || text.contains("刚才") || text.contains("后来");
        return pointingBack || (text.length() <= 8 && asking);
    }

    /**
     * 需要就改写成具体查询，不需要就原样返回。
     *
     * <p>失败一律**退回原句**——改写是为了帮忙，不能变成新的故障点。
     */
    public String expand(String userId, String query) {
        if (!enabled() || !referential(query)) {
            return query;
        }
        String cached = cache.get(query);
        if (cached != null) {
            return cached;
        }
        String context = recentContext(userId);
        if (context.isBlank()) {
            return query;
        }
        try {
            String ask = "下面是用户和助手最近几轮对话：\n" + context + "\n\n"
                    + "用户现在说：「" + query + "」\n\n"
                    + "他这句话在指代前面聊过的事。请把他的话补全成一句**可以拿去检索记忆**的具体查询：\n"
                    + "写出他指的人名、时间、事件关键词（例如「恩儿 10月31号 不回消息 备胎」）。\n"
                    + "要求：" + maxChars + " 字以内；只输出这句查询本身，不要解释、不要引号。";
            String rewritten = com.liche.qqagent.config.LlmScenario.run(
                    com.liche.qqagent.config.LlmScenario.EXTRACT, () -> chatModel.chat(ask));
            if (rewritten == null || rewritten.isBlank()) {
                return query;
            }
            String cleaned = rewritten.strip().replaceAll("[\r\n]+", " ").trim();
            if (cleaned.length() > maxChars) {
                cleaned = cleaned.substring(0, maxChars);
            }
            if (cleaned.length() < 2) {
                return query;
            }
            cache.put(query, cleaned);
            log.info("记忆查询改写 user={} 原句=[{}] → [{}]", userId, query, cleaned);
            return cleaned;
        } catch (RuntimeException exception) {
            log.warn("记忆查询改写失败（用原句）：{}", exception.getMessage());
            return query;
        }
    }

    /** 最近几轮对话，拼成一段纯文本给改写用 */
    private String recentContext(String userId) {
        if (conversationMemoryService == null) {
            return "";
        }
        try {
            List<com.liche.qqagent.agent.ContextTurn> turns =
                    conversationMemoryService.recentForContext(userId, 3, 600);
            if (turns == null || turns.isEmpty()) {
                return "";
            }
            List<String> lines = new ArrayList<>();
            for (var turn : turns) {
                String text = turn.text();
                if (text == null || text.isBlank()) {
                    continue;
                }
                String role = "assistant".equals(turn.role()) ? "助手" : "用户";
                lines.add(role + "：" + (text.length() > 120 ? text.substring(0, 120) : text));
            }
            return String.join("\n", lines);
        } catch (RuntimeException exception) {
            return "";
        }
    }
}
