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
 * 它自己的「块」：有类型、可读写、**有长度上限**（照 Letta 的 memory blocks 做法）。
 *
 * <p>块是**常驻上下文**里的那一小块（我是谁 / 我现在在做什么），不是通用知识库——
 * 会累积的东西进 {@link AgentSelfEvent}，不往块里塞（否则必然膨胀、挤掉对话预算）。
 *
 * <p>这一侧属于"它自己"，**没有 user_id**：同一段时间里每次对话读到的是同一份。
 */
@Entity
@Table(name = "agent_self_block", indexes = {
        @Index(name = "uk_self_block", columnList = "block_type,label", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
public class AgentSelfBlock {

    /** 我是谁（Persona 块） */
    public static final String TYPE_PERSONA = "PERSONA";
    /** 我现在在做什么（当前自己的目标/项目） */
    public static final String TYPE_TASK = "TASK";
    /** 一个持续的项目（三期"领域"用） */
    public static final String TYPE_PROJECT = "PROJECT";
    /** 一贯的样子（二期"倾向"，程序按阈值提升后才写） */
    public static final String TYPE_STANCE = "STANCE";
    /** 随手记（低门槛，可被 summarize 掉） */
    public static final String TYPE_NOTE = "NOTE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "block_type", length = 16, nullable = false)
    private String blockType;

    @Column(length = 64, nullable = false)
    private String label;

    @Column(length = 4000)
    private String value;

    /** 这个块的字符上限；超了必须先 summarize 才能再写 */
    @Column(name = "char_limit", nullable = false)
    private Integer charLimit = 1200;

    /** 给模型看的说明：这个块该装什么、什么时候该改它 */
    @Column(length = 200)
    private String description;

    @Column(nullable = false)
    private Integer version = 1;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
