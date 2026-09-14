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
 * 它自己那侧的时间线：**只追加，不修改不删除**。
 *
 * <p>两个用途：① 它自己的历史（我立过什么目标、改过什么主意）；
 * ② 二期"判断 → 倾向"的**证据链**（倾向必须能指回具体的几条事件）。
 *
 * <p>{@code evidence} 由服务层校验：必须能解析成**真实存在**的对话记录 id 或事件 id，否则拒绝写入 ——
 * 这条是针对上次"归纳"翻车（模型把两条原文用「；」拼起来当结论）设的闸门。
 */
@Entity
@Table(name = "agent_self_event", indexes = {
        @Index(name = "idx_self_event_kind", columnList = "kind,created_at"),
        @Index(name = "idx_self_event_topic", columnList = "topic,stance,created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class AgentSelfEvent {

    public static final String KIND_GOAL_SET = "GOAL_SET";
    public static final String KIND_GOAL_CLOSED = "GOAL_CLOSED";
    public static final String KIND_COMMIT = "COMMIT";
    public static final String KIND_COMMIT_RESOLVED = "COMMIT_RESOLVED";
    /** 一轮里做出的判断（二期按 topic+stance 累积成倾向） */
    public static final String KIND_JUDGE = "JUDGE";
    /** 与用户意见相左（默认只讲一次，记一笔） */
    public static final String KIND_DISAGREE = "DISAGREE";
    public static final String KIND_REFLECT = "REFLECT";
    /** 倾向形成 / 修订 / 退役：**由程序按规则写**（spec §5），模型没有直接写倾向的工具 */
    public static final String KIND_STANCE_FORMED = "STANCE_FORMED";
    public static final String KIND_STANCE_REVISED = "STANCE_REVISED";
    public static final String KIND_STANCE_RETIRED = "STANCE_RETIRED";
    public static final String KIND_NOTE = "NOTE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 24, nullable = false)
    private String kind;

    /** 判断的类别（如"学习安排 / 我自己的能力 / 该不该答应"），二期归类用 */
    @Column(length = 60)
    private String topic;

    /** 方向（A / B / 中立），二期按同方向累计 */
    @Column(length = 16)
    private String stance;

    @Column(length = 1000, nullable = false)
    private String content;

    /** 证据：引用的对话记录 id 或事件 id（服务层校验真实性） */
    @Column(length = 300)
    private String evidence;

    @Column(nullable = false)
    private Integer importance = 0;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
