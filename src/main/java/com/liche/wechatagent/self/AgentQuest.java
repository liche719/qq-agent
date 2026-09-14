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
 * 它自己的一个方向（三期领域②，spec §7/§9）。
 *
 * <p><b>这块地盘是它自己的</b>：题目、选择理由、下一步都由它自己出，不是机主派的任务
 * （§9 筛选规则第 2 条：靠指派就还是执行器）。它拥有的是"想法"——为什么选这个而不是那个——
 * 所以 {@link #why} 是必填的：偏好是**稀缺下的选择模式被记录下来**，不是宣称出来的（§7）。
 *
 * <p>为什么同时只允许一个 {@code ACTIVE}：稀缺才有取舍（§9）。想开新的，先关旧的。
 */
@Entity
@Table(name = "agent_quest", indexes = {
        @Index(name = "idx_self_quest_status", columnList = "status,updated_at")
})
@Getter
@Setter
@NoArgsConstructor
public class AgentQuest {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_PAUSED = "PAUSED";
    public static final String STATUS_CLOSED = "CLOSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 领域名，它自己起的 */
    @Column(length = 200, nullable = false)
    private String title;

    /** 为什么选这个（它自己的理由，必填——这是"偏好"的唯一证据） */
    @Column(length = 600, nullable = false)
    private String why;

    @Column(length = 16, nullable = false)
    private String status = STATUS_ACTIVE;

    /** 它自己写的下一步（会注入到它自己的上下文里，所以短） */
    @Column(name = "next_step", length = 600)
    private String nextStep;

    @Column(name = "step_count", nullable = false)
    private Integer stepCount = 0;

    @Column(name = "note_count", nullable = false)
    private Integer noteCount = 0;

    /** 被它自己撤回的笔记数：撤回说明它在核对，而不是在堆料（§9.3 的标尺之一） */
    @Column(name = "retract_count", nullable = false)
    private Integer retractCount = 0;

    @Column(length = 300)
    private String evidence;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;
}
