package com.liche.qqagent.controller;

import com.liche.qqagent.tool.ToolRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 工具维度统计（2026-10-26，P2-4）。
 *
 * <p>数据来源就是 `conversation_memory` 里的 `role=system` 行：
 * `tool=<名字> phase=call` 与 `tool=<名字> phase=result`（result 里带 `durationMs=`）。
 * 不新增表——这些行本来就是给"复盘一轮发生了什么"用的，这里只是把同一批数据聚合起来。
 *
 * <p>回答三个问题：**哪些工具在跑、哪些在失败、哪些从来没被用过**（后者在 64 个工具里最值得看）。
 */
@RestController
@RequestMapping("/api/admin/tools")
public class AdminToolStatsController {

    private final JdbcTemplate jdbc;
    private final ToolRegistry toolRegistry;

    public AdminToolStatsController(JdbcTemplate jdbc, ToolRegistry toolRegistry) {
        this.jdbc = jdbc;
        this.toolRegistry = toolRegistry;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestParam(defaultValue = "7") int days) {
        int span = Math.max(1, Math.min(90, days));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select content from conversation_memory "
                        + "where role = 'system' and content like 'tool=%phase=result%' "
                        + "and created_at > now() - (? || ' days')::interval",
                String.valueOf(span));

        Map<String, long[]> byTool = new LinkedHashMap<>();   // [calls, failures, totalMs]
        for (Map<String, Object> row : rows) {
            String content = String.valueOf(row.get("content"));
            String tool = toolNameOf(content);
            if (tool.isBlank()) {
                continue;
            }
            long[] stat = byTool.computeIfAbsent(tool, key -> new long[3]);
            stat[0]++;
            if (!content.contains("【工具执行成功】")) {
                stat[1]++;
            }
            stat[2] += durationOf(content);
        }

        List<Map<String, Object>> table = new ArrayList<>();
        byTool.forEach((tool, stat) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tool", tool);
            item.put("calls", stat[0]);
            item.put("failed", stat[1]);
            item.put("successRate", stat[0] == 0 ? "-" : String.format("%.0f%%", (stat[0] - stat[1]) * 100.0 / stat[0]));
            item.put("avgMs", stat[0] == 0 ? 0 : stat[2] / stat[0]);
            table.add(item);
        });
        table.sort(Comparator.comparingLong(item -> -((Number) item.get("calls")).longValue()));

        TreeSet<String> used = new TreeSet<>(byTool.keySet());
        TreeSet<String> never = new TreeSet<>();
        for (ToolRegistry.ToolMeta meta : toolRegistry.allMetadata()) {
            if (!used.contains(meta.name())) {
                never.add(meta.name());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("days", span);
        result.put("distinctTools", byTool.size());
        result.put("totalCalls", byTool.values().stream().mapToLong(stat -> stat[0]).sum());
        result.put("totalFailed", byTool.values().stream().mapToLong(stat -> stat[1]).sum());
        result.put("rows", table);
        result.put("neverUsed", new ArrayList<>(never));
        return result;
    }

    /** 从 "tool=xxx phase=result" 里抠出工具名 */
    private static String toolNameOf(String content) {
        if (content == null) {
            return "";
        }
        int at = content.indexOf("tool=");
        if (at < 0) {
            return "";
        }
        int start = at + 5;
        int end = content.indexOf(' ', start);
        if (end < 0) {
            end = content.indexOf('\n', start);
        }
        if (end < 0) {
            end = content.length();
        }
        return content.substring(start, Math.min(end, content.length())).trim();
    }

    /** 从 "durationMs=1234" 里抠耗时 */
    private static long durationOf(String content) {
        if (content == null) {
            return 0L;
        }
        int at = content.indexOf("durationMs=");
        if (at < 0) {
            return 0L;
        }
        int i = at + "durationMs=".length();
        int start = i;
        while (i < content.length() && Character.isDigit(content.charAt(i))) {
            i++;
        }
        if (i == start) {
            return 0L;
        }
        try {
            return Long.parseLong(content.substring(start, i));
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }
}
