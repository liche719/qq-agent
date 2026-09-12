package com.liche.wechatagent.schedule;

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
 * 定时任务：到点让机器人**真的去做一件事**并把结果发回来（区别于"定时提醒"只发一句提醒）。
 *
 * <p>例如「每天早上 8 点把天气发我」「每周一汇总上周聊过的内容」。
 * 到点后用 {@code instruction} 走一遍完整的 Agent 链路（可搜索、可调用工具、可读记忆），
 * 再把回复推送给用户，并把结果与状态留在本表里（面板可见）。
 */
@Entity
@Table(name = "scheduled_task", indexes = {
        @Index(name = "idx_scheduled_user", columnList = "userId"),
        @Index(name = "idx_scheduled_enabled", columnList = "enabled")
})
@Getter
@Setter
@NoArgsConstructor
public class ScheduledTask {

    public static final String STATUS_IDLE = "IDLE";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 128, nullable = false)
    private String userId;

    /** 给人看的名字，例如「早上天气」 */
    @Column(length = 120)
    private String title;

    /** 到点执行的指令（按用户原话改写，交给 Agent 执行） */
    @Column(length = 2000)
    private String instruction;

    /** Quartz 6 段 Cron（秒 分 时 日 月 周） */
    @Column(length = 64, nullable = false)
    private String cron;

    private Boolean enabled = true;

    private LocalDateTime nextRunAt;

    private LocalDateTime lastRunAt;

    /** IDLE / RUNNING / SUCCESS / FAILED */
    @Column(length = 16)
    private String status = STATUS_IDLE;

    /** 上一次执行的结果摘要（就是发给用户的那条回复） */
    @Column(length = 2000)
    private String lastResult;

    @Column(length = 500)
    private String lastError;

    private Integer runCount = 0;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
