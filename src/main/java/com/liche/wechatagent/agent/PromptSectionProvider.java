package com.liche.wechatagent.agent;

/**
 * 「我能往系统提示词里加一段」的扩展点（插件挂点）。
 *
 * <p>为什么要有它：自留地、以后的产出层都需要往提示词里塞东西。如果每加一个模块就去改
 * {@code AgentPromptBuilder} 和 {@code AgentOrchestrator}，模块就永远拔不干净。
 * 现在核心只收集所有实现类、按 {@code order} 拼装——**模块加进来/拔出去都不用动核心**。
 *
 * <p>约定：
 * <ul>
 *   <li>返回 {@code null} 或 body 为空 → 该段整块不出现（不要返回"（空）"这种占位文字）</li>
 *   <li>实现类必须自己控制长度（核心不替它截断），并遵守提示词既有的语气与规则编号</li>
 *   <li>实现类建议标 {@code @ConditionalOnProperty}，这样能用开关把整个模块拔掉</li>
 * </ul>
 */
public interface PromptSectionProvider {

    /** 当前用户这一轮的动态段落；没有内容就返回 null。 */
    PromptSection section(String userId);

    /**
     * 带上**这一轮用户消息**的版本：只有"得看当前场景才决定要不要注入"的模块才需要它
     * （例如教训清单只在同类场景提示，不做全局唠叨）。
     * 默认忽略消息、退回到 {@link #section(String)}，所以既有实现一行都不用改。
     */
    default PromptSection section(String userId, String userMessage) {
        return section(userId);
    }
}
