package com.liche.wechatagent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * LLM 花销的账（2026-09-15 加，用户给「它自己的时间」定了 0.5 元/天的预算）。
 *
 * <p><b>为什么必须按 cache 命中分开算</b>：DeepSeek Flash 的定价里
 * **缓存命中的输入是未命中的 1/50**（0.02 vs 1.0 元/百万）。而我们的作业 12 轮里
 * 人设 / 固定规则 / 工具 schema **每轮一模一样**，命中率很高——只记 prompt_tokens
 * 会把成本高估好几倍，预算就成了假的。
 *
 * <p><b>为什么按调用时刻计价</b>：官方峰时价是谷时的 2 倍（UTC 01:00–04:00 与 06:00–10:00，
 * 周一至周五；换算到北京时间是 09:00–12:00 与 14:00–18:00）。事后估会偏。
 *
 * <p><b>这个类只做两件事</b>：算一次调用多少钱、累计进程内的总数。
 * **真正的日账在数据库**（`agent_quest_run.cost_yuan`）——内存计数一重启就等于免费，
 * 那种预算拦不住任何东西。这里的累计只用来算"这次作业花了多少 = 前后差值"。
 */
@Component
public class LlmCostLedger {

    private final double cacheHitPerMillion;
    private final double cacheMissPerMillion;
    private final double outputPerMillion;
    private final double peakMultiplier;
    private final DoubleAdder total = new DoubleAdder();
    private final java.util.concurrent.atomic.LongAdder cacheHitTokens =
            new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder cacheMissTokens =
            new java.util.concurrent.atomic.LongAdder();

    public LlmCostLedger(@Value("${llm.price.cache-hit-per-million:0.02}") double cacheHitPerMillion,
                         @Value("${llm.price.cache-miss-per-million:1.0}") double cacheMissPerMillion,
                         @Value("${llm.price.output-per-million:4.0}") double outputPerMillion,
                         @Value("${llm.price.peak-multiplier:2.0}") double peakMultiplier) {
        this.cacheHitPerMillion = Math.max(0, cacheHitPerMillion);
        this.cacheMissPerMillion = Math.max(0, cacheMissPerMillion);
        this.outputPerMillion = Math.max(0, outputPerMillion);
        this.peakMultiplier = Math.max(1, peakMultiplier);
    }

    /**
     * 这一次调用花多少元。
     *
     * @param cacheHitTokens  缓存命中的输入 token（0 表示这次没有可命中的前缀）
     * @param cacheMissTokens 缓存未命中的输入 token
     * @param outputTokens    输出 token（含思考——思考也算 output，坑 60）
     * @param at              调用**发生的那一刻**，用来定峰谷价
     */
    public double price(int cacheHitTokens, int cacheMissTokens, int outputTokens, Instant at) {
        double factor = isPeak(at) ? peakMultiplier : 1.0;
        double micro = (double) Math.max(0, cacheHitTokens) * cacheHitPerMillion
                + (double) Math.max(0, cacheMissTokens) * cacheMissPerMillion
                + (double) Math.max(0, outputTokens) * outputPerMillion;
        return factor * micro / 1_000_000.0;
    }

    public void record(int cacheHitTokens, int cacheMissTokens, int outputTokens, Instant at) {
        double yuan = price(cacheHitTokens, cacheMissTokens, outputTokens, at);
        if (yuan > 0) {
            total.add(yuan);
        }
        this.cacheHitTokens.add(Math.max(0, cacheHitTokens));
        this.cacheMissTokens.add(Math.max(0, cacheMissTokens));
    }

    /** 进程内累计（只用于算差值；日预算请读数据库） */
    public double totalYuan() {
        return total.sum();
    }

    /** 进程内累计的缓存命中/未命中输入 token（差值得出某次作业的量，落库给面板看） */
    public long cacheHitTokens() {
        return cacheHitTokens.sum();
    }

    public long cacheMissTokens() {
        return cacheMissTokens.sum();
    }

    /** 峰时：UTC 01:00–04:00 与 06:00–10:00，周一至周五（北京时间 09–12 与 14–18） */
    static boolean isPeak(Instant at) {
        ZonedDateTime utc = at.atZone(ZoneOffset.UTC);
        DayOfWeek day = utc.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return false;
        }
        int hour = utc.getHour();
        return (hour >= 1 && hour < 4) || (hour >= 6 && hour < 10);
    }
}
