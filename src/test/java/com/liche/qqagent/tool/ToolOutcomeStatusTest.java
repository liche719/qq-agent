package com.liche.qqagent.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具结果的**显式状态**（2026-10-29）。
 *
 * <p>原来只有中文标题，模型得自己从"失败/部分完成/结果无法确认"里体会差别。现在标题里带
 * {@code status=…}，它可以按字段分支——最要紧的是**"结果无法确认"不能被当成"失败"去重试**
 * （那会让用户收到两条一样的消息）。
 */
class ToolOutcomeStatusTest {

    @Test
    void successCarriesStatusAndIsSuccessful() {
        ToolExecutionOutcome outcome = ToolExecutionOutcome.success("正文", 1);
        assertTrue(outcome.successful());
        assertEquals(ToolExecutionStatus.SUCCEEDED, outcome.status());
        assertTrue(outcome.content().startsWith("【status=SUCCESS"), outcome.content());
    }

    @Test
    void failureCarriesFailed() {
        ToolExecutionOutcome outcome = ToolExecutionOutcome.failure("网络错误", 1);
        assertFalse(outcome.successful());
        assertEquals(ToolExecutionStatus.FAILED, outcome.status());
        assertTrue(outcome.content().startsWith("【status=FAILED"), outcome.content());
        assertTrue(outcome.content().contains("原因：网络错误"));
    }

    @Test
    void partialAndUnknownAndNeedsInputAreDistinct() {
        assertEquals(ToolExecutionStatus.PARTIALLY_SUCCEEDED, ToolExecutionOutcome.partial("半截", 1).status());
        assertEquals(ToolExecutionStatus.UNKNOWN_RESULT, ToolExecutionOutcome.unknown("没回音", 1).status());
        assertEquals(ToolExecutionStatus.NEEDS_INPUT, ToolExecutionOutcome.needsInput("几点的课？", 1).status());

        assertTrue(ToolExecutionOutcome.partial("半截", 1).content().startsWith("【status=PARTIAL"));
        assertTrue(ToolExecutionOutcome.unknown("没回音", 1).content().startsWith("【status=UNKNOWN"));
        assertTrue(ToolExecutionOutcome.needsInput("几点的课？", 1).content().startsWith("【status=NEEDS_INPUT"));
    }

    @Test
    void retriedFailureStillSaysFailed() {
        ToolExecutionOutcome outcome = ToolExecutionOutcome.failure("又错了", 3);
        assertEquals(ToolExecutionStatus.FAILED, outcome.status());
        assertTrue(outcome.content().contains("已自动重试 2 次"), outcome.content());
        assertTrue(outcome.content().startsWith("【status=FAILED"), outcome.content());
    }
}
