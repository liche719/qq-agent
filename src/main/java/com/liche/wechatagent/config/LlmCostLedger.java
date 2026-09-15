package com.liche.wechatagent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * LLM 花销的账（2026-09-15 加，用户给「它自己的时间」定了 0.5 元/天的预算）。
 *
 * <p><b>为什么必须按 cache 命中分开算</b>：DeepSeek Flash 的定价里
 * **缓存命中的输入是未命中的 1/50**（0.02 vs 1.0 元/百万）。而我们的作业 12 轮里
 * 人设 / 固定规则 / 工具 schema **每轮一模一样**，命中率很高——只记 prompt_tokens
 * 会把成本高估好几倍，预算就成了假的。
 *
 * <p><b>为什么按调用时刻计价</b>：官方峰时价是谷时的 2 倍。**时段是可配的**
 * （{@code llm.price.peak-windows-utc}，默认 `1-4,6-10` 即 DeepSeek 的 UTC 时段；
 * 留空 = 没有峰谷价，别的厂商多半没有）。
 *
 * <p><b>这个类只做两件事</b>：算一次调用多少钱、累计进程内的总数。
 * **真正的日账在数据库**（`agent_quest_run.cost_yuan`）——内存计数一重启就等于免费，
 * 那种预算拦不住任何东西。这里的累计只用来算"这次作业花了多少 = 前后差值"。
 *
 * <p><b>它实现 {@link LlmUsageSink}（收用量）与 {@link LlmSpendMeter}（给读数）</b>：
 * 模型层只认识前者、熔断方只认识后者，所以"换模型"和"换记账方式"互不影响——
 * 这是 2026-09-15 那次"计费与模型解耦"的落点。
 */
@Component
public class LlmCostLedger implements LlmUsageSink, LlmSpendMeter {

    private static final Logger log = LoggerFactory.getLogger(LlmCostLedger.class);

    private final double cacheHitPerMillion;
    private final double cacheMissPerMillion;
    private final double outputPerMillion;
    private final double peakMultiplier;
    /** 峰时时段（UTC，形如 `1-4,6-10`）；留空 = 永远不是峰时 */
    private final List<int[]> peakWindows;
    private final DoubleAdder total = new DoubleAdder();
    private final java.util.concurrent.atomic.LongAdder cacheHitTokens =
            new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder cacheMissTokens =
            new java.util.concurrent.atomic.LongAdder();

    public LlmCostLedger(@Value("${llm.price.cache-hit-per-million:0.02}") double cacheHitPerMillion,
                         @Value("${llm.price.cache-miss-per-million:1.0}") double cacheMissPerMillion,
                         @Value("${llm.price.output-per-million:4.0}") double outputPerMillion,
                         @Value("${llm.price.peak-multiplier:2.0}") double peakMultiplier,
                         @Value("${llm.price.peak-windows-utc:1-4,6-10}") String peakWindows) {
        this.cacheHitPerMillion = Math.max(0, cacheHitPerMillion);
        this.cacheMissPerMillion = Math.max(0, cacheMissPerMillion);
        this.outputPerMillion = Math.max(0, outputPerMillion);
        this.peakMultiplier = Math.max(1, peakMultiplier);
        this.peakWindows = parseWindows(peakWindows);
    }

    /** 解析 `1-4,6-10` 这种 UTC 时段；写错就忽略那一段（宁可不打折，也不要算错） */
    static List<int[]> parseWindows(String spec) {
        List<int[]> windows = new ArrayList<>();
        if (spec == null || spec.isBlank()) {
            return windows;
        }
        for (String part : spec.split("[,;\\s]+")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            int dash = token.indexOf('-');
            try {
                int start;
                int end;
                if (dash < 0) {
                    start = Integer.parseInt(token);
                    end = start + 1;
                } else {
                    start = Integer.parseInt(token.substring(0, dash));
                    end = Integer.parseInt(token.substring(dash + 1));
                }
                if (start >= 0 && end <= 24 && end > start) {
                    windows.add(new int[]{start, end});
                }
            } catch (NumberFormatException ignored) {
                log.warn("峰时时段写错了，忽略这一段：{}", token);
            }
        }
        return windows;
    }

    @Override
    public void accept(LlmUsage usage) {
        if (usage == null || usage.isEmpty()) {
            return;
        }
        int hit = Math.max(0, usage.cachedTokens());
        int miss = usage.uncachedTokens();
        double yuan = price(hit, miss, usage.completionTokens(), Instant.now());
        if (yuan > 0) {
            total.add(yuan);
        }
        cacheHitTokens.add(hit);
        cacheMissTokens.add(miss);
    }

    /**
     * 这一次调用花多少元。
     *
     * @param at 调用**发生的那一刻**，用来定峰谷价
     */
    public double price(int cacheHitTokens, int cacheMissTokens, int outputTokens, Instant at) {
        double factor = isPeak(at) ? peakMultiplier : 1.0;
        double micro = (double) Math.max(0, cacheHitTokens) * cacheHitPerMillion
                + (double) Math.max(0, cacheMissTokens) * cacheMissPerMillion
                + (double) Math.max(0, outputTokens) * outputPerMillion;
        return factor * micro / 1_000_000.0;
    }

    /** 进程内累计（只用于算差值；日预算请读数据库） */
    @Override
    public double totalYuan() {
        return total.sum();
    }

    /** 进程内累计的缓存命中/未命中输入 token（差值得出某次作业的量，落库给面板看） */
    @Override
    public long cacheHitTokens() {
        return cacheHitTokens.sum();
    }

    @Override
    public long cacheMissTokens() {
        return cacheMissTokens.sum();
    }

    /** 峰时：落在配置的 UTC 时段内、且是工作日（时段留空 = 永远不是峰时） */
    boolean isPeak(Instant at) {
        if (peakWindows.isEmpty()) {
            return false;
        }
        ZonedDateTime utc = at.atZone(ZoneOffset.UTC);
        DayOfWeek day = utc.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }
        int hour = utc.getHour();
        for (int[] window : peakWindows) {
            if (hour >= window[0] && hour < window[1]) {
                return true;
            }
        }
        return false;
    }
}
