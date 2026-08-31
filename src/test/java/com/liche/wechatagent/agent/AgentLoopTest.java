package com.liche.wechatagent.agent;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentLoopTest {

    @Test
    void appendsDistinctChineseToolNamesInInvocationOrder() {
        Set<String> tools = new LinkedHashSet<>();
        tools.add("searchWeb");
        tools.add("saveImportantMedia");
        tools.add("searchWeb");

        assertEquals("已处理。\n\n> _调用工具：搜索、保存文件_",
                AgentLoop.appendToolFooter("已处理。", tools));
    }

    @Test
    void leavesReplyUnchangedWhenNoToolsWereCalled() {
        assertEquals("普通回复", AgentLoop.appendToolFooter("普通回复", Set.of()));
    }

    @Test
    void findsTheCompleteToolFooterForFinalStreamFrame() {
        String reply = "回答内容。\n\n> _调用工具：搜索、保存文件_";

        assertEquals("回答内容。".length(), AgentLoop.toolFooterStart(reply));
    }

    @Test
    void displaysTheDedicatedLatestSearchToolInChinese() {
        Set<String> tools = new LinkedHashSet<>();
        tools.add("searchLatestWeb");

        assertEquals("已处理。\n\n> _调用工具：搜索最新资料_",
                AgentLoop.appendToolFooter("已处理。", tools));
    }
}
