package com.liche.wechatagent.self;

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

import java.time.LocalDateTime;

/**
 * 一次"自己的时间"的作业记录（spec §7：每天一笔小预算）。
 *
 * <p>三件事都靠它：① **预算账**（今天跑了几次，而不是靠内存计数——重启不能重置预算）；
 * ② **成本账**（prompt/completion token，坑 60：思考 token 也算进 max_tokens）；
 * ③ **它的连续存在**（{@link #summary} 是它这一轮自己写下的东西，下次作业会读到）。
 *
 * <p>没跑成也记一条（{@code SKIPPED}/{@code FAILED} + {@link #reason}）：
 * "为什么今天没动"要能查，否则预算被谁吃掉都不知道。
 */
@Entity
@Table(name = "agent_quest_run", indexes = {
        @Index(name = "idx_self_quest_run_created", columnList = "created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class AgentQuestRun {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_RAN = "RAN";
    public static final String STATUS_SKIPPED = "SKIPPED";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "quest_id")
    private Long questId;

    @Column(length = 16, nullable = false)
    private String status;

    @Column(length = 500)
    private String reason;

    @Column(length = 2000)
    private String summary;

    @Column(name = "step_count", nullable = false)
    private Integer stepCount = 0;

    @Column(name = "note_count", nullable = false)
    private Integer noteCount = 0;

    @Column(name = "retract_count", nullable = false)
    private Integer retractCount = 0;

    @Column(name = "prompt_tokens", nullable = false)
    private Integer promptTokens = 0;

    @Column(name = "completion_tokens", nullable = false)
    private Integer completionTokens = 0;

    @Column(name = "duration_ms", nullable = false)
    private Integer durationMs = 0;

    /** 这次作业实际花了多少元（按 cache 命中/未命中 + 峰谷精算，不是估算） */
    @Column(name = "cost_yuan", nullable = false)
    private java.math.BigDecimal costYuan = java.math.BigDecimal.ZERO;

    @Column(name = "cache_hit_tokens", nullable = false)
    private Long cacheHitTokens = 0L;

    @Column(name = "cache_miss_tokens", nullable = false)
    private Long cacheMissTokens = 0L;

    /** 是不是"没落产出"之后的那一次续期（预算 ×1.5 那部分） */
    @Column(nullable = false)
    private Boolean extended = false;

    /** 这次作业被给的预算（元）——面板上能看出"它是在多少钱里干完的" */
    @Column(name = "budget_yuan", nullable = false)
    private java.math.BigDecimal budgetYuan = java.math.BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
