package com.liche.qqagent.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具分类与"常驻"判定（2026-10-28 补测）。
 *
 * <p>⚠️ 这里锁住一个**容易踩的陷阱**：渐进式披露只下发"常驻类别"的工具，
 * 而类别来自 `CATEGORY_BY_PROVIDER`。**新加的工具类如果忘了在这个映射里登记**，
 * `categoryOf` 会返回"其它" → 不是常驻 → 这一轮**根本不会下发给模型**，
 * 表现是"工具明明注册了但模型说没有这个能力"，而且日志里只有一行"本轮带 N 个"。
 */
class ToolCatalogTest {

    @Test
    void everydayToolsAreCore() {
        assertTrue(ToolCatalog.isCore("SayTool"));
        assertTrue(ToolCatalog.isCore("SearchTool"));
        assertTrue(ToolCatalog.isCore("ReminderTool"));
        assertTrue(ToolCatalog.isCore("MediaMemoryTool"));
    }

    @Test
    void longTailToolsNeedDiscovery() {
        // 这些不常驻，靠 discoverTools 解锁
        assertFalse(ToolCatalog.isCore("InterviewTool"));
        assertFalse(ToolCatalog.isCore("ScheduledTaskTool"));
        assertFalse(ToolCatalog.isCore("AgentSelfTool"));
    }

    @Test
    void discoverToolItselfIsAlwaysCore() {
        // 否则"想找工具却连找的工具都没有"
        assertTrue(ToolCatalog.isCore(ToolCatalog.DISCOVER_TOOL.equals("discoverTools") ? "DiscoverToolsTool" : ""));
    }

    @Test
    void unknownProviderIsNotCore() {
        // 未登记的类会落到"其它"，不会被下发——新加工具类时必须同步登记
        assertEquals("其它", ToolCatalog.categoryOf("SomeNewTool"));
        assertFalse(ToolCatalog.isCore("SomeNewTool"));
        assertEquals("其它", ToolCatalog.categoryOf(null));
    }
}
