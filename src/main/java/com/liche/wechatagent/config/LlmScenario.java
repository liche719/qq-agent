package com.liche.wechatagent.config;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * 一次 LLM 调用属于哪个场景——只用来决定「温度多少、max_tokens 给多少」，不影响业务语义。
 *
 * <p>**2026-09-14：不再按场景开关深度思考**（按用户要求整块删除，现在默认全部思考）。
 * 这个枚举留下来是因为温度与 max_tokens 仍按场景区分，指标也按场景分开记账。
 *
 * <p>为什么用 ThreadLocal 而不是给方法加参数：这几个调用点分散在四个 Service 里，而这些 Service 都带着
 * 多个给单测用的构造器（见坑 45，加构造器参数曾把线上打挂两个部署周期）。绑定/解绑只在同步调用前后发生，
 * 两个自研 ChatModel 都是在调用线程里同步读完响应，所以 ThreadLocal 是安全的。
 *
 * <p>没显式标注时按 {@link #DIALOG} 处理：对话是主路径，宁可保持默认，也不要因为漏标注而把某个场景
 * 悄悄套上结构化的 0 温度或 token 上限。
 */
public enum LlmScenario {

    /** 对话回复（流式） */
    DIALOG,
    /** 对话回复 + 模型自己申请了升档（thinkDeeper）：更多工具轮 + 更长的流式超时 */
    DIALOG_DEEP,
    /** 记忆提取 */
    EXTRACT,
    /**
     * 自主模块的反思（后台整合）。
     *
     * <p>**为什么要单独一档**：实测把它挂在 {@code EXTRACT} 上会整条失败——
     * 这个模型默认深度思考，而思考 token 也算进 max_tokens，
     * 结构化档默认 4096 会被思考**吃满**（日志 {@code promptTokens=420 completionTokens=4096 reasoningTokens=4096}），
     * 正文为空 → 反思什么都写不出来。所以反思单独给更宽松的上限。
     */
    REFLECT,
    /** 提醒解析 */
    REMINDER_PARSE,
    /** 定时任务解析（自然语言 → cron） */
    SCHEDULE_PARSE,
    /** 归档摘要 */
    ARCHIVE;

    private static final ThreadLocal<LlmScenario> CURRENT = new ThreadLocal<>();

    public static LlmScenario current() {
        LlmScenario scenario = CURRENT.get();
        return scenario == null ? DIALOG : scenario;
    }

    /** 在这个场景下执行一次同步 LLM 调用（退出时恢复上一层，支持嵌套） */
    public static <T> T run(LlmScenario scenario, Supplier<T> action) {
        LlmScenario previous = CURRENT.get();
        CURRENT.set(scenario);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** 配置里用小写下划线（extract / reminder_parse / schedule_parse / archive / dialog），认不出来就当 DIALOG */
    public static LlmScenario of(String name) {
        if (name == null || name.isBlank()) {
            return DIALOG;
        }
        String normalized = name.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (LlmScenario scenario : values()) {
            if (scenario.name().equals(normalized)) {
                return scenario;
            }
        }
        return DIALOG;
    }

    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }
}
