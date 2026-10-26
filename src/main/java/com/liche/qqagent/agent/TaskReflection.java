package com.liche.qqagent.agent;

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
 * 任务级反思的记忆（2026-10-25）。
 *
 * <p>为什么单独一张表：`memory` 存的是"关于他的事实"，`agent_self_note` 存的是"它对自己的描述"，
 * 而"某个工具连续失败时上次是怎么绕过去的"是第三种东西——混进去会污染两个已有的注入通道。
 *
 * <p>复用方式：同一工具再次失败时，先查最近几条，把"上次的归因 + 改法"塞进反思提示词，
 * 让它别从零开始想。
 */
@Entity
@Table(name = "task_reflection", indexes = {
        @Index(name = "idx_reflection_user_time", columnList = "user_id, created_at"),
        @Index(name = "idx_reflection_user_tool", columnList = "user_id, tool, created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class TaskReflection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", length = 128, nullable = false)
    private String userId;

    @Column(length = 64, nullable = false)
    private String tool;

    /** TRANSIENT / FIXABLE / STRATEGIC */
    @Column(name = "failure_kind", length = 16, nullable = false)
    private String failureKind;

    /** 模型给的 failure_type：tool_limitation / wrong_plan / wrong_params / no_capability */
    @Column(name = "failure_type", length = 32)
    private String failureType;

    @Column(name = "root_cause", length = 300)
    private String rootCause;

    @Column(name = "next_action", length = 300)
    private String nextAction;

    /** 这一轮反思之后任务成了没有：SUCCESS / FAILED；NULL = 当时还没统计（2026-10-27） */
    @Column(name = "turn_outcome", length = 16)
    private String turnOutcome;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
