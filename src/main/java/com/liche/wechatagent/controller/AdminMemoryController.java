package com.liche.wechatagent.controller;

import com.liche.wechatagent.config.AlertProperties;
import com.liche.wechatagent.memory.MemoryChangeLog;
import com.liche.wechatagent.memory.MemoryChangeLogRepository;
import com.liche.wechatagent.memory.MemoryExtractionRun;
import com.liche.wechatagent.memory.MemoryExtractionRunRepository;
import com.liche.wechatagent.memory.MemoryFact;
import com.liche.wechatagent.memory.MemoryFactRepository;
import com.liche.wechatagent.memory.MemoryFactService;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「记忆」页签（descriptor 面板，2026-09-17）。
 *
 * <p>回答两个以前只能靠翻日志猜的问题：**① 提取到底跑没跑成功、为什么什么都没写**（{@code memory_extraction_run} 审计），
 * **② 最近到底写了哪些记忆**（{@code memory_change_log} 留痕）。
 *
 * <p>只读：编辑记忆一律走工具/对话（见 docs/memory-hybrid-plan.md，写入路径要共用同一道质量闸）。
 */
@RestController
@RequestMapping("/api/admin/memory")
public class AdminMemoryController {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");

    private final MemoryExtractionRunRepository runs;
    private final MemoryChangeLogRepository changeLogs;
    private final MemoryFactRepository facts;
    private final MemoryFactService factService;
    private final AlertProperties alertProperties;

    public AdminMemoryController(MemoryExtractionRunRepository runs,
                                 MemoryChangeLogRepository changeLogs,
                                 MemoryFactRepository facts,
                                 MemoryFactService factService,
                                 AlertProperties alertProperties) {
        this.runs = runs;
        this.changeLogs = changeLogs;
        this.facts = facts;
        this.factService = factService;
        this.alertProperties = alertProperties;
    }

    /** 今天的提取账：跑了几次、空了几次、花了多少、最近一次为什么空 */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        LocalDateTime today = LocalDate.now().atStartOfDay();
        List<MemoryExtractionRun> all = runs.findByCreatedAtAfterOrderByCreatedAtDesc(today);
        int empty = 0;
        int failed = 0;
        double cost = 0d;
        Map<String, Integer> reasons = new LinkedHashMap<>();
        for (MemoryExtractionRun run : all) {
            cost += run.getCostYuan() == null ? 0d : run.getCostYuan().doubleValue();
            if (run.getSkipReason() != null) {
                reasons.merge(run.getSkipReason(), 1, Integer::sum);
                if ("FAILED".equals(run.getSkipReason())) {
                    failed++;
                } else {
                    empty++;
                }
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("今日提取", all.size() + " 次"));
        rows.add(row("其中空跑", empty + " 次（跳过 " + skipDetail(reasons) + "）"));
        rows.add(row("失败", failed + " 次"));
        rows.add(row("今日提取花费", String.format("%.4f 元", cost)));
        rows.add(row("今日写入记忆", todayWrites(today) + " 条"));
        if (!ownerUserId().isBlank()) {
            rows.add(row("事实（会变的信息）", factService.countActive(ownerUserId()) + " 条有效，缺向量 "
                    + missingVectors() + " 条"));
        }
        if (!all.isEmpty()) {
            MemoryExtractionRun last = all.get(0);
            rows.add(row("最近一次", last.getCreatedAt().format(STAMP)
                    + (last.getSkipReason() == null ? "（正常跑完）" : "（" + last.getSkipReason() + "）")));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        return result;
    }

    /** 提取记录：每次都留一行（含跳过与失败） */
    @GetMapping("/runs")
    public Map<String, Object> runs(@RequestParam(defaultValue = "50") int limit) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MemoryExtractionRun run : bounded(limit)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("time", run.getCreatedAt() == null ? "-" : run.getCreatedAt().format(STAMP));
            item.put("trigger", trigger(run.getTriggerSource()));
            item.put("window", run.getWindowTurns() + " 轮 / " + run.getWindowChars() + " 字");
            item.put("verdict", run.getVerdictJson() == null ? "-" : run.getVerdictJson());
            item.put("reason", run.getSkipReason() == null ? "—" : reason(run.getSkipReason()));
            item.put("tokens", (run.getPromptTokens() == null ? 0 : run.getPromptTokens()) + " / "
                    + (run.getCompletionTokens() == null ? 0 : run.getCompletionTokens())
                    + (run.getReasoningTokens() == null || run.getReasoningTokens() == 0
                    ? "" : "（思考 " + run.getReasoningTokens() + "）"));
            item.put("cost", String.format("%.4f 元", run.getCostYuan() == null ? 0d : run.getCostYuan().doubleValue()));
            item.put("duration", (run.getDurationMs() == null ? 0 : run.getDurationMs()) + " ms");
            rows.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        return result;
    }

    /** 最近写入的记忆（来自变更留痕；内容为空 = 用户遗忘后的无正文事件） */
    @GetMapping("/writes")
    public Map<String, Object> writes(@RequestParam(defaultValue = "50") int limit) {
        String userId = ownerUserId();
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!userId.isBlank()) {
            for (MemoryChangeLog entry : changeLogs.findByUserIdOrderByCreatedAtDesc(userId,
                    PageRequest.of(0, Math.max(1, Math.min(100, limit))))) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("time", entry.getCreatedAt() == null ? "-" : entry.getCreatedAt().format(STAMP));
                item.put("action", action(entry.getAction()));
                item.put("layer", layer(entry.getLayer()));
                item.put("content", content(entry));
                item.put("operator", operator(entry.getOperator()));
                item.put("reason", entry.getReason() == null ? "—" : entry.getReason());
                rows.add(item);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        return result;
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 事实层（v2，2026-09-18）：会变的信息一条一句地躺在这里，取代关系用状态列表示。
     * 基线（DOC=从图片/文件看出来的）与补丁（USER=用户说的）分开显示——"图上写 303、他后来补充 305"要看得见。
     */
    @GetMapping("/facts")
    public Map<String, Object> facts(@RequestParam(defaultValue = "80") int limit) {
        String userId = ownerUserId();
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!userId.isBlank()) {
            java.util.Set<Long> embedded = factService.embeddedIds(userId);
            for (MemoryFact fact : facts.findByUserIdOrderByUpdatedAtDesc(userId,
                    PageRequest.of(0, Math.max(1, Math.min(200, limit))))) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("subject", clip(fact.getSubject(), 40));
                item.put("predicate", fact.getPredicate() == null ? "-" : fact.getPredicate());
                item.put("object", clip(fact.getObject(), 80));
                item.put("source", source(fact.getSource()));
                item.put("status", status(fact.getStatus()));
                item.put("vector", embedded.contains(fact.getId()) ? "有" : "无");
                item.put("updated", fact.getUpdatedAt() == null ? "-" : fact.getUpdatedAt().format(STAMP));
                rows.add(item);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        return result;
    }

    /** 缺向量的事实条数（用户是先有事实、后填的 api-key，所以这个数要能看见并会自己回落到 0） */
    private long missingVectors() {
        String userId = ownerUserId();
        if (userId.isBlank()) {
            return 0L;
        }
        try {
            return factService.countMissingEmbedding(userId);
        } catch (Exception e) {
            return 0L;
        }
    }

    private String source(String raw) {
        return switch (raw == null ? "" : raw) {
            case "DOC" -> "图片/文件（基线）";
            case "USER" -> "用户说的（补丁）";
            case "AUTO" -> "推断";
            default -> raw == null ? "-" : raw;
        };
    }

    private String status(String raw) {
        return switch (raw == null ? "" : raw) {
            case "ACTIVE" -> "有效";
            case "SUPERSEDED" -> "已被取代";
            case "EXPIRED" -> "已过期";
            default -> raw == null ? "-" : raw;
        };
    }

    private List<MemoryExtractionRun> bounded(int limit) {
        int size = Math.max(1, Math.min(100, limit));
        List<MemoryExtractionRun> all = runs.findTop100ByOrderByCreatedAtDesc();
        return all.size() <= size ? all : all.subList(0, size);
    }

    private int todayWrites(LocalDateTime today) {
        String userId = ownerUserId();
        if (userId.isBlank()) {
            return 0;
        }
        int count = 0;
        for (MemoryChangeLog entry : changeLogs.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 200))) {
            if (entry.getCreatedAt() == null || entry.getCreatedAt().isBefore(today)) {
                break;
            }
            if (entry.getAfterContent() != null && !entry.getAfterContent().isBlank()) {
                count++;
            }
        }
        return count;
    }

    private String skipDetail(Map<String, Integer> reasons) {
        if (reasons.isEmpty()) {
            return "无";
        }
        StringBuilder text = new StringBuilder();
        reasons.forEach((key, value) -> text.append(text.length() == 0 ? "" : "/").append(reason(key)).append(" ").append(value));
        return text.toString();
    }

    private String reason(String raw) {
        return switch (raw) {
            case "WINDOW_EMPTY" -> "没有可读轮次";
            case "PRECHECK" -> "前置门槛（无实质内容）";
            case "STALE" -> "结果过期未写回";
            case "FAILED" -> "调用/解析失败";
            default -> raw;
        };
    }

    private String trigger(String raw) {
        return switch (raw == null ? "" : raw) {
            case "TOOL" -> "工具调用";
            case "MANUAL" -> "手动";
            default -> "自动";
        };
    }

    private String action(String raw) {
        return switch (raw == null ? "" : raw) {
            case "ADD" -> "新增";
            case "UPDATE" -> "改写";
            case "CONFIRM" -> "确认（同值，不新增）";
            case "SUPERSEDE" -> "替换（留旧行）";
            case "COMPLETE" -> "标记完成";
            case "ARCHIVE" -> "归档";
            case "ARCHIVE_CREATE" -> "生成归档";
            case "CONFIRM_REJECT" -> "确认被拒";
            default -> raw == null ? "-" : raw;
        };
    }

    private String layer(String raw) {
        return switch (raw == null ? "" : raw) {
            case "CORE" -> "核心";
            case "WORK" -> "中期";
            case "FACT" -> "事实";
            case "ARCHIVE" -> "归档";
            default -> raw == null ? "-" : raw;
        };
    }

    private String operator(String raw) {
        return switch (raw == null ? "" : raw) {
            case "AUTO" -> "自动提取";
            case "USER" -> "用户确认";
            case "SYSTEM" -> "系统流程";
            case "TOOL" -> "工具写入";
            default -> raw == null ? "-" : raw;
        };
    }

    private String content(MemoryChangeLog entry) {
        String before = entry.getBeforeContent();
        String after = entry.getAfterContent();
        if (after == null || after.isBlank()) {
            return before == null ? "（无正文事件）" : "（已遗忘）" + clip(before, 60);
        }
        if (before == null || before.isBlank()) {
            return clip(after, 120);
        }
        return clip(before, 60) + " → " + clip(after, 60);
    }

    private String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String single = text.replace('\n', ' ').trim();
        return single.length() <= max ? single : single.substring(0, max - 1) + "…";
    }

    private Map<String, Object> row(String label, String value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", label);
        row.put("value", value);
        return row;
    }

    /** 与「它自己」页签一致：面板看的都是机主自己的数据 */
    private String ownerUserId() {
        String openid = alertProperties.getQqOpenid();
        return openid == null ? "" : openid.trim();
    }
}
