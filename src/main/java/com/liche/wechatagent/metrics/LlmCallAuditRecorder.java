package com.liche.wechatagent.metrics;

import com.liche.wechatagent.config.LlmCallEvent;
import com.liche.wechatagent.config.LlmCallSink;
import com.liche.wechatagent.config.LlmCostLedger;
import com.liche.wechatagent.config.LlmUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把每一次 LLM 调用写进 {@code llm_call_audit}（2026-09-21 加）。
 *
 * <p><b>为什么不是又一个内存计数器</b>：{@link RuntimeMetrics} 的场景统计重启归零，
 * 而"这个月花了多少钱"必须跨重启、跨部署成立——所以钱落库（同 {@code agent_quest_run} 的教训）。
 *
 * <p><b>钱由 {@link LlmCostLedger} 算</b>：命中/未命中分开 + 按**调用时刻**定峰谷 ×2。
 * 这里只做"归一化用量 → 一行审计"的搬运，不自己再定义一遍价格。
 *
 * <p><b>失败绝不外抛</b>：本类挂在模型调用路径上，写库出问题只能记一条 WARN，
 * 不能让用户的回复因为记账失败而失败（约定见 {@link LlmCallSink}）。
 */
@Component
public class LlmCallAuditRecorder implements LlmCallSink {

    private static final Logger log = LoggerFactory.getLogger(LlmCallAuditRecorder.class);

    /** 错误信息列宽（{@code error_message varchar(200)}）；截断要给省略号留一位，否则撞列长整条写入失败 */
    private static final int ERROR_MAX = 200;
    private static final int SCENARIO_MAX = 64;

    private final LlmCallAuditRepository repository;
    private final LlmCostLedger ledger;
    private final boolean enabled;
    private final ZoneId zone;

    public LlmCallAuditRecorder(LlmCallAuditRepository repository,
                                LlmCostLedger ledger,
                                @Value("${llm.audit.enabled:true}") boolean enabled,
                                @Value("${app.time-zone:Asia/Shanghai}") String timeZone) {
        this.repository = repository;
        this.ledger = ledger;
        this.enabled = enabled;
        this.zone = parseZone(timeZone);
    }

    @Override
    public void acceptCall(LlmCallEvent event) {
        if (!enabled || event == null) {
            return;
        }
        try {
            repository.save(toRow(event));
        } catch (RuntimeException exception) {
            log.warn("LLM 调用审计写库失败（已忽略）：{}", exception.getMessage());
        }
    }

    /** 把一次调用归一化成审计行（不写库，方便单测/诊断直接看） */
    LlmCallAudit toRow(LlmCallEvent event) {
        LlmUsage usage = event.usageOrEmpty();
        int hit = Math.max(0, usage.cachedTokens());
        int miss = usage.uncachedTokens();
        int completion = Math.max(0, usage.completionTokens());

        LlmCallAudit row = new LlmCallAudit();
        row.setCreatedAt(LocalDateTime.now(zone));
        row.setScenario(truncate(event.scenario() == null || event.scenario().isBlank()
                ? "unknown" : event.scenario(), SCENARIO_MAX));
        row.setStreaming(event.streaming());
        row.setOk(event.ok());
        row.setDurationMs(Math.max(0L, event.durationMs()));
        row.setPromptTokens(Math.max(0, usage.promptTokens()));
        row.setCompletionTokens(completion);
        row.setReasoningTokens(Math.max(0, usage.reasoningTokens()));
        row.setCacheHitTokens((long) hit);
        row.setCacheMissTokens((long) miss);
        // 单价与峰谷都由账本说了算；表里存 4 位小数（与 agent_quest_run 同口径）
        row.setCostYuan(BigDecimal.valueOf(ledger.price(hit, miss, completion, Instant.now()))
                .setScale(4, RoundingMode.HALF_UP));
        row.setErrorMessage(event.ok() ? null : truncate(event.error(), ERROR_MAX));
        return row;
    }

    /** 今天（本地时区自然日）已经花了多少元 */
    public BigDecimal todayYuan() {
        return repository.sumCostSince(LocalDate.now(zone).atStartOfDay());
    }

    /** 最近 {@code days} 天：按天的汇总（面板用） */
    public List<Map<String, Object>> byDay(int days) {
        LocalDate from = LocalDate.now(zone).minusDays(Math.max(1, days) - 1L);
        List<Object[]> rows = repository.summaryByDay(from.atStartOfDay());
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("day", String.valueOf(row[0]));
            item.put("calls", asLong(row[1]));
            item.put("yuan", asDouble(row[2]));
            item.put("promptTokens", asLong(row[3]));
            item.put("completionTokens", asLong(row[4]));
            item.put("cacheHitTokens", asLong(row[5]));
            item.put("cacheHitPercent", percent(asLong(row[5]), asLong(row[3])));
            result.add(item);
        }
        return result;
    }

    /** 最近 {@code days} 天：按场景的汇总，花钱多的在前（面板用） */
    public List<Map<String, Object>> byScenario(int days) {
        LocalDate from = LocalDate.now(zone).minusDays(Math.max(1, days) - 1L);
        List<Object[]> rows = repository.summaryByScenario(from.atStartOfDay());
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("scenario", String.valueOf(row[0]));
            item.put("calls", asLong(row[1]));
            item.put("yuan", asDouble(row[2]));
            item.put("avgPromptTokens", asLong(row[3]));
            item.put("cacheHitTokens", asLong(row[4]));
            item.put("promptTokens", asLong(row[5]));
            item.put("completionTokens", asLong(row[6]));
            item.put("cacheHitPercent", percent(asLong(row[4]), asLong(row[5])));
            result.add(item);
        }
        return result;
    }

    private static double percent(long part, long total) {
        if (total <= 0) {
            return 0d;
        }
        return Math.round(1000.0 * part / total) / 10.0;
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0d;
    }

    /** 截断时给省略号留一位（历史坑：多出 1 个字符直接撞列长，整条写入失败） */
    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String text = value.strip();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private static ZoneId parseZone(String timeZone) {
        try {
            return ZoneId.of(timeZone);
        } catch (RuntimeException ignored) {
            return ZoneId.of("Asia/Shanghai");
        }
    }
}
