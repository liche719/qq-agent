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
}
