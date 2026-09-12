package com.liche.wechatagent.search;

import com.liche.wechatagent.tool.ToolStatusService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchToolTest {

    @Test
    void limitsResultsToConfiguredMaximumOfTen() {
        SearxngClient client = mock(SearxngClient.class);
        when(client.search("query", 10, null)).thenReturn(hits(12));
        SearchTool tool = new SearchTool(client, mock(WebPageTool.class), mock(ToolStatusService.class), 10, 10, 0, 1200, "Asia/Shanghai");

        String result = tool.searchWeb("query");

        assertEquals(10, result.lines().filter(line -> line.matches("\\d+\\. .*" )).count());
    }

    @Test
    void verifiedSearchClearlySeparatesCandidatesFromVerifiedFacts() {
        SearxngClient client = mock(SearxngClient.class);
        when(client.search("policy 2026年9月", 10, "year")).thenReturn(hits(3));
        SearchTool tool = new SearchTool(client, mock(WebPageTool.class), mock(ToolStatusService.class), 10, 10, 0, 1200, "Asia/Shanghai");

        String result = tool.searchVerifiedWeb("policy");

        assertTrue(result.contains("当前仅得到搜索候选和摘要，不代表事实已核实"));
        assertTrue(result.contains("独立发布者域名数"));
    }

    private List<SearxngClient.SearchHit> hits(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> new SearxngClient.SearchHit(
                        "https://source" + index + ".example/article-" + index,
                        "Title " + index, "Summary " + index, "2026-09-01"))
                .toList();
    }
}
