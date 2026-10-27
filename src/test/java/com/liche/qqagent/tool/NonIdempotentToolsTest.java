package com.liche.qqagent.tool;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "不可重复执行"标记（2026-10-28 补）。
 *
 * <p>规则：**产生外部可见或不可撤销副作用的工具必须标 `@NonIdempotentTool`**。
 * 不标就有两个后果：① `ToolInvocationService` 会在结果未知时重试（用户看到两遍）；
 * ② `ToolRegistry` 会把它当成可并行的只读工具。
 *
 * <p>这次补的两个是实测发现的：`sayToUser`（发出去的撤不回）、
 * `noteStoredMediaContent`（重试会插两行笔记）。
 */
class NonIdempotentToolsTest {

    @Test
    void sayToUserIsNotRepeatable() throws Exception {
        Method method = com.liche.qqagent.tool.SayTool.class.getMethod("sayToUser", String.class);
        assertTrue(method.isAnnotationPresent(NonIdempotentTool.class),
                "发出去的话撤不回来，必须禁止重试");
    }

    @Test
    void noteStoredMediaContentIsNotRepeatable() throws Exception {
        Method method = com.liche.qqagent.media.MediaMemoryTool.class
                .getMethod("noteStoredMediaContent", Long.class, String.class);
        assertTrue(method.isAnnotationPresent(NonIdempotentTool.class),
                "写笔记重试会插两行");
    }
}
