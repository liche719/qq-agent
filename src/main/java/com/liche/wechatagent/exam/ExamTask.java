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

/** 一条每日任务：早上按计划生成，之后用户/模型都能加和改。 */
@Entity
@Table(name = "exam_task", indexes = {
        @Index(name = "idx_exam_task_user_date", columnList = "user_id,plan_date"),
        @Index(name = "idx_exam_task_user_status", columnList = "user_id,status")
})
@Getter
@Setter
@NoArgsConstructor
public class ExamTask {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_SKIPPED = "SKIPPED";

    public static final String SOURCE_PLAN = "PLAN";
    public static final String SOURCE_MANUAL = "MANUAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", length = 128, nullable = false)
    private String userId;

    @Column(name = "plan_date", nullable = false)
    private LocalDate planDate;

    @Column(length = 60)
    private String subject;

    @Column(length = 300)
    private String content;

    private Integer plannedMinutes;

    /** PENDING / DONE / SKIPPED */
    @Column(length = 16)
    private String status = STATUS_PENDING;

    /** PLAN（按计划生成）/ MANUAL（用户或模型加的） */
    @Column(length = 16)
    private String source = SOURCE_PLAN;

    private LocalDateTime doneAt;

    @Column(length = 300)
    private String note;

    private Integer sortOrder;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
