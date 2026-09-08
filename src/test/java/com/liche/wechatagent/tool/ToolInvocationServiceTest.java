package com.liche.wechatagent.tool;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolInvocationServiceTest {
    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void alreadyInterruptedThreadDoesNotInvokeTool() throws Exception {
        InterruptingTool target = new InterruptingTool();
        Thread.currentThread().interrupt();
        invoke(target);
        assertEquals(0, target.calls);
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void toolInterruptionPreventsRetryAndPreservesInterrupt() throws Exception {
        InterruptingTool target = new InterruptingTool();
        invoke(target);
        assertEquals(1, target.calls);
        assertTrue(Thread.currentThread().isInterrupted());
    }

    private void invoke(InterruptingTool target) throws Exception {
        new ToolInvocationService(3, 8000, 1).invoke(
                InterruptingTool.class.getMethod("execute"), target,
                ToolExecutionRequest.builder().name("execute").arguments("{}").build(), "test-user");
    }

    public static class InterruptingTool {
        int calls;

        public String execute() throws InterruptedException {
            calls++;
            throw new InterruptedException("cancelled");
        }
    }
}
