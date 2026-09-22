package com.liche.wechatagent.metrics;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一次 LLM 调用的**审计行**（2026-09-21 加，DDL 见 {@code deploy/postgres/V18__create_llm_call_audit.sql}）。
 *
 * <p>为什么要有它：在这之前只有"作业"（记忆提取、它自己的时间）按次落库，
 * 而**对话本身**——占开销最大头的每轮流式调用——只进了 {@link RuntimeMetrics} 的进程内计数，
 * 重启归零、也没法按天或按场景聚合。所以"最近花了多少钱"一直只能翻日志估。
 *
 * <p>与 {@code agent_quest_run} / {@code memory_extraction_run} 同一套口径：
 * **命中/未命中分开记**（命中价是未命中的 1/50，不分开会高估好几倍）、
 * **钱按调用时刻算峰谷**（官方峰时价 ×2），写进来的 {@link #costYuan} 是账本算好的结果而不是估算。
 */
@Entity
@Table(name = "llm_call_audit", indexes = {
        @Index(name = "idx_llm_call_created", columnList = "created_at"),
        @Index(name = "idx_llm_call_scenario", columnList = "scenario, created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class LlmCallAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 调用场景标签（{@code LlmScenario.label()}，如 dialog / extract / reflect） */
    @Column(name = "scenario", length = 64, nullable = false)
    private String scenario;

    @Column(name = "streaming", nullable = false)
    private Boolean streaming = false;

    @Column(name = "ok", nullable = false)
    private Boolean ok = true;

    @Column(name = "duration_ms", nullable = false)
    private Long durationMs = 0L;

    @Column(name = "prompt_tokens", nullable = false)
    private Integer promptTokens = 0;

    @Column(name = "completion_tokens", nullable = false)
    private Integer completionTokens = 0;

    /** 思考的那部分，**已含在 completion 里**，单独记只是为了看清"多少是在想" */
    @Column(name = "reasoning_tokens", nullable = false)
    private Integer reasoningTokens = 0;

    @Column(name = "cache_hit_tokens", nullable = false)
    private Long cacheHitTokens = 0L;

    @Column(name = "cache_miss_tokens", nullable = false)
    private Long cacheMissTokens = 0L;

    /** 这一次实际花掉多少元（含峰谷价）——**必须显式声明 precision/scale**，
     *  否则 Hibernate 默认 DECIMAL(38,2)，`ddl-auto: validate` 会对不上迁移脚本建的 DECIMAL(10,4) */
    @Column(name = "cost_yuan", nullable = false, precision = 10, scale = 4)
    private BigDecimal costYuan = BigDecimal.ZERO;

    @Column(name = "error_message", length = 200)
    private String errorMessage;
}
