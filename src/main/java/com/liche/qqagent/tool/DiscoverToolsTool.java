package com.liche.qqagent.tool;

import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 找工具的工具（2026-10-22）——渐进式披露的第二半。
 *
 * <p>默认只带核心工具（见 {@link ToolCatalog}），promptTokens 从 1.3 万降下来、首字更快；
 * 模型发现"手上的工具不够用"时调这个，按关键词搜到后**本轮立刻可用**。
 *
 * <p>为什么不一次给全：63 个工具的 schema 约 5–6k token，占第一跳输入近一半；
 * 而绝大多数对话只用得到十来个。按需解锁是拿"偶尔多一轮"换"每轮都更快"。
 */
@Component
public class DiscoverToolsTool implements AgentToolProvider {

    private static final Logger log = LoggerFactory.getLogger(DiscoverToolsTool.class);
    private static final int MAX_RESULTS = 12;

    private final org.springframework.beans.factory.ObjectProvider<ToolRegistry> registryProvider;

    /**
     * 用 {@link org.springframework.beans.factory.ObjectProvider} 延迟取：ToolRegistry 构造时要收集
     * 所有 AgentToolProvider（包括本工具），直接注入 ToolRegistry 会形成循环依赖，应用起不来。
     */
    public DiscoverToolsTool(org.springframework.beans.factory.ObjectProvider<ToolRegistry> registryProvider) {
        this.registryProvider = registryProvider;
    }

    @Tool(value = "缺工具时按关键词搜更多能力（搜到后本轮直接可用）。")
    public ToolBusinessResult discoverTools(
            @dev.langchain4j.agent.tool.P("要做什么，用自然语言几个词，例如「面试陪练」「定时任务」") String query) {
        List<ToolRegistry.ToolMeta> hits = registryProvider.getObject().allMetadata().stream()
                // 只搜"默认不常驻"的，避免把已经在手上的工具又列一遍
                .filter(meta -> !ToolCatalog.isCore(meta.providerClass())
                        || ToolCatalog.DISCOVER_TOOL.equals(meta.name()))
                .filter(meta -> ToolCatalog.matches(query, meta.name(), meta.description(),
                        ToolCatalog.categoryOf(meta.providerClass())))
                .limit(MAX_RESULTS)
                .toList();
        if (hits.isEmpty()) {
            log.info("findTools 没命中 query={}", query);
            return ToolBusinessResult.failure("没找到匹配的工具。换个说法再试，或者直接告诉用户这个能力暂时没有。");
        }
        ToolDiscovery.unlock(hits.stream().map(ToolRegistry.ToolMeta::name).toList());
        StringBuilder result = new StringBuilder("找到 ").append(hits.size()).append(" 个工具（已解锁，本轮直接可调）：\n");
        for (ToolRegistry.ToolMeta meta : hits) {
            result.append("- ").append(meta.name())
                    .append("（").append(ToolCatalog.categoryOf(meta.providerClass())).append("）：")
                    .append(clip(meta.description(), 60)).append('\n');
        }
        log.info("findTools 命中 query={} count={} tools={}", query, hits.size(),
                hits.stream().map(ToolRegistry.ToolMeta::name).toList());
        return ToolBusinessResult.success(result.toString());
    }

    private static String clip(String text, int max) {
        return com.liche.qqagent.support.Texts.clipFlat(text, max);
    }
}
