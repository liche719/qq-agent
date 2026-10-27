package com.liche.qqagent.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 进度条那一行怎么拼（2026-10-27 从 AgentLoop 抽出）：用户直接看到这一行。 */
class ProgressNarratorTest {

    private final Map<String, String> names = Map.of("searchLatestWeb", "搜索", "readBilibiliVideo", "看B站视频");

    private static ToolExecutionRequest request(String name, String args) {
        return ToolExecutionRequest.builder().id("1").name(name).arguments(args).build();
    }

    @Test
    void usesDisplayNamePlusMostInformativeField() {
        assertEquals("搜索：广州天气",
                ProgressNarrator.label(request("searchLatestWeb", "{\"query\":\"广州天气\"}"), names));
        assertEquals("看B站视频：BV1xx",
                ProgressNarrator.label(request("readBilibiliVideo", "{\"bvid\":\"BV1xx\"}"), names));
    }

    @Test
    void truncatesLongDetailToKeepTheLineShort() {
        String longQuery = "一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十";
        String label = ProgressNarrator.label(request("searchLatestWeb", "{\"query\":\"" + longQuery + "\"}"), names);

        assertTrue(label.startsWith("搜索："));
        assertTrue(label.endsWith("…"));
        assertTrue(label.length() <= 3 + 25);
    }

    @Test
    void fallsBackToDisplayNameWhenArgumentsAreEmptyOrUnusable() {
        assertEquals("搜索", ProgressNarrator.label(request("searchLatestWeb", ""), names));
        assertEquals("搜索", ProgressNarrator.label(request("searchLatestWeb", "not-json"), names));
        assertEquals("unknown", ProgressNarrator.label(request("unknown", "{}"), names));
    }

    @Test
    void voiceAndSayToolsAreSilent() {
        assertTrue(ProgressNarrator.silent("sendVoice"));
        assertTrue(ProgressNarrator.silent("sayToUser"));
        assertFalse(ProgressNarrator.silent("searchLatestWeb"));
    }

    @Test
    void announceReportsEachToolOnlyOnce() {
        Set<String> announced = new java.util.HashSet<>();
        var req = request("searchLatestWeb", "{\"query\":\"a\"}");

        assertTrue(announced.add(req.name()));
        assertFalse(announced.add(req.name()));
    }
}

/** 追加用例：心跳只在"慢外部工具"且当前真的有进度通道时才起。 */
class ProgressNarratorHeartbeatTest {

    @org.junit.jupiter.api.Test
    void fastToolHasNoHeartbeat() {
        org.junit.jupiter.api.Assertions.assertNull(
                ProgressNarrator.heartbeat(initReq(), com.liche.qqagent.tool.ToolExecutionClass.FAST,
                        java.util.Map.of()));
    }

    @org.junit.jupiter.api.Test
    void slowToolWithoutProgressChannelHasNoHeartbeat() {
        // 没有绑定通道时（比如后台任务、测试）不能起定时任务，否则就是空转
        org.junit.jupiter.api.Assertions.assertNull(
                ProgressNarrator.heartbeat(initReq(), com.liche.qqagent.tool.ToolExecutionClass.SLOW_EXTERNAL,
                        java.util.Map.of()));
    }

    private static dev.langchain4j.agent.tool.ToolExecutionRequest initReq() {
        return dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                .id("1").name("readBilibiliVideo").arguments("{}").build();
    }
}
