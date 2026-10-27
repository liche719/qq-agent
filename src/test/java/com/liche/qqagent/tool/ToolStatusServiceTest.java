package com.liche.qqagent.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 当前用户上下文（2026-10-28 去重：9 个工具类各写了一份 requireCurrentUser）。
 * 这里只测"取不到就抛"这条——它是工具在错误上下文里跑时的唯一防线。
 */
class ToolStatusServiceTest {

    @Test
    void returnsNullOutsideATurn() {
        ToolStatusService service = new ToolStatusService(java.util.List.of());
        assertNull(service.currentUserId(), "没绑定上下文时 currentUserId 返回 null");
    }

    @Test
    void requireFailsOutsideATurn() {
        ToolStatusService service = new ToolStatusService(java.util.List.of());
        IllegalStateException error = assertThrows(IllegalStateException.class, service::requireCurrentUserId);
        assertEquals("当前用户上下文不可用，无法执行该操作。", error.getMessage());
    }
}
