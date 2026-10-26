package com.liche.qqagent.memory;

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
 * 实体（同一件事的规范写法）。
 *
 * <p><b>为什么需要</b>（2026-10-26）：`memory_fact.subject` 完全由模型自由生成，同一件事会出现
 * 「周二晚·课程」「周二晚 课程」两种写法 → 拆成两张卡、取代链打不到一起、检索时命中错的那张。
 *
 * <p><b>保守归并</b>：只把"规范化之后完全一致"的写法合并（去空格、全角转半角、去掉书名号/括号注音），
 * **不做模糊匹配**——宁可多一张卡，也不能把两件不同的事并成一件（比如「10-28·周三·体育课」
 * 和「周二晚·数学课」）。
 */
@Entity
@Table(name = "memory_entity", indexes = {
        @Index(name = "idx_entity_user_updated", columnList = "user_id, updated_at")
})
@Getter
@Setter
@NoArgsConstructor
public class MemoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", length = 128, nullable = false)
    private String userId;

    @Column(length = 200, nullable = false)
    private String canonical;

    /** 见过的其它写法，用 ｜ 分隔（只用于展示/排查，不参与检索） */
    @Column(length = 2000)
    private String aliases;

    @Column(name = "fact_count", nullable = false)
    private int factCount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
