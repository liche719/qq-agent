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
 * 倾向（判断→倾向的产物）：**由程序按规则提升，不是模型自己写**（spec §5）。
 *
 * <p>为什么单独一张表、而不是只写进 STANCE 块：块是**注入用的投影**（≤5 行、受 800 字预算约束），
 * 而倾向还要带证据区间、反例、修订次数、FSRS 的 S/D 与复查时间——那些进块只会挤掉对话预算。
 * 所以这里才是事实源，STANCE 块由它渲染。
 *
 * <p>`stability`（S）与 `difficulty`（D）来自 FSRS v6：按某条倾向行动、结果被证实 = 复习成功 → S 变长；
 * 被反例推翻 = 遗忘 → S 收缩、D 上升。`nextReviewAt` 由 `R(t,S)` 掉到目标保留率**反推**，不是拍脑袋定的天数。
 */
@Entity
@Table(name = "agent_stance", indexes = {
        @Index(name = "idx_self_stance_status", columnList = "status,next_review_at"),
        @Index(name = "idx_self_stance_topic", columnList = "topic,status")
})
@Getter
@Setter
@NoArgsConstructor
public class AgentStance {

    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 人工/逃生门退役 */
    public static final String STATUS_RETIRED = "RETIRED";
    /** 长期没有新证据 → 降级（不删除，可回溯） */
    public static final String STATUS_DEMOTED = "DEMOTED";
    /** 被反例修订，旧内容留档 */
    public static final String STATUS_REVISED = "REVISED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 判断类别，与 agent_self_event.topic 对齐 */
    @Column(length = 60, nullable = false)
    private String topic;

    /** 方向标签（短词，例如 A/B/要有记录） */
    @Column(length = 16, nullable = false)
    private String direction;

    /** 倾向原话，必须含证据区间说明 */
    @Column(length = 600, nullable = false)
    private String content;

    /** 支撑它的 JUDGE 事件 id（逗号分隔） */
    @Column(name = "evidence_ids", length = 300)
    private String evidenceIds;

    /** 方向相反的 JUDGE 事件 id —— 反例优先，必须看得见 */
    @Column(name = "counter_ids", length = 300)
    private String counterIds;

    @Column(name = "support_count", nullable = false)
    private Integer supportCount = 0;

    @Column(name = "counter_count", nullable = false)
    private Integer counterCount = 0;

    @Column(name = "revise_count", nullable = false)
    private Integer reviseCount = 0;

    /** FSRS 的记忆强度 S（天） */
    @Column(nullable = false)
    private Double stability = 1.0;

    /** FSRS 的难度 D（1~10，越大越难） */
    @Column(nullable = false)
    private Double difficulty = 5.0;

    private LocalDateTime lastReviewAt;
    private LocalDateTime nextReviewAt;

    @Column(length = 16, nullable = false)
    private String status = STATUS_ACTIVE;

    @Column(name = "formed_at", nullable = false)
    private LocalDateTime formedAt;

    private LocalDateTime updatedAt;
}
