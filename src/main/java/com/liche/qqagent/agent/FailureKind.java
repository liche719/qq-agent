package com.liche.qqagent.agent;

import java.util.Locale;

/**
 * 工具失败的类型（2026-10-25，用户要求"错误分类也要做"）。
 *
 * <p>之前只有一个 {@code failureReason} 字符串，所有失败长一个样——模型不知道"这是网络抖一下"
 * 还是"这个方法根本行不通"，只会反复重试。现在分三档，处理方式不同：
 *
 * <ul>
 *   <li>{@link #TRANSIENT} 瞬时/环境错误（超时、网络、限流、5xx）：**不该让模型反思**，
 *       代码层重试更便宜；重试仍失败才升级；</li>
 *   <li>{@link #FIXABLE} 参数/格式/查无结果这类**可就地修正**的错误：错误原样回传给模型最划算；</li>
 *   <li>{@link #STRATEGIC} 策略性失败（同一工具反复失败、轮次过半仍无进展）：**只有这类才值得独立反思**。</li>
 * </ul>
 */
public enum FailureKind {

    TRANSIENT,
    FIXABLE,
    STRATEGIC;

    private static final String[] TRANSIENT_HINTS = {
            "timeout", "timed out", "超时", "connection", "连接", "refused", "reset",
            "429", "502", "503", "504", "rate limit", "限流", "unavailable", "temporarily"
    };

    /**
     * 单次失败的分类：先看是不是瞬时错误，其余都算"可就地修正"。
     * {@link #STRATEGIC} 不是单次失败能判定的，由循环按"连续失败/轮次"决定。
     */
    public static FailureKind classify(String failureReason, int attempts) {
        String reason = failureReason == null ? "" : failureReason.toLowerCase(Locale.ROOT);
        for (String hint : TRANSIENT_HINTS) {
            if (reason.contains(hint)) {
                return TRANSIENT;
            }
        }
        // 内部重试过仍失败、但原因不是明确的环境问题：仍然按可就地修正处理，
        // 让模型自己换参数/换工具，比代码乱猜更靠谱。
        return FIXABLE;
    }
}
