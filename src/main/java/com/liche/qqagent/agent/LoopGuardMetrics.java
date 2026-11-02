package com.liche.qqagent.agent;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 防循环相关的计数（2026-11-02）。
 *
 * <p>为什么需要：我们已经有"同参重复拦截"和"副作用工具幂等"，但**没有数据**——
 * 面试时只能说"我做了"，说不了"它拦了多少次、拦完有没有收敛"。
 *
 * <p>口径：
 * <ul>
 *   <li>{@code repeatGuardHits}：同参重复被拦下的次数（每命中一个签名算一次）；</li>
 *   <li>{@code actionDedupHits}：动作类工具"一轮只允许一次"被拦下的次数；</li>
 *   <li>{@code toolCalls} / {@code turns}：用来算"平均每轮调用几次工具"。</li>
 * </ul>
 */
@Component
public class LoopGuardMetrics {

    private final AtomicLong repeatGuardHits = new AtomicLong();

    private final AtomicLong actionDedupHits = new AtomicLong();

    private final AtomicLong toolCalls = new AtomicLong();

    private final AtomicLong turns = new AtomicLong();

    public void repeatGuardHit() {
        repeatGuardHits.incrementAndGet();
    }

    public void actionDedupHit() {
        actionDedupHits.incrementAndGet();
    }

    public void toolCall() {
        toolCalls.incrementAndGet();
    }

    public void turn() {
        turns.incrementAndGet();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        long calls = toolCalls.get();
        long turnCount = turns.get();
        result.put("repeatGuardHits", repeatGuardHits.get());
        result.put("actionDedupHits", actionDedupHits.get());
        result.put("toolCalls", calls);
        result.put("turns", turnCount);
        // 平均每轮调用几次工具：最能说明"有没有在原地打转"的单一指标
        result.put("avgToolCallsPerTurn", turnCount == 0 ? 0d : Math.round(calls * 100.0 / turnCount) / 100.0);
        return result;
    }
}
