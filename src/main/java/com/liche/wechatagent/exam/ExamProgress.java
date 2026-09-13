package com.liche.wechatagent.exam;

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

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 章节/轮次进度：一条 = 一个可度量的复习单元（高数第三章、408 数据结构王道、真题 2015 卷…）。 */
@Entity
@Table(name = "exam_progress", indexes = {
        @Index(name = "idx_exam_progress_user", columnList = "user_id,subject_group"),
        @Index(name = "idx_exam_progress_due", columnList = "user_id,due_date")
})
@Getter
@Setter
@NoArgsConstructor
public class ExamProgress {

    public static final String PHASE_BASIC = "BASIC";
    public static final String PHASE_INTENSIVE = "INTENSIVE";
    public static final String PHASE_SPRINT = "SPRINT";
    public static final String PHASE_PAST_PAPER = "PAST_PAPER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", length = 128, nullable = false)
    private String userId;

    @Column(length = 60)
    private String subject;

    /** 归组：408 / 数学二 / 英语二 / 政治…（面板按它聚合，统计才不会散） */
    @Column(name = "subject_group", length = 60)
    private String subjectGroup;

    /** BASIC / INTENSIVE / SPRINT / PAST_PAPER */
    @Column(length = 16)
    private String phase;

    @Column(length = 200, nullable = false)
    private String title;

    private Integer total;
    private Integer done;

    /** 量词：章 / 题 / 讲 / 套 */
    @Column(length = 16)
    private String unit;

    private LocalDate dueDate;

    @Column(length = 300)
    private String note;

    private LocalDateTime lastTouchedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
