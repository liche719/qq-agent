package com.liche.wechatagent.agent;

import com.liche.wechatagent.media.MediaToolContextService;
import com.liche.wechatagent.network.PublicUrlValidator;
import com.liche.wechatagent.tool.ToolRegistry;
import com.liche.wechatagent.tool.ToolStatusService;
import com.liche.wechatagent.tool.ToolExecutionOutcome;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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

        assertEquals("已处理。\n\n> _调用工具：搜索_",
                AgentLoop.appendToolFooter("已处理。", tools));
    }

    @Test
    void acceptsConfiguredDisplayNamesForAdditionalDeployments() {
        Set<String> tools = new LinkedHashSet<>();
        tools.add("searchWeb");
        tools.add("customTool");
        Map<String, String> labels = Map.of("searchWeb", "联网检索", "customTool", "自定义动作");

        assertEquals("已处理。\n\n> _调用工具：联网检索、自定义动作_",
                AgentLoop.appendToolFooter("已处理。", tools, labels));
    }

    @Test
    void appendsFailureReasonAfterAutomaticRetry() {
        Map<String, ToolExecutionOutcome> failures = new LinkedHashMap<>();
        failures.put("readWebPage", ToolExecutionOutcome.failure("网页内容超过安全读取上限", 2));

        String result = AgentLoop.appendToolFailureNotice("我暂时无法读取该页面。", failures);

        assertEquals("我暂时无法读取该页面。\n\n⚠️ 工具调用未完成：读取网页已自动重试1次仍失败，原因：网页内容超过安全读取上限", result);
    }

    @Test
    void removesTrailingModelGeneratedToolDisclosureWhenProgramHasToolResults() {
        Set<String> tools = new LinkedHashSet<>();
        tools.add("searchWeb");
        String reply = "我找到了官方公告。\n\n**工具调用说明**\n- 调用了搜索工具\n- 已读取结果";

        assertEquals("我找到了官方公告。", AgentLoop.stripModelToolDisclosure(reply));
    }

    @Test
    void removesModelGeneratedToolDisclosureEvenWhenNoToolActuallySucceeded() {
        String reply = "工具调用说明：这是一段概念介绍。";

        assertEquals("", AgentLoop.stripModelToolDisclosure(reply));
    }

    @Test
    void removesMarkdownToolFooterGeneratedByTheModel() {
        String reply = "我先看看。\n\n> _调用工具：查询提醒_";

        assertEquals("我先看看。", AgentLoop.stripModelToolDisclosure(reply));
    }

    @Test
    void recognizesDirectCurrentTimeRequestsForMandatoryFreshness() {
        assertEquals(true, AgentLoop.requestsCurrentTime("现在几点了？"));
        assertEquals(true, AgentLoop.requestsCurrentTime("今天是星期几？"));
        assertEquals(false, AgentLoop.requestsCurrentTime("明天上午九点提醒我开会。"));
    }

    @Test
    void acceptsOnlyBoundedSupportedImageDataUrls() {
        AgentLoop loop = new AgentLoop(Mockito.mock(StreamingChatModel.class), Mockito.mock(ToolRegistry.class),
                Mockito.mock(ToolStatusService.class), Mockito.mock(MediaToolContextService.class),
                new PublicUrlValidator(), 4);

        assertEquals("data:image/png;base64,AA==", loop.downloadImageAsDataUrl("data:image/png;base64,AA=="));
        assertNull(loop.downloadImageAsDataUrl("data:text/html;base64,PGgxPk5vPC9oMT4="));
        assertNull(loop.downloadImageAsDataUrl("data:image/png;base64,MTIzNDU="));
    }
}
