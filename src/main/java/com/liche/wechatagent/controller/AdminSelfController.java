package com.liche.wechatagent.controller;

import com.liche.wechatagent.self.AgentCommitment;
import com.liche.wechatagent.self.AgentSelfBlock;
import com.liche.wechatagent.self.AgentSelfEvent;
import com.liche.wechatagent.self.SelfService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自主模块的**只读**面板接口（编辑一律走对话，见 docs/self-layer-spec.md §10）。
 *
 * <p>用途：让"它自己那一侧"可见。一期只给四块：状态条 / 块 / 事件流 / 承诺账。
 */
@RestController
@RequestMapping("/api/admin/self")
public class AdminSelfController {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final SelfService selfService;

    public AdminSelfController(SelfService selfService) {
        this.selfService = selfService;
    }

    /** 状态条：模块开没开、归属人配没配、有多少东西。 */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("模块状态", selfService.isActive() ? "工作中" : "未工作（" + selfService.inactiveReason() + "）"));
        List<AgentSelfBlock> blocks = selfService.blocks();
        List<AgentCommitment> open = selfService.openCommitments();
        rows.add(row("自己的块", blocks.isEmpty() ? "（空）" : blocks.size() + " 个"));
        rows.add(row("未结承诺", String.valueOf(open.size())));
        rows.add(row("最近事件", selfService.recentEvents(20).size() + " 条（面板最多显示 200）"));
        Duration since = selfService.sinceLastEvent();
        rows.add(row("距上次动自己这边", since == null ? "还没有记录" : humanize(since)));
        return Map.of("rows", rows);
    }

    /** 它自己的块：类型 / 用量 / 版本 / 最后修改。 */
    @GetMapping("/blocks")
    public Map<String, Object> blocks() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AgentSelfBlock block : selfService.blocks()) {
            int used = block.getValue() == null ? 0 : block.getValue().length();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", block.getBlockType());
            row.put("usage", used + "/" + block.getCharLimit());
            row.put("version", "v" + block.getVersion());
            row.put("updatedAt", block.getUpdatedAt() == null ? "—" : block.getUpdatedAt().format(STAMP));
            row.put("value", block.getValue() == null ? "（空）" : block.getValue());
            rows.add(row);
        }
        return Map.of("rows", rows);
    }

    /** 它自己那侧的时间线（只追加，所以就是它的历史）。 */
    @GetMapping("/events")
    public Map<String, Object> events(@RequestParam(defaultValue = "50") int limit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AgentSelfEvent event : selfService.recentEvents(limit)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", "#" + event.getId());
            row.put("kind", event.getKind());
            row.put("content", event.getContent());
            row.put("evidence", event.getEvidence() == null ? "—" : event.getEvidence());
            row.put("createdAt", event.getCreatedAt() == null ? "—" : event.getCreatedAt().format(STAMP));
            rows.add(row);
        }
        return Map.of("rows", rows);
    }

    /** 账：许过的诺与预测。 */
    @GetMapping("/commitments")
    public Map<String, Object> commitments() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AgentCommitment commitment : selfService.openCommitments()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", "#" + commitment.getId());
            row.put("content", commitment.getContent());
            row.put("due", commitment.getDueAt() == null ? "—" : commitment.getDueAt().format(DAY));
            row.put("status", commitment.getStatus());
            rows.add(row);
        }
        return Map.of("rows", rows);
    }

    private Map<String, Object> row(String label, String value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", label);
        row.put("value", value);
        return row;
    }

    private String humanize(Duration duration) {
        long minutes = duration.toMinutes();
        if (minutes < 1) {
            return "刚刚";
        }
        if (minutes < 60) {
            return minutes + " 分钟前";
        }
        long hours = duration.toHours();
        return hours < 24 ? hours + " 小时前" : duration.toDays() + " 天前";
    }
}
