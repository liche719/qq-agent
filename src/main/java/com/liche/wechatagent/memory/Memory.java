package com.liche.wechatagent.memory;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 记忆（2026-09-18 三合一）：**一条记忆 = 一段话**，用 {@link #kind} 区分语义、用 {@link #alwaysInject} 决定注入策略。
 *
 * <p>为什么要合：core / work / episode 三张表在"操作"上只剩两条轴——要不要每轮无条件注入、是不是有槽位的当前值
 * 或原文证据。实测边界已经站不住（生产只有 19 行 episode，而 work 里大量行本身就是"经历"），
 * 于是合并成这一张；`memory_fact`（有槽位的当前值）与 `conversation_memory`（原文证据）保持独立，
 * 因为它们的**操作**不同：一个是"改某一格"，一个是"只能忘不能替"。
 *
 * <p>三种 kind：
 * <ul>
 *   <li>{@link #KIND_PROFILE}：关于用户的长期设定（目标/身份/偏好）——{@code alwaysInject=true}，每轮无条件在场；</li>
 *   <li>{@link #KIND_TASK}：会结束或会变的中期事项——按向量相关性注入；</li>
 *   <li>{@link #KIND_EXPERIENCE}：带情绪或教训的经历（叙事）——按向量相关性注入。</li>
 * </ul>
 *
 * <p>{@code embedding}/{@code embedding_model} 两列**故意不映射**：Hibernate 不认识 pgvector 的 {@code vector}
 * 类型，映射了 {@code ddl-auto: validate} 会失败；它们只由 {@link PgVectorStore} 的原生 SQL 读写。
 */
@Entity
@Table(name = "memory", indexes = {
        @Index(name = "idx_memory_user_status", columnList = "userId,status"),
        @Index(name = "idx_memory_user_kind", columnList = "userId,kind"),
        @Index(name = "idx_memory_user_always", columnList = "userId,alwaysInject")
})
@Getter
@Setter
@NoArgsConstructor
public class Memory {

    public static final String KIND_PROFILE = "PROFILE";
    public static final String KIND_TASK = "TASK";
    public static final String KIND_EXPERIENCE = "EXPERIENCE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 128, nullable = false)
    private String userId;

    @Column(length = 16, nullable = false)
    private String kind = KIND_TASK;

    /** 一条记忆的正文（原 core/work 的 content、原 episode 的 summary） */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false)
    private String content;

    /** 只有经历用得到（原 episode.title） */
    @Column(length = 200)
    private String title;

    /** 类型标签（原 episode.episode_type：EXPERIENCE / …） */
    @Column(length = 32)
    private String category;

    /** true = 每轮无条件注入（原来只有 core 是这样）；false = 按相关性注入 */
    @Column(nullable = false)
    private boolean alwaysInject;

    @Column(length = 32, nullable = false)
    private String status = MemoryStatus.ACTIVE.name();

    private Integer importance = 3;

    private Integer confidence = 85;

    /** 优先级 1-5（原 work 用；越大越优先） */
    private Integer priority = 3;

    @Column(length = 1000)
    private String keywords = "";

    /** 来源：extraction=自动提取 / confirm=用户确认（原 work.source） */
    @Column(length = 32)
    private String source = "extraction";

    @Column(length = 32)
    private String sourceType = "USER_DERIVED";

    @Column(length = 2000)
    private String sourceMessageIds = "";

    @Column(length = 1000)
    private String sourceMediaIds = "";

    private LocalDateTime validFrom;

    private LocalDateTime validUntil;

    private LocalDateTime occurredAt;

    private LocalDateTime endedAt;

    private LocalDateTime lastConfirmedAt;

    private LocalDateTime lastUsedAt;

    private LocalDateTime lastDecisionAt;

    private Long supersededById;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public Memory(String userId, String kind, String content) {
        LocalDateTime now = LocalDateTime.now();
        this.userId = userId;
        this.kind = kind == null || kind.isBlank() ? KIND_TASK : kind;
        this.content = content;
        this.alwaysInject = KIND_PROFILE.equals(this.kind);
        this.status = MemoryStatus.ACTIVE.name();
        this.sourceType = KIND_PROFILE.equals(this.kind) ? "USER_EXPLICIT" : "USER_DERIVED";
        this.confidence = KIND_PROFILE.equals(this.kind) ? 100 : 85;
        this.importance = KIND_PROFILE.equals(this.kind) ? 5 : 3;
        this.priority = 3;
        this.keywords = "";
        this.sourceMessageIds = "";
        this.sourceMediaIds = "";
        this.createdAt = now;
        this.updatedAt = now;
        this.lastConfirmedAt = now;
        this.lastDecisionAt = now;
        if (KIND_TASK.equals(this.kind)) {
            this.validFrom = now;
        }
    }

    public boolean isProfile() {
        return KIND_PROFILE.equals(kind);
    }

    public boolean isExperience() {
        return KIND_EXPERIENCE.equals(kind);
    }
}
