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
        tabs.add(core("usage", "花费"));
        tabs.add(core("bili", "B站"));
        tabs.add(core("tasks", "任务"));
        tabs.add(core("scheduled", "定时任务"));
        tabs.add(selfTab());
        tabs.add(memoryTab());
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

    /** 自主模块：只读页签——「它自己那一侧」（编辑一律走对话，见 docs/self-layer-spec.md §10） */
    private Map<String, Object> selfTab() {
        Map<String, Object> tab = new LinkedHashMap<>();
        tab.put("key", "self");
        tab.put("label", "它自己");
        tab.put("kind", "descriptor");
        tab.put("sections", List.of(
                section("info", "状态", "/api/admin/self/overview", null),
                
                
                
                table("它自己的块", "/api/admin/self/blocks", List.of(
                        column("type", "类型", false),
                        column("usage", "用量", false),
                        column("version", "版本", false),
                        column("updatedAt", "最后修改", false),
                        column("value", "内容", true)), List.of()),
                
                table("我的教训清单（它自己的可靠性）", "/api/admin/self/lessons", List.of(
                        column("id", "#", false),
                        column("category", "类别", false),
                        column("what", "三段（做了什么/预期/实际）", true),
                        column("correction", "以后怎么做", true),
                        column("recurrence", "复现/干净复查", false),
                        column("review", "复查（FSRS）", true),
                        column("status", "状态", false)), List.of()),
                bars("每周教训事件数（看趋势，不看绝对值）", "/api/admin/self/lesson-bars?weeks=8", "次"),
                table("它的工作区（它自己写的东西，全在 self-workspace/ 里）", "/api/admin/self/workspace", List.of(
                        column("path", "文件", true),
                        column("kind", "类型", false),
                        column("size", "大小", false),
                        column("modified", "改动时间", false)),
                        List.of(
                                Map.of(
                                        "label", "查看",
                                        "endpoint", "/api/admin/self/workspace/file",
                                        "method", "POST",
                                        "body", Map.of("path", "$path"),
                                        "showDetail", true),
                                Map.of(
                                        "label", "删除",
                                        "endpoint", "/api/admin/self/workspace/delete",
                                        "method", "POST",
                                        "body", Map.of("path", "$path"),
                                        "confirm", "真删了，没有回收站。确定删掉这个文件吗？"))),
                table("它刷过的视频（工具埋点 + 它自己写的摘要）", "/api/admin/self/bilibili-views", List.of(
                        column("at", "时间", false),
                        column("title", "链接", false),
                        column("video", "视频", true),
                        column("outcome", "读到了什么", false),
                        column("summary", "它记住了什么", true)), List.of()),
                table("它在 B 站说过的话（用的是机主的账号）", "/api/admin/self/bilibili-comments", List.of(
                        column("at", "时间", false),
                        column("video", "视频", true),
                        column("content", "评论原文", true),
                        column("result", "结果", false),
                        column("rpid", "评论号", false),
                        linkedColumn("link", "去看")), List.of()),
                table("它在后台忙的事（长任务：跑完会主动告诉你）", "/api/admin/self/background-tasks", List.of(
                        column("at", "什么时候交的", false),
                        column("goal", "要它做什么", true),
                        column("status", "现在", false),
                        column("took", "花了多久", false),
                        column("outcome", "结果", true)), List.of()),
                table("自主活动（它自己的时间花在哪）", "/api/admin/self/activities", List.of(
                        column("state", "状态", false),
                        column("activity", "活动", true),
                        column("today", "今天", false),
                        column("lastAt", "上次", false),
                        column("lastSummary", "上次干了什么", true)), List.of()),
                table("它自己的方向（不是给你办事的）", "/api/admin/self/quests", List.of(
                        column("id", "#", false),
                        column("title", "题目", true),
                        column("why", "它为什么想做这个", true),
                        column("next", "它写的下一步", true),
                        column("progress", "推进 / 笔记 / 撤回", false),
                        column("status", "状态", false),
                        column("time", "开于", false)), List.of()),
                table("它为自己写的笔记（带来源才算数）", "/api/admin/self/quest-notes", List.of(
                        column("id", "#", false),
                        column("quest", "方向", false),
                        column("content", "内容", true),
                        column("source", "来源", true),
                        column("state", "状态", false),
                        column("time", "时间", false)), List.of()),
                bars("每周为自己写的笔记（条数一直涨但从不撤回＝在堆料）",
                        "/api/admin/self/quest-bars?weeks=8", "条"),
                section("info", "它自己的预算（今天花了多少）", "/api/admin/self/budget", null),
                table("它想说、但没跟你说的话（口留着，不打扰你）", "/api/admin/self/utterances", List.of(
                        column("id", "#", false),
                        column("content", "它想说什么", true),
                        column("why", "为什么想说", true),
                        column("state", "状态", false),
                        column("time", "时间", false)), List.of()),
                table("它自己的时间（每次作业与花销）", "/api/admin/self/quest-runs?limit=20", List.of(
                        column("id", "#", false),
                        column("status", "结果", false),
                        column("summary", "它这一轮写了什么", true),
                        column("cost", "成本", false),
                        column("counts", "当时的笔记/撤回", false),
                        column("time", "时间", false)), List.of()),
                
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
                action("立即反思一次", "/api/admin/self/reflect", "现在跑一次反思？会花一次模型调用（预算照常生效）。", null),
                action("让它动一次自己的事", "/api/admin/self/quest/run",
                        "现在叫它去做一会儿自己的事？会花一次完整对话级的调用（预算与防抖照常生效）。", null),
                action("把攒着的话发一条", "/api/admin/self/speak/flush",
                        "现在把它想跟你说的话发一条出去？每日条数照常生效。", null)));
        return section;
    }

    /**
     * 「记忆」页签（2026-09-17）：把"到底提没提取成功"从猜变成可查。
     *
     * <p>除了「立刻提取一次」这个动作（2026-09-18 加，走调度器同一条路），其余都是只读——
     * 改记忆一律走工具/对话，写入路径共用同一道质量闸（见 docs/memory-hybrid-plan.md）。
     */
    private Map<String, Object> memoryTab() {
        Map<String, Object> tab = new LinkedHashMap<>();
        tab.put("key", "memory");
        tab.put("label", "记忆");
        tab.put("kind", "descriptor");
        tab.put("sections", List.of(
                memoryActions(),
                section("info", "提取账（今天）", "/api/admin/memory/overview", null),
                table("事实卡片（会变的信息，按「这件事」聚合）", "/api/admin/memory/facts?limit=80", List.of(
                        column("subject", "这件事", false),
                        column("values", "当前值（括号里是图片原值）", true),
                        column("sources", "来源", false),
                        column("count", "几条", false),
                        column("vector", "向量", false),
                        column("updated", "更新时间", false)), List.of()),
                table("提取记录（每次为什么写 / 为什么空）", "/api/admin/memory/runs?limit=50", List.of(
                        column("time", "时间", false),
                        column("trigger", "触发", false),
                        column("window", "窗口", false),
                        column("verdict", "模型判定", true),
                        column("reason", "为什么空", false),
                        column("tokens", "输入/输出", false),
                        column("cost", "花费", false),
                        column("duration", "用时", false)), List.of()),
                table("最近写入的记忆（留痕，可核对）", "/api/admin/memory/writes?limit=50", List.of(
                        column("time", "时间", false),
                        column("action", "动作", false),
                        column("layer", "层", false),
                        column("content", "内容（旧 → 新）", true),
                        column("operator", "谁写的", false),
                        column("reason", "原因", false)), List.of())));
        return tab;
    }

    /** 「记忆」页的操作：让用户在面板上主动整理一次（其余仍是只读） */
    private Map<String, Object> memoryActions() {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("kind", "actions");
        section.put("title", "手动整理");
        section.put("actions", List.of(
                action("立刻提取一次", "/api/admin/memory/extract",
                        "现在整理一次记忆？会花一次模型调用（约 0.02 元），10~40 秒跑完。", null)));
        return section;
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

    /** 一个渲染成可点外链的列（2026-10-21）：用它点开某条 B 站评论 */
    private Map<String, Object> linkedColumn(String key, String label) {
        Map<String, Object> column = new LinkedHashMap<>();
        column.put("key", key);
        column.put("label", label);
        column.put("link", true);
        return column;
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
