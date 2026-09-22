package com.liche.wechatagent.config;

/**
 * 一次 LLM 调用的**完整事实**：用量 + 场景 + 成败 + 耗时。
 *
 * <p>为什么在 {@link LlmUsage} 之外还要它：{@code LlmUsage} 只回答"用了多少 token"，
 * 而"哪一类调用花的钱、失败的调用是不是在烧钱"需要场景与成败。
 * 2026-09-21 之前这些信息只进了 {@code RuntimeMetrics}（进程内、重启归零），
 * 所以"最近花了多少"只能翻日志估；现在由 {@link LlmCallSink} 的实现落库。
 *
 * @param scenario   调用场景标签（如 {@code dialog} / {@code extract}），来自 {@code LlmScenario.label()}
 * @param streaming  是否流式（对话是流式，提取/反思是普通调用）
 * @param ok         这次调用成功没有
 * @param durationMs 端到端耗时
 * @param usage      归一化后的用量（含缓存命中）
 * @param error      失败原因；成功为 null
 */
public record LlmCallEvent(String scenario, boolean streaming, boolean ok, long durationMs,
                           LlmUsage usage, String error) {

    public LlmUsage usageOrEmpty() {
        return usage == null ? LlmUsage.EMPTY : usage;
    }
}
