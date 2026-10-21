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
 * @param explicitCostYuan 已知单价的调用（如 TTS 按字符、生图按张）直接给钱；为 null 时按 token 走账本算。
 *                   <p>为什么要有它：语音合成**没有 token**（按字符 0.8 元/万字符），
 *                   硬塞进 token 那张账本只能算出 0，面板就永远看不见这笔钱。
 */
public record LlmCallEvent(String scenario, boolean streaming, boolean ok, long durationMs,
                           LlmUsage usage, String error, java.math.BigDecimal explicitCostYuan) {

    /** 老调用方的便捷构造：按 token 计价的调用不需要显式金额 */
    public LlmCallEvent(String scenario, boolean streaming, boolean ok, long durationMs,
                        LlmUsage usage, String error) {
        this(scenario, streaming, ok, durationMs, usage, error, null);
    }

    public LlmUsage usageOrEmpty() {
        return usage == null ? LlmUsage.EMPTY : usage;
    }
}
