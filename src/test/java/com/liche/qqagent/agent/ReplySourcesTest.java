package com.liche.qqagent.agent;

import com.liche.qqagent.search.SearchTool;
import com.liche.qqagent.tool.ToolExecutionOutcome;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 参考来源（2026-10-27 从 AgentLoop 抽出）：拼来源、以及裁掉模型自己列的那份。 */
class ReplySourcesTest {

    private static Map<String, ToolExecutionOutcome> searchResult(String... links) {
        StringBuilder content = new StringBuilder();
        for (String link : links) {
            content.append(SearchTool.SOURCE_MARK).append(link).append('\n');
        }
        Map<String, ToolExecutionOutcome> results = new LinkedHashMap<>();
        results.put("searchLatestWeb", ToolExecutionOutcome.success(content.toString(), 1));
        return results;
    }

    @Test
    void appendsUpToFiveSources() {
        String reply = ReplySources.append("答案在这里", searchResult(
                "https://a.com/1", "https://a.com/2", "https://a.com/3",
                "https://a.com/4", "https://a.com/5", "https://a.com/6"));

        assertTrue(reply.startsWith("答案在这里"));
        assertTrue(reply.contains("> _参考来源_"));
        assertTrue(reply.contains("https://a.com/5"));
        assertFalse(reply.contains("https://a.com/6"), "最多 5 条");
    }

    @Test
    void nonSearchToolsAreIgnored() {
        Map<String, ToolExecutionOutcome> results = new LinkedHashMap<>();
        results.put("listReminders", ToolExecutionOutcome.success(SearchTool.SOURCE_MARK + "不应该出现", 1));
        assertEquals("普通回复", ReplySources.append("普通回复", results));
    }

    @Test
    void stripsModelWrittenSourceBlockAtTail() {
        String reply = "正文结论\n\n> _参考来源_\n> 模型自己抄的 1\n> 模型自己抄的 2";
        assertEquals("正文结论", ReplySources.stripEmbedded(reply));
    }

    @Test
    void keepsInlineQuoteThatIsNotATrailingBlock() {
        String reply = "> 这是引用的一句原文\n后面还有正文";
        assertEquals(reply, ReplySources.stripEmbedded(reply));
    }
}
