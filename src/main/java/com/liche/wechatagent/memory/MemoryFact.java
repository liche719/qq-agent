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

import java.time.LocalDateTime;

/**
 * 记忆事实层：**一条记忆 = 一个事实**（2026-09-18 加，见 docs/memory-vector-plan.md §2.3）。
 *
 * <p>为什么不把两件事写一条里：向量是整条文本的语义平均，塞得越多相似度越糊；
 * 拆成一条一事实后，"改教室"天然只碰教室那条（不相关字段不可能被改到），
 * 读的时候再按 {@link #subject} 聚合成"卡片视角"（见 {@code MemoryFactLoader}）。
 *
 * <p>{@link #source} 是**来源层级**，不是优先级：
 * {@code DOC}=从图片/文件抽出的基线（可能不全）、{@code USER}=用户后来的补充（补丁）、
 * {@code AUTO}=模型从对话推断。回答时可以回溯"图上写的是 X，你后来补充 Y"。
 *
 * <p>{@code embedding}/{@code embedding_model} 两列**故意不映射**：Hibernate 不认识 pgvector 的
 * {@code vector} 类型，映射了 `ddl-auto: validate` 会失败；它们只由 {@link MemoryFactVectorStore} 的 SQL 读写。
 */
@Entity
@Table(name = "memory_fact", indexes = {
        @Index(name = "idx_fact_user_status", columnList = "user_id, status"),
        @Index(name = "idx_fact_lookup", columnList = "user_id, subject, predicate")
})
@Getter
@Setter
@NoArgsConstructor
public class MemoryFact {

    /** 当前有效 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    /** 被新事实取代（**不删行**，留痕可回溯） */
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";
    /** 过了有效期（valid_to 到期后的归档态） */
    public static final String STATUS_EXPIRED = "EXPIRED";

    /** 基线：来自图片/文件 */
    public static final String SOURCE_DOC = "DOC";
    /** 补丁：来自用户自己的话 */
    public static final String SOURCE_USER = "USER";
    /** 模型从对话里推断的 */
    public static final String SOURCE_AUTO = "AUTO";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", length = 128, nullable = false)
    private String userId;

    /** 实体：同一张"卡片"的名字（例：第一周·周二晚·数学课） */
    @Column(length = 200, nullable = false)
    private String subject;

    /** 属性名（例：教室 / 时间 / 教师） */
    @Column(length = 64, nullable = false)
    private String predicate;

    /** 属性值（例：303） */
    @Column(length = 500, nullable = false)
    private String object;

    /** 一句完整事实（检索与注入用，例：第一周周二晚数学课的教室是 303） */
    @Column(length = 1000, nullable = false)
    private String content;

    @Column(length = 16, nullable = false)
    private String source;

    @Column(nullable = false)
    private Integer confidence = 70;

    @Column(length = 16, nullable = false)
    private String status = STATUS_ACTIVE;

    @Column(name = "valid_from")
    private LocalDateTime validFrom;

    @Column(name = "valid_to")
    private LocalDateTime validTo;

    /** 被哪条取代（旧行不删） */
    @Column(name = "superseded_by")
    private Long supersededBy;

    /** source=DOC 时指向 stored_media.id，便于回溯"图上写的是什么" */
    @Column(name = "doc_media_id")
    private Long docMediaId;

    @Column(length = 500)
    private String keywords;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
