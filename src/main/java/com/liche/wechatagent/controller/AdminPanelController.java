package com.liche.wechatagent.controller;

import com.liche.wechatagent.config.AlertProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 面板页签清单（让"加模块"不再需要改前端）。
 *
 * <p>{@code kind=core} 的页签由前端手写组件渲染（那 8 个核心页签内容差异太大，不适合通用化）；
 * {@code kind=descriptor} 的页签由后端给「区块描述」（info 键值 / table 表格 / bars 柱状 / actions 按钮），
 * 前端用同一个通用组件渲染。**新模块只要在这里追加一个 descriptor + 自己的接口，就不用重新构建前端。**
 *
 * <p>鉴权由 {@code AdminAccessFilter} 统一处理（{@code /api/admin/**} 都要口令）。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminPanelController {

    private final AlertProperties alertProperties;

    public AdminPanelController(AlertProperties alertProperties) {
        this.alertProperties = alertProperties;
    }

    @GetMapping("/panels")
    public Map<String, Object> panels() {
        List<Map<String, Object>> tabs = new ArrayList<>();
        tabs.add(core("overview", "总览"));
        tabs.add(core("qq", "QQ 通道"));
        tabs.add(core("llm", "模型与搜索"));
        tabs.add(core("maimemo", "背单词"));
        tabs.add(core("tasks", "任务"));
        tabs.add(core("scheduled", "定时任务"));
        tabs.add(lockedExamTab());
        tabs.add(core("users", "用户与记忆"));
        tabs.add(core("logs", "日志"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tabs", tabs);
        result.put("ownerUserId", ownerUserId());
        return result;
    }

    private Map<String, Object> core(String key, String label) {
        Map<String, Object> tab = new LinkedHashMap<>();
        tab.put("key", key);
        tab.put("label", label);
        tab.put("kind", "core");
        return tab;
    }

    /** 考研模块：整个页签都由后端描述，前端不认识这个模块也能渲染出来 */
    private Map<String, Object> lockedExamTab() {
        Map<String, Object> tab = new LinkedHashMap<>();
        tab.put("key", "exam");
        tab.put("label", "考研");
        tab.put("kind", "descriptor");
        tab.put("sections", List.of(
                section("info", "备考计划", "/api/admin/exam/plan", null),
                section("table", "今日任务", "/api/admin/exam/tasks?date=today", List.of(
                        column("subject", "科目", false),
                        column("content", "内容", true),
                        tagColumn("status", "状态", Map.of(
                                "PENDING", tag("待完成", "warn"),
                                "DONE", tag("已完成", "ok"),
                                "SKIPPED", tag("已跳过", "muted"))),
                        column("plannedMinutes", "预计分钟", false),
                        column("note", "备注", false))),
                section("bars", "近 7 天完成率", "/api/admin/exam/trend?days=7", null),
                section("table", "最近打卡", "/api/admin/exam/checkins?days=14", List.of(
                        column("date", "日期", false),
                        column("minutes", "学习分钟", false),
                        column("tasks", "任务", false),
                        column("note", "备注", true))),
                actions()));
        return tab;
    }

    private Map<String, Object> section(String kind, String title, String endpoint, Object columns) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("kind", kind);
        section.put("title", title);
        section.put("endpoint", endpoint);
        if ("bars".equals(kind)) {
            section.put("unit", "%");
        }
        if (columns != null) {
            section.put("columns", columns);
        }
        return section;
    }

    private Map<String, Object> column(String key, String label, boolean wide) {
        Map<String, Object> column = new LinkedHashMap<>();
        column.put("key", key);
        column.put("label", label);
        if (wide) {
            column.put("wide", true);
        }
        return column;
    }

    private Map<String, Object> tagColumn(String key, String label, Map<String, Object> tags) {
        Map<String, Object> column = column(key, label, false);
        column.put("tag", tags);
        return column;
    }

    private Map<String, Object> tag(String text, String tone) {
        Map<String, Object> tag = new LinkedHashMap<>();
        tag.put("text", text);
        tag.put("tone", tone);
        return tag;
    }

    private Map<String, Object> actions() {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("kind", "actions");
        section.put("title", "操作");
        section.put("actions", List.of(
                action("生成今日任务", "/api/admin/exam/tasks/generate", "按当前计划生成今天的任务？", null),
                action("立即推送一次", "/api/admin/exam/push", null, null),
                action("开启推送", "/api/admin/exam/toggle", null, Map.<String, Object>of("enabled", true)),
                action("关闭推送", "/api/admin/exam/toggle", null, Map.<String, Object>of("enabled", false))));
        return section;
    }

    private Map<String, Object> action(String label, String endpoint, String confirm, Map<String, Object> body) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("label", label);
        action.put("endpoint", endpoint);
        action.put("method", "POST");
        if (confirm != null) {
            action.put("confirm", confirm);
        }
        if (body != null) {
            action.put("body", body);
        }
        return action;
    }

    /** 面板操作归属的用户：QQ 私聊里 userId 就是 openid，直接复用运维告警里配置的本人 openid。 */
    private String ownerUserId() {
        String openid = alertProperties.getQqOpenid();
        return openid == null ? "" : openid.trim();
    }
}
