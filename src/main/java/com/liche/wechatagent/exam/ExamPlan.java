package com.liche.wechatagent.exam;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 备考计划：一个用户一份（{@code user_id} 就是主键）。
 *
 * <p>{@code subjects} 存 JSON 数组：{@code [{"name":"数学","targetScore":120,"dailyMinutes":120,"dailyPlan":"强化第3章"}]}，
 * 用一列放而不是单开一张表——科目就三四个，读的时候整份一起用，没必要 join。
 */
@Entity
@Table(name = "exam_plan")
@Getter
@Setter
@NoArgsConstructor
public class ExamPlan {

    public static final String STAGE_BASIC = "BASIC";
    public static final String STAGE_INTENSIVE = "INTENSIVE";
    public static final String STAGE_SPRINT = "SPRINT";

    @Id
    @Column(name = "user_id", length = 128)
    private String userId;

    /** 考试日期（初试） */
    private LocalDate examDate;

    @Column(length = 120)
    private String school;

    @Column(length = 120)
    private String major;

    /** BASIC / INTENSIVE / SPRINT */
    @Column(length = 16)
    private String stage = STAGE_BASIC;

    /** 每天计划学习分钟数 */
    private Integer dailyMinutes;

    /** 科目 JSON，见类注释 */
    @Column(length = 2000)
    private String subjects;

    private Boolean enabled = true;

    @Column(length = 500)
    private String remark;

    /** 各类推送的"今天已经推过"标记：重启也不会重复推 */
    private LocalDate lastMorningPush;
    private LocalDate lastEveningPush;
    private LocalDate lastWeeklyPush;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
