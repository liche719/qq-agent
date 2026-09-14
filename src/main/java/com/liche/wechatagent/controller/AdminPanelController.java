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
 * {@code kind=descriptor} 的页签由后端给「区块描述」，前端用同一个通用组件渲染。支持的区块：
 * <ul>
 *   <li>{@code info} 键值（{@code {"rows":[{"label","value"}]}}）</li>
 *   <li>{@code table} 表格（{@code {"rows":[…]}}，列支持 wide/mono/tag，行内操作见 rowActions）</li>
 *   <li>{@code bars} 柱状（{@code {"items":[{"label","value"}]}}，unit 是数值后缀）</li>
 *   <li>{@code form} 表单（字段 text/textarea/number/date/select；提交 POST 字段值对象；initialEndpoint 预填）</li>
 *   <li>{@code actions} 按钮组（POST，响应 {@code {"message"}}；{@code accepted:false} 视为失败）</li>
 * </ul>
 *
 * <p><b>新模块只要在这里追加一个 descriptor + 自己的接口，就不用重新构建前端。</b>
 * 鉴权由 {@code AdminAccessFilter} 统一处理（{@code /api/admin/**} 都要口令）。
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
        tabs.add(examTab());
        tabs.add(selfTab());
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
    /** 自主模块：只读页签——「它自己那一侧」（编辑一律走对话，见 docs/self-layer-spec.md §10） */
    private Map<String, Object> selfTab() {
        Map<String, Object> tab = new LinkedHashMap<>();
        tab.put("key", "self");
        tab.put("label", "它自己");
        tab.put("kind", "descriptor");
        tab.put("sections", List.of(
                section("info", "状态", "/api/admin/self/overview", null),
                table("倾向（它一贯的样子）", "/api/admin/self/stances", List.of(
                        column("content", "倾向", true),
                        column("topic", "类别 / 方向", false),
                        column("evidence", "证据区间", true),
                        column("revised", "修订", false),
                        column("review", "复查（FSRS）", true),
                        column("formedAt", "形成于", false)), List.of()),
                table("它自己的块", "/api/admin/self/blocks", List.of(
                        column("type", "类型", false),
                        column("usage", "用量", false),
                        column("version", "版本", false),
                        column("updatedAt", "最后修改", false),
                        column("value", "内容", true)), List.of()),
                table("我欠着", "/api/admin/self/commitments", List.of(
                        column("id", "#", false),
                        column("content", "内容", true),
                        column("due", "截止", false),
                        column("status", "状态", false)), List.of()),
                table("分歧（它跟你意见不同的记录）", "/api/admin/self/disagreements", List.of(
                        column("id", "#", false),
                        column("topic", "类别", false),
                        column("content", "它主张什么", true),
                        column("outcome", "后来", false),
                        column("createdAt", "时间", false)), List.of()),
                table("反思与成本", "/api/admin/self/reflections?limit=20", List.of(
                        column("id", "#", false),
                        column("trigger", "触发", false),
                        column("conclusion", "它整合出的结论", true),
                        column("evidence", "读了哪几条", true),
                        column("cost", "成本", false),
                        column("createdAt", "时间", false)), List.of()),
                bars("每日变更量（近 14 天）", "/api/admin/self/changelog-bars?days=14", "处"),
                bars("反思花掉的 tokens（近 14 天）", "/api/admin/self/cost-bars?days=14", "tokens"),
                table("时间线", "/api/admin/self/events?limit=50", List.of(
                        column("id", "#", false),
                        column("kind", "类型", false),
                        column("content", "内容", true),
                        column("evidence", "证据", false),
                        column("createdAt", "时间", false)), List.of()),
                selfActions()));
        return tab;
    }

    /**
     * 「它自己」页签的操作：**只有排障用的手动触发**。
     * 编辑它的状态一律走对话（spec §10：单一通道，避免"面板改了它不知道"的两套真相）。
     */
    private Map<String, Object> selfActions() {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("kind", "actions");
        section.put("title", "排障");
        section.put("actions", List.of(
                action("立即反思一次", "/api/admin/self/reflect", "现在跑一次反思？会花一次模型调用（预算照常生效）。", null)));
        return section;
    }

    /** 考研模块：整个页签都由后端描述，前端不认识这个模块也能渲染出来 */
    private Map<String, Object> examTab() {
        Map<String, Object> tab = new LinkedHashMap<>();
        tab.put("key", "exam");
        tab.put("label", "考研");
        tab.put("kind", "descriptor");
        tab.put("sections", List.of(
                // 计划概览（只读）
                section("info", "备考计划", "/api/admin/exam/plan", null),
                // 计划可编辑（表单预填 + 提交）
                form("编辑计划", "/api/admin/exam/plan", "/api/admin/exam/plan/form", "保存计划",
                        "科目格式：科目名:目标分:每天分钟:每日计划，多个用分号分隔。想归组可以写「数据结构@408:45:90:王道一轮」。",
                        List.of(
                                field("examDate", "考试日期", "date", "2027-12-25", null),
                                field("school", "目标院校", "text", "例如 浙江大学", null),
                                field("major", "目标专业", "text", "例如 计算机技术（专硕）", null),
                                field("stage", "当前阶段", "select", null, List.of(
                                        option("BASIC", "基础"), option("INTENSIVE", "强化"), option("SPRINT", "冲刺"))),
                                field("dailyMinutes", "每天总时长（分钟）", "number", "例如 240", null),
                                field("subjects", "科目", "textarea", "数学二:120:120:高数一轮;数据结构@408:45:90:王道第3章", null),
                                field("remark", "备注", "text", "例如 目标总分 360", null))),
                // 今日任务（行内勾选）
                table("今日任务", "/api/admin/exam/tasks?date=today", List.of(
                        column("subject", "科目", false),
                        column("content", "内容", true),
                        tagColumn("status", "状态", Map.<String, Object>of(
                                "PENDING", tag("待完成", "warn"),
                                "DONE", tag("已完成", "ok"),
                                "SKIPPED", tag("已跳过", "muted"))),
                        column("plannedMinutes", "预计分钟", false),
                        column("note", "备注", false)), List.of(
                        rowAction("✓ 完成", "/api/admin/exam/tasks/status", Map.<String, Object>of("id", "$id", "status", "DONE")),
                        rowAction("跳过", "/api/admin/exam/tasks/status", Map.<String, Object>of("id", "$id", "status", "SKIPPED")),
                        rowAction("撤销", "/api/admin/exam/tasks/status", Map.<String, Object>of("id", "$id", "status", "PENDING")))),
                // 章节/轮次进度（行内推进）
                table("章节/轮次进度", "/api/admin/exam/progress", List.of(
                        column("group", "组", false),
                        column("subject", "科目", false),
                        column("title", "单元", true),
                        column("phase", "阶段", false),
                        column("progress", "完成量", true),
                        tagColumn("state", "状态", Map.<String, Object>of(
                                "ONGOING", tag("进行中", "info"),
                                "DONE", tag("已完成", "ok"),
                                "OVERDUE", tag("超期", "bad"))),
                        column("dueDate", "计划完成", false),
                        column("note", "备注", false)), List.of(
                        rowAction("+1", "/api/admin/exam/progress/bump", Map.<String, Object>of("id", "$id", "delta", "1")),
                        rowAction("+10", "/api/admin/exam/progress/bump", Map.<String, Object>of("id", "$id", "delta", "10")),
                        rowAction("做完", "/api/admin/exam/progress/bump", Map.<String, Object>of("id", "$id", "done", "$total"), "把这条标成已完成？"),
                        rowAction("删除", "/api/admin/exam/progress/delete", Map.<String, Object>of("id", "$id"), "删掉这条进度？"))),
                bars("各科完成率", "/api/admin/exam/progress/groups", "%"),
                // 进度录入
                form("记一条进度", "/api/admin/exam/progress", null, "保存进度",
                        "例：科目=高数，单元=第三章，总量=120，已完成=40，量词=题，计划完成=2027-03-31",
                        List.of(
                                field("subject", "科目", "text", "例如 高数 / 数据结构", null),
                                field("group", "归组（可空）", "text", "留空自动归组：数据结构→408、高数→数学", null),
                                field("phase", "阶段", "select", null, List.of(
                                        option("BASIC", "基础"), option("INTENSIVE", "强化"),
                                        option("SPRINT", "冲刺"), option("PAST_PAPER", "真题"))),
                                field("title", "单元名", "text", "例如 第三章 栈与队列", null),
                                field("total", "总量", "number", "例如 120", null),
                                field("done", "已完成", "number", "例如 40", null),
                                field("unit", "量词", "text", "章 / 题 / 讲 / 套", null),
                                field("dueDate", "计划完成日", "date", "2027-03-31", null))),
                // 里程碑（行内完成）
                table("阶段里程碑", "/api/admin/exam/milestones", List.of(
                        column("name", "里程碑", true),
                        column("dueDate", "截止日", false),
                        column("remainDays", "剩余天数", false),
                        tagColumn("state", "状态", Map.<String, Object>of(
                                "ONGOING", tag("进行中", "info"),
                                "DONE", tag("已完成", "ok"),
                                "OVERDUE", tag("已超期", "bad"))),
                        column("doneAt", "完成日", false),
                        column("note", "备注", false)), List.of(
                        rowAction("完成", "/api/admin/exam/milestones/done", Map.<String, Object>of("id", "$id", "done", "true")),
                        rowAction("撤销", "/api/admin/exam/milestones/done", Map.<String, Object>of("id", "$id", "done", "false")))),
                form("加里程碑", "/api/admin/exam/milestones", null, "保存里程碑",
                        "例：名称=基础一轮，截止日=2027-03-31；超期未完成会在早推送和周复盘里被点名",
                        List.of(
                                field("name", "名称", "text", "例如 408 一轮", null),
                                field("dueDate", "截止日", "date", "2027-06-30", null),
                                field("note", "备注", "text", "例如 王道四科过一遍", null))),
                // 错题本（行内复习结果）
                table("错题本", "/api/admin/exam/mistakes", List.of(
                        column("group", "组", false),
                        column("title", "错题/知识点", true),
                        column("stage", "轮次", false),
                        tagColumn("state", "状态", Map.<String, Object>of(
                                "DUE", tag("今天该复习", "warn"),
                                "WAITING", tag("等待回收", "muted"))),
                        column("nextReviewDate", "下次复习", false),
                        column("source", "来源", false)), List.of(
                        rowAction("✓ 记得", "/api/admin/exam/mistakes/review", Map.<String, Object>of("id", "$id", "result", "RIGHT")),
                        rowAction("✗ 又错", "/api/admin/exam/mistakes/review", Map.<String, Object>of("id", "$id", "result", "WRONG"), "记一次答错？"))),
                form("记一条错题", "/api/admin/exam/mistakes", null, "收进错题本",
                        "记下后按 1/3/7/15/30 天自动回收：答对往后推一轮，答错回到第一天",
                        List.of(
                                field("subject", "科目", "text", "例如 数据结构 / 高数", null),
                                field("title", "题目/知识点摘要", "text", "例如 快排最坏复杂度推导", null),
                                field("detail", "错在哪 / 正确思路", "textarea", null, null),
                                field("source", "来源", "text", "例如 王道 P123 / 660 题", null))),
                // 面板动作
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

    private Map<String, Object> bars(String title, String endpoint, String unit) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("kind", "bars");
        section.put("title", title);
        section.put("endpoint", endpoint);
        section.put("unit", unit);
        return section;
    }

    private Map<String, Object> table(String title, String endpoint, List<Map<String, Object>> columns,
                                      List<Map<String, Object>> rowActions) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("kind", "table");
        section.put("title", title);
        section.put("endpoint", endpoint);
        section.put("columns", columns);
        if (rowActions != null && !rowActions.isEmpty()) {
            section.put("rowActions", rowActions);
        }
        return section;
    }

    private Map<String, Object> form(String title, String endpoint, String initialEndpoint, String submitLabel,
                                     String hint, List<Map<String, Object>> fields) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("kind", "form");
        section.put("title", title);
        section.put("endpoint", endpoint);
        section.put("method", "POST");
        if (initialEndpoint != null) {
            section.put("initialEndpoint", initialEndpoint);
        }
        section.put("submitLabel", submitLabel);
        if (hint != null) {
            section.put("hint", hint);
        }
        section.put("fields", fields);
        return section;
    }

    private Map<String, Object> field(String key, String label, String type, String placeholder,
                                      List<Map<String, Object>> options) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("key", key);
        field.put("label", label);
        field.put("type", type);
        if (placeholder != null) {
            field.put("placeholder", placeholder);
        }
        if (options != null) {
            field.put("options", options);
        }
        return field;
    }

    private Map<String, Object> option(String value, String label) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("value", value);
        option.put("label", label);
        return option;
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

    private Map<String, Object> rowAction(String label, String endpoint, Map<String, Object> body) {
        return rowAction(label, endpoint, body, null);
    }

    private Map<String, Object> rowAction(String label, String endpoint, Map<String, Object> body, String confirm) {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("label", label);
        action.put("endpoint", endpoint);
        action.put("method", "POST");
        action.put("body", body);
        if (confirm != null) {
            action.put("confirm", confirm);
        }
        return action;
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
