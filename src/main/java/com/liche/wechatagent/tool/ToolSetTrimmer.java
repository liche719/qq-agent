package com.liche.wechatagent.tool;

import dev.langchain4j.agent.tool.ToolSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 按**模块自己声明的规则**决定这一轮下发哪些工具。
 *
 * <p><b>2026-10-09 大改：把"按关键词裁掉 18 个考试工具"那套删了。</b>三条理由，都是实测出来的：
 * <ol>
 *   <li><b>它实际上几乎从不生效</b>：关键词表里有「学习 / 进度 / 计划 / 任务 / 数学 / 英语」这类日常高频词，
 *       而且"该用户已有备考计划"就直接 early return —— 生产上用户有计划，永远走不到裁剪那一步。
 *       也就是说它是"看起来在工作、实际没在工作"的机制（还带一个 early-return 顺序的坑）。</li>
 *   <li><b>裁掉省不到钱</b>：工具 schema 基本都在前缀缓存里（对话命中率 82%+），
 *       裁掉它们省下的钱不到单轮成本的 3%，剩下的只有几百毫秒首字延迟。</li>
 *   <li><b>硬编码关键词会误裁</b>，而误裁的代价是"模型说我没有这个能力"——比多带几个工具严重得多。</li>
 * </ol>
 * 所以结论是：**与其每轮动态猜，不如静态删掉确认死掉的工具**。同一天按这个思路删掉了 `thinkDeeper`
 * （5 周零调用 + 面板「思考强度」滑块已替代它）。
 *
 * <p><b>但考试那 10 个长尾最后没删</b>——查下去发现两件事推翻了原计划：① `ExamService` 的推送里
 * 明写着「复习完说『错题 #编号 记得』」，也就是**推送在叫用户去调那些工具**；② 错题本本来就有独立的
 * 中文指令处理器（{@code ExamMistakeHandler}），删掉 LLM 工具并不会删掉功能，只会让**模型比指令层更无能**。
 * 盘点与理由见 {@code docs/tools-and-prompt-inventory.md}。
 * 工具该不该存在，是一次性判断；不该每轮再猜一遍。
 *
 * <p>它现在只做一件事：依次问所有 {@link ToolVisibilityRule}（模块自己声明"我这些工具在什么作用域下不下发"）。
 * 裁剪器**不认识任何业务模块**——模块关掉时它的规则 bean 不存在，这里就少问一条。
 */
@Component
public class ToolSetTrimmer {

    private static final Logger log = LoggerFactory.getLogger(ToolSetTrimmer.class);

    // 「哪些工具在什么作用域下不下发」不写在这里：那是**模块自己的事**，
    // 由 ToolVisibilityRule 的实现声明（如 SelfToolVisibilityRule），本类只负责依次问。

    /** 模块自己声明的可见性规则（一个实现都没有时 = 一个工具都不裁） */
    private final List<ToolVisibilityRule> visibilityRules;
    private final boolean enabled;

    public ToolSetTrimmer(List<ToolVisibilityRule> visibilityRules,
                          @Value("${agent.tool-trim.enabled:true}") boolean enabled) {
        this.visibilityRules = visibilityRules == null ? List.of() : visibilityRules;
        this.enabled = enabled;
    }

    public record TrimResult(List<ToolSpecification> specifications, Set<String> hidden, String reason) {
    }

    /**
     * @param all    当前全部工具
     * @param userId 当前用户；**拿不到用户时一个都不裁**（保守全给，宁可多带也不让功能静默失效）
     */
    public TrimResult trim(List<ToolSpecification> all, String userId) {
        if (!enabled || all == null || all.isEmpty()) {
            return new TrimResult(all, Set.of(), "未启用");
        }
        if (userId == null || userId.isBlank()) {
            return new TrimResult(all, Set.of(), "拿不到用户，保守全给");
        }
        Set<String> hidden = new LinkedHashSet<>();
        List<ToolSpecification> kept = applyVisibilityRules(all, userId, hidden);
        if (hidden.isEmpty()) {
            return new TrimResult(kept, hidden, "没有需要藏起来的工具");
        }
        // 独立打一行：出问题时"哪个工具被藏了、为什么"要能一眼从日志看出来
        log.info("本轮工具集按模块规则裁掉 {} 个：{}", hidden.size(), String.join("、", hidden));
        return new TrimResult(kept, hidden, "按模块声明的规则");
    }

    /**
     * 依次问所有 {@link ToolVisibilityRule}（模块自己声明的），把要藏的工具摘掉。
     *
     * <p>单条规则抛异常只记日志跳过——拔掉一个模块不该让整轮对话下不出去工具。
     *
     * @param hidden 出参：被摘掉的名字
     */
    private List<ToolSpecification> applyVisibilityRules(List<ToolSpecification> all, String userId,
                                                         Set<String> hidden) {
        if (visibilityRules == null || visibilityRules.isEmpty()) {
            return all;
        }
        for (ToolVisibilityRule rule : visibilityRules) {
            Set<String> names;
            try {
                names = rule.hiddenFor(userId, all);
            } catch (RuntimeException exception) {
                log.warn("工具可见性规则「{}」出错（跳过这条）：{}", rule.name(), exception.getMessage());
                continue;
            }
            if (names != null && !names.isEmpty()) {
                hidden.addAll(names);
            }
        }
        if (hidden.isEmpty()) {
            return all;
        }
        List<ToolSpecification> base = new ArrayList<>(all.size());
        for (ToolSpecification spec : all) {
            if (spec.name() == null || !hidden.contains(spec.name())) {
                base.add(spec);
            }
        }
        return base;
    }
}
