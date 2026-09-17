package com.liche.wechatagent.config;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.LongAdder;

/**
 * 进程内的用量读数：给"某一次调用花了多少 token"提供**前后差值**。
 *
 * <p>为什么需要它：模型层的接口只返回正文（{@code String}），调用方拿不到 usage。而"这次提取花了多少"
 * 是审计的第一问。用法与 {@link LlmSpendMeter} 一致——调用前后各取一次快照，相减就是这一次的账：
 *
 * <pre>
 *   Snapshot before = recorder.snapshot();
 *   ... chatModel.chat(...) ...
 *   Snapshot used = recorder.snapshot().minus(before);
 * </pre>
 *
 * <p>只是读数，<b>不参与计费</b>：钱由 {@link LlmCostLedger} 算（命中/未命中 + 峰谷），这里只回答 token 拆分。
 * 进程内累计，重启归零（要长期账请落库，见 {@code memory_extraction_run}）。
 */
@Component
public class LlmUsageRecorder implements LlmUsageSink {

    private final LongAdder promptTokens = new LongAdder();
    private final LongAdder completionTokens = new LongAdder();
    private final LongAdder reasoningTokens = new LongAdder();
    private final LongAdder cachedTokens = new LongAdder();

    @Override
    public void accept(LlmUsage usage) {
        if (usage == null || usage.isEmpty()) {
            return;
        }
        promptTokens.add(usage.promptTokens());
        completionTokens.add(usage.completionTokens());
        reasoningTokens.add(usage.reasoningTokens());
        cachedTokens.add(usage.cachedTokens());
    }

    public Snapshot snapshot() {
        return new Snapshot(promptTokens.sum(), completionTokens.sum(), reasoningTokens.sum(), cachedTokens.sum());
    }

    /** 某一刻的累计读数；两次相减就是这段时间里用掉的量 */
    public record Snapshot(long prompt, long completion, long reasoning, long cached) {

        public static final Snapshot ZERO = new Snapshot(0, 0, 0, 0);

        public Snapshot minus(Snapshot older) {
            if (older == null) {
                return this;
            }
            return new Snapshot(Math.max(0, prompt - older.prompt),
                    Math.max(0, completion - older.completion),
                    Math.max(0, reasoning - older.reasoning),
                    Math.max(0, cached - older.cached));
        }
    }
}
