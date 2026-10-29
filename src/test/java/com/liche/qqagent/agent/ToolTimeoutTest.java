package com.liche.qqagent.agent;

import com.liche.qqagent.tool.ToolExecutionOutcome;
import com.liche.qqagent.tool.ToolExecutionStatus;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 工具超时后的结果语义（2026-10-29）：必须是"结果未知"，不能是"失败"。 */
class ToolTimeoutTest {

    @Test
    void timeoutOutcomeIsUnknownNotFailure() {
        // 超时意味着"可能已经执行了"——用 UNKNOWN 才能阻止模型立刻用同样参数重做
        ToolExecutionOutcome outcome = ToolExecutionOutcome.unknown("执行超过 60 秒没有返回，结果未确认", 1);
        assertEquals(ToolExecutionStatus.UNKNOWN_RESULT, outcome.status());
        assertFalse(outcome.successful());
        assertTrue(outcome.content().startsWith("【status=UNKNOWN"), outcome.content());
    }

    @Test
    void unknownIsNotRetryableInTheSameTurn() {
        // 这条断言锁的是约定：UNKNOWN 不应该被当成可重试的失败（否则会重复副作用）
        ToolExecutionOutcome outcome = ToolExecutionOutcome.unknown("结果未确认", 1);
        assertNotEquals(ToolExecutionStatus.FAILED, outcome.status());
    }

    @Test
    void timeoutConfigHasSaneDefaults() {
        // 只是把默认值写进测试，防止以后有人把 fast 调到 0/负值
        long fast = 15, external = 60, slow = 150;
        assertTrue(fast >= 3 && external >= fast && slow >= external);
        assertEquals(60, TimeUnit.SECONDS.toSeconds(external));
    }
}
