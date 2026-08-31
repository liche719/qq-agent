package com.liche.wechatagent.reminder;

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

/** 定时提醒任务（Quartz JDBC 持久化，重启自动恢复） */
@Entity
@Table(name = "reminder_task", indexes = @Index(name = "idx_reminder_user", columnList = "userId"))
@Getter
@Setter
@NoArgsConstructor
public class ReminderTask {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 128)
    private String userId;

    /** 提醒内容 */
    @Column(length = 2000)
    private String content;

    /** 准时触发时间 */
    private LocalDateTime triggerAt;

    /** 提前预热分钟数（默认 10） */
    private Integer prewarmMinutes = 10;

    /** 重复规则 Cron 表达式，null 表示一次性 */
    @Column(length = 64)
    private String cron;

    @Column(length = 32)
    private String status = STATUS_PENDING;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public ReminderTask(String userId, String content, LocalDateTime triggerAt, Integer prewarmMinutes, String cron) {
        this.userId = userId;
        this.content = content;
        this.triggerAt = triggerAt;
        this.prewarmMinutes = prewarmMinutes == null ? 10 : prewarmMinutes;
        this.cron = cron;
        this.status = STATUS_PENDING;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }
}
