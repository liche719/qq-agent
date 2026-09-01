package com.liche.wechatagent.tool;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    @Test
    void retriesOnceAndReturnsSuccessWhenSecondAttemptWorks() {
        FlakyTool tool = new FlakyTool(1);
        ToolRegistry registry = new ToolRegistry(List.of(tool), 1);

        ToolExecutionOutcome outcome = registry.execute(request(), "user-a");

        assertTrue(outcome.successful());
        assertEquals(2, outcome.attempts());
        assertEquals(2, tool.calls.get());
        assertTrue(outcome.content().contains("第二次成功"));
    }

    @Test
    void returnsTheSecondFailureReasonAfterOneRetry() {
        FlakyTool tool = new FlakyTool(2);
        ToolRegistry registry = new ToolRegistry(List.of(tool), 1);

        ToolExecutionOutcome outcome = registry.execute(request(), "user-a");

        assertFalse(outcome.successful());
        assertEquals(2, outcome.attempts());
        assertEquals("外部服务拒绝请求", outcome.failureReason());
        assertTrue(outcome.content().contains("已自动重试 1 次"));
        assertTrue(outcome.content().contains("外部服务拒绝请求"));
        assertEquals(2, tool.calls.get());
    }

    @Test
    void doesNotRetryMalformedArguments() {
        FlakyTool tool = new FlakyTool(0);
        ToolRegistry registry = new ToolRegistry(List.of(tool), 1);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-1")
                .name("flaky")
                .arguments("{bad-json")
                .build();

        ToolExecutionOutcome outcome = registry.execute(request, "user-a");

        assertFalse(outcome.successful());
        assertEquals(0, outcome.attempts());
        assertEquals(0, tool.calls.get());
    }

    @Test
    void treatsBusinessNotCompletedAsFailureWithoutClaimingSuccess() {
        IncompleteTool tool = new IncompleteTool();
        ToolRegistry registry = new ToolRegistry(List.of(tool), 1);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-2")
                .name("create")
                .arguments("{}")
                .build();

        ToolExecutionOutcome outcome = registry.execute(request, "user-a");

        assertFalse(outcome.successful());
        assertEquals(1, outcome.attempts());
        assertEquals(1, tool.calls.get());
        assertEquals("还缺具体时间", outcome.failureReason());
        assertTrue(outcome.content().contains("工具执行失败"));
    }

    @Test
    void preservesPartialBusinessOutcomeStatus() {
        PartialTool tool = new PartialTool();
        ToolRegistry registry = new ToolRegistry(List.of(tool), 1);

        ToolExecutionOutcome outcome = registry.execute(ToolExecutionRequest.builder()
                .id("call-3").name("partial").arguments("{}").build(), "user-a");

        assertFalse(outcome.successful());
        assertEquals(ToolExecutionStatus.PARTIALLY_SUCCEEDED, outcome.status());
    }

    @Test
    void exposesDeclaredToolPolicyAtRuntime() {
        ToolRegistry registry = new ToolRegistry(List.of(new RiskyTool()), 1);

        ToolPolicySnapshot policy = registry.policy("risky");

        assertTrue(policy.hasSideEffect());
        assertEquals(ToolRiskLevel.HIGH, policy.riskLevel());
        assertFalse(policy.retryable());
    }

    @Test
    void rejectsHighRiskToolWithoutConfirmationParameterBeforeInvocation() {
        RiskyConfirmedTool tool = new RiskyConfirmedTool();
        ToolRegistry registry = new ToolRegistry(List.of(tool), 1);

        ToolExecutionOutcome outcome = registry.execute(ToolExecutionRequest.builder()
                .id("call-4").name("confirmed").arguments("{}").build(), "user-a");

        assertFalse(outcome.successful());
        assertEquals(0, outcome.attempts());
        assertEquals(0, tool.calls.get());
    }

    private ToolExecutionRequest request() {
        return ToolExecutionRequest.builder()
                .id("call-1")
                .name("flaky")
                .arguments("{\"value\":\"ok\"}")
                .build();
    }

    static class FlakyTool {
        private final int failures;
        private final AtomicInteger calls = new AtomicInteger();

        FlakyTool(int failures) {
            this.failures = failures;
        }

        @Tool("执行一个用于测试的易失败工具")
        public String flaky(String value) {
            int current = calls.incrementAndGet();
            if (current <= failures) {
                throw new IllegalStateException(current == 1 ? "第一次失败" : "外部服务拒绝请求");
            }
            return "第二次成功：" + value;
        }
    }

    static class IncompleteTool {
        private final AtomicInteger calls = new AtomicInteger();

        @Tool(name = "create", value = "创建测试任务")
        public ToolBusinessResult create() {
            calls.incrementAndGet();
            return ToolBusinessResult.failure("还缺具体时间");
        }
    }

    static class PartialTool {
        @Tool(name = "partial", value = "部分完成测试工具")
        public ToolBusinessResult partial() {
            return ToolBusinessResult.partial("下载成功但发送失败");
        }
    }

    static class RiskyTool {
        @Tool(name = "risky", value = "高风险测试工具")
        @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, hasSideEffect = true,
                destructive = true, retryable = false, riskLevel = ToolRiskLevel.HIGH)
        public String risky() {
            return "ok";
        }
    }

    static class RiskyConfirmedTool {
        private final AtomicInteger calls = new AtomicInteger();

        @Tool(name = "confirmed", value = "需确认测试工具")
        @ToolExecutionPolicy(value = ToolExecutionClass.EXTERNAL_ACTION, requiresConfirmation = true,
                confirmationParameter = "token", destructive = true, retryable = false,
                riskLevel = ToolRiskLevel.HIGH)
        public String confirmed(String token) {
            calls.incrementAndGet();
            return token;
        }
    }
}
