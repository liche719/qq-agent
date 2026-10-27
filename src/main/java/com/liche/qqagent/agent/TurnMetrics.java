package com.liche.qqagent.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 延迟埋点的输出（2026-10-27 从 {@code AgentLoop} 拆出，第 4 步）。
 *
 * <p>拆的理由：埋点日志的**格式**（字段名、单位）是要被日志检索和面板消费的，
 * 散在 1400 行的循环里改一次容易漏。集中成两个方法后，想加字段只改一处。
 *
 * <p>当前两个指标：
 * <ul>
 *   <li><b>首字延迟</b>：第一次 LLM 调用到第一个 token（**不含工具耗时**）——用户体感的核心；</li>
 *   <li><b>首跳 prompt 大小</b>：输入 token 与工具 schema 数量——判断"慢在提示词还是慢在思考"。</li>
 * </ul>
 */
public final class TurnMetrics {

    private static final Logger log = LoggerFactory.getLogger(TurnMetrics.class);

    private TurnMetrics() {
    }

    /**
     * 每次模型调用的 prompt 规模（第 1 跳和后续跳都会打）。
     *
     * @param round 第几轮（从 0 开始）；0 才是"首跳"
     */
    public static void logCall(String userId, int round, Integer inputTokens, Integer outputTokens, int toolSpecs) {
        if (inputTokens == null) {
            return;
        }
        log.info("模型调用 tokens user={} 第{}轮 输入={} 输出={} 工具 schema={}个",
                userId, round, inputTokens, outputTokens == null ? 0 : outputTokens, toolSpecs);
    }

    /** 一轮结束的延迟：首字（第一次 LLM 调用→第一个 token）与总时长（含工具） */
    public static void logTurn(String userId, long firstTokenMillis, long totalMillis, int toolCount) {
        log.info("轮次延迟 user={} 首字={}ms 总={}ms 工具={}个",
                userId, firstTokenMillis, totalMillis, toolCount);
    }
}
