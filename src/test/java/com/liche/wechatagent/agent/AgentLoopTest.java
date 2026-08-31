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

        assertEquals("已处理。\n\n> _调用工具：搜索最新资料_",
                AgentLoop.appendToolFooter("已处理。", tools));
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
