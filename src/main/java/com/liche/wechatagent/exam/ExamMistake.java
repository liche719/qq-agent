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

/**
 * 错题 / 顽固知识点：按间隔复习（1/3/7/15/30 天）回收。
 *
 * <p>和墨墨的「顽固词」一个思路：答对就往后推一个间隔，答错回到第一天；走完整轮才算掌握。
 * 早推送里会把今天到期的抽 2~3 条出来问。
 */
@Entity
@Table(name = "exam_mistake", indexes = {
        @Index(name = "idx_exam_mistake_user", columnList = "user_id,status,next_review_date"),
        @Index(name = "idx_exam_mistake_group", columnList = "user_id,subject_group")
})
@Getter
@Setter
@NoArgsConstructor
public class ExamMistake {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_MASTERED = "MASTERED";
    public static final String STATUS_DROPPED = "DROPPED";

    /** 间隔复习的天数阶梯：第 n 轮答对后，下次在 intervals[n] 天后 */
    public static final int[] REVIEW_INTERVALS = {1, 3, 7, 15, 30};

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", length = 128, nullable = false)
    private String userId;

    @Column(length = 60)
    private String subject;

    @Column(name = "subject_group", length = 60)
    private String subjectGroup;

    @Column(length = 300, nullable = false)
    private String title;

    @Column(length = 1000)
    private String detail;

    /** 来源：660 题 / 王道 / 真题 2015 … */
    @Column(length = 60)
    private String source;

    /** 已经过了几轮；0 = 刚记下 */
    private Integer reviewStage = 0;

    private LocalDate nextReviewDate;
    private Integer correctStreak = 0;

    /** OPEN / MASTERED / DROPPED */
    @Column(length = 16)
    private String status = STATUS_OPEN;

    private LocalDateTime lastReviewedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
