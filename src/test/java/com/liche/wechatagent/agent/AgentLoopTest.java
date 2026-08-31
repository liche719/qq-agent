package com.liche.wechatagent.agent;

import com.liche.wechatagent.media.MediaToolContextService;
import com.liche.wechatagent.network.PublicUrlValidator;
import com.liche.wechatagent.tool.ToolRegistry;
import com.liche.wechatagent.tool.ToolStatusService;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.LinkedHashSet;
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
    void removesTrailingModelGeneratedToolDisclosureWhenProgramHasToolResults() {
        Set<String> tools = new LinkedHashSet<>();
        tools.add("searchWeb");
        String reply = "我找到了官方公告。\n\n**工具调用说明**\n- 调用了搜索工具\n- 已读取结果";

        assertEquals("我找到了官方公告。", AgentLoop.stripModelToolDisclosure(reply, tools));
    }

    @Test
    void keepsToolExplanationWhenNoToolActuallySucceeded() {
        String reply = "工具调用说明：这是一段概念介绍。";

        assertEquals(reply, AgentLoop.stripModelToolDisclosure(reply, Set.of()));
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
