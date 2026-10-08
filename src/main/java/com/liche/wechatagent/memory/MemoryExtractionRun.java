package com.liche.wechatagent.memory;

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
 * 一次记忆提取的**审计行**（2026-09-17 加）。
 *
 * <p>为什么要有它：提取是后台跑的，"到底提没提取成功、为什么什么都没写"以前只能靠翻日志猜。
 * 现在每跑一次（**包括被跳过和失败**）都留一行：读了多长的窗口、模型判了什么、实际写了几条、
 * 花了多少 token 与钱、以及"空"的原因是哪一个。
 *
 * <p>{@link #skipReason}：{@code WINDOW_EMPTY}（没有可读的轮次）/ {@code PRECHECK}（前置门槛挡下）/
 * {@code STALE}（结果过期未写回）/ {@code PARSE_FAILED}（输出解析不出来）；正常跑完为空。
 */
@Entity
@Table(name = "memory_extraction_run", indexes = {
        @Index(name = "idx_mer_user_time", columnList = "user_id, created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class MemoryExtractionRun {

    /** 后台自动触发（定时/每轮对话后的静默窗口） */
    public static final String TRIGGER_AUTO = "AUTO";
    /** 模型显式调用工具触发的写入 */
    public static final String TRIGGER_TOOL = "TOOL";
    /** 面板手动触发 */
    public static final String TRIGGER_MANUAL = "MANUAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", length = 128, nullable = false)
    private String userId;

    @Column(name = "trigger_source", length = 32, nullable = false)
    private String triggerSource = TRIGGER_AUTO;

    /** 这次实际读了多少轮 / 多少字（窗口 = 最近 N 轮） */
    @Column(name = "window_turns", nullable = false)
    private Integer windowTurns = 0;

    @Column(name = "window_chars", nullable = false)
    private Integer windowChars = 0;

    @Column(name = "prompt_tokens", nullable = false)
    private Integer promptTokens = 0;

    @Column(name = "completion_tokens", nullable = false)
    private Integer completionTokens = 0;

    @Column(name = "reasoning_tokens", nullable = false)
    private Integer reasoningTokens = 0;

    @Column(name = "cache_hit_tokens", nullable = false)
    private Long cacheHitTokens = 0L;

    @Column(name = "cache_miss_tokens", nullable = false)
    private Long cacheMissTokens = 0L;

    /** 这一次实际花掉多少元（账本前后差，含峰谷价）——**必须显式声明 precision/scale**：
     *  Hibernate 对 BigDecimal 的默认是 DECIMAL(38,2)，而迁移脚本建的是 DECIMAL(10,4)，
     *  `ddl-auto: validate` 下类型不一致会让容器起不来（本地还实测到 0.0026 被四舍五入成 0.00） */
    @Column(name = "cost_yuan", nullable = false, precision = 10, scale = 4)
    private BigDecimal costYuan = BigDecimal.ZERO;

    @Column(name = "duration_ms", nullable = false)
    private Integer durationMs = 0;

    /** 模型**提议**了多少条，形如 {@code core=1 work=0 episode=1 updates=0 completed=0 duplicates=3} */
    @Column(name = "verdict_json", length = 512)
    private String verdictJson;

    /**
     * **实际落库清单**（按操作计数，逗号分隔，形如 {@code 新增核心记忆=1,新增情景记忆=1,事实条数=4}）。
     *
     * <p>列名虽然叫 ids，但存的**不是 id**：写入统一走 {@code runSafely(..., Runnable)}，
     * lambda 不返回实体、拿不到自增 id；要拿 id 得把 6 个 applyXxx 的 14 个调用点全改成带返回值。
     * 而"模型提议了多少条 vs 实际落了几条"这个对照**只要计数就能回答**，所以先按计数落。
     *
     * <p>空串 = 这次一条都没写成（和 {@code skip_reason} 一起看）。
     * <p>⚠️ 2026-10-09 之前这一列**一直是 null**——MemoryExtractor 调用审计时硬编码传了 null，
     * 所以那段时期的"落库"信息在库里是缺失的，**不能用它做历史对比**。
     */
    @Column(name = "written_ids", length = 512)
    private String writtenIds;

    @Column(name = "skip_reason", length = 64)
    private String skipReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
