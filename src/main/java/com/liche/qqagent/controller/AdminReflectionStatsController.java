package com.liche.qqagent.controller;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 反思效果（2026-10-27，P1-1 后半）。
 *
 * <p>只统计"触发过反思的轮次"：`turn_outcome=SUCCESS` 表示这一轮最终给出了回复，
 * `FAILED` 表示走到兜底（轮次/预算耗尽）。它回答的问题很直接：
 * **反思之后任务成功率是多少？重规划是不是更管用？**
 *
 * <p>注意口径：这是"这一轮有没有正常收尾"，不是"用户的请求有没有被满足"——后者需要人工或判分模型。
 */
@RestController
@RequestMapping("/api/admin/reflections")
public class AdminReflectionStatsController {

    private final JdbcTemplate jdbc;

    public AdminReflectionStatsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestParam(defaultValue = "30") int days) {
        int span = Math.max(1, Math.min(365, days));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select failure_type, failure_kind, turn_outcome, count(*) as n from task_reflection "
                        + "where created_at > now() - (? || ' days')::interval group by 1, 2, 3",
                String.valueOf(span));

        Map<String, long[]> byType = new LinkedHashMap<>();   // [total, success, replan]
        long total = 0;
        long success = 0;
        long pending = 0;
        for (Map<String, Object> row : rows) {
            String type = String.valueOf(row.get("failure_type"));
            String outcome = String.valueOf(row.get("turn_outcome"));
            long n = ((Number) row.get("n")).longValue();
            long[] stat = byType.computeIfAbsent(type == null || type.isBlank() ? "(未分类)" : type,
                    key -> new long[2]);
            stat[0] += n;
            if ("SUCCESS".equalsIgnoreCase(outcome)) {
                stat[1] += n;
                success += n;
            }
            if (outcome == null || "null".equalsIgnoreCase(outcome)) {
                pending += n;
            }
            total += n;
        }

        List<Map<String, Object>> table = new ArrayList<>();
        byType.forEach((type, stat) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("failureType", type);
            item.put("count", stat[0]);
            item.put("succeeded", stat[1]);
            item.put("successRate", stat[0] == 0 ? "-" : String.format("%.0f%%", stat[1] * 100.0 / stat[0]));
            table.add(item);
        });
        table.sort((a, b) -> Long.compare(((Number) b.get("count")).longValue(),
                ((Number) a.get("count")).longValue()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("days", span);
        result.put("records", total);
        result.put("succeeded", success);
        result.put("pendingOutcome", pending);
        result.put("successRate", total == 0 ? "-" : String.format("%.0f%%", success * 100.0 / total));
        result.put("rows", table);
        return result;
    }
}
