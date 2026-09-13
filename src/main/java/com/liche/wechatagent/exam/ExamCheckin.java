package com.liche.wechatagent.exam;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 每日打卡：一天一条，用来算连续天数与趋势。 */
@Entity
@Table(name = "exam_checkin")
@Getter
@Setter
@NoArgsConstructor
public class ExamCheckin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", length = 128, nullable = false)
    private String userId;

    @Column(name = "checkin_date", nullable = false)
    private LocalDate checkinDate;

    /** 当天实际学习分钟数 */
    private Integer minutes;

    @Column(length = 500)
    private String note;

    /** 打卡那一刻的任务完成情况快照 */
    private Integer tasksTotal;
    private Integer tasksDone;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
