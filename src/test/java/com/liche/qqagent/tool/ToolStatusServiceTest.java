package com.liche.qqagent.tool;

import com.liche.qqagent.channel.ChatChannel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolStatusServiceTest {

    @Test
    void sendsAtMostOneStatusAcrossNestedAgentScope() {
        ChatChannel channel = mock(ChatChannel.class);
        when(channel.channel()).thenReturn("qq");
        ToolStatusService service = new ToolStatusService(List.of(channel));

        service.bind("u1", "message-1", "qq", "qq");
        service.push("正在读取文件…");
        service.bind("u1", "message-1", "qq", "qq");
        service.push("正在搜索…");
        service.unbind();
        service.unbind();

        verify(channel, times(1)).sendTextReplyFrom("qq", "u1", "message-1", "正在读取文件…");
    }
}
