package com.liche.wechatagent.config;

/**
 * 一次 LLM 调用的用量——**中立视图**（2026-09-15 加，为"计费与模型解耦"）。
 *
 * <p>为什么要有这个 record：原来模型实现直接把自己的 cache 字段（DeepSeek 的
 * `prompt_cache_hit_tokens`）塞给具体的记账类，于是"怎么算钱"这件事**长在了模型实现里**——
 * 换一个模型实现（官方 SDK、别的厂商、本地模型）就没有计费了，而且模型类得认识"钱"。
 *
 * <p>现在分工是：**模型层只负责把响应里的 usage 归一化成这个结构**（字段名映射是它唯一要知道的事），
 * 谁想拿它做什么（记账、熔断、面板）由 {@link LlmUsageSink} 的实现决定。
 *
 * @param promptTokens     输入总量（含缓存命中）
 * @param completionTokens 输出（含思考——思考也算 output，坑 60）
 * @param reasoningTokens  其中思考的部分（仅用于展示）
 * @param cachedTokens     其中**缓存命中**的部分。命中价通常远低于未命中（DeepSeek Flash 是 1/50），
 *                         所以这一项是能不能算准钱的关键；服务端没给就是 0
 */
public record LlmUsage(int promptTokens, int completionTokens, int reasoningTokens, int cachedTokens) {

    public static final LlmUsage EMPTY = new LlmUsage(0, 0, 0, 0);

    /** 未命中的输入 = 总量 − 命中（服务端没给命中信息时就是全部） */
    public int uncachedTokens() {
        return Math.max(0, promptTokens - Math.max(0, cachedTokens));
    }

    public boolean isEmpty() {
        return promptTokens <= 0 && completionTokens <= 0;
    }
}
