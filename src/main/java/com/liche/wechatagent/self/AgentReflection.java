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
 * 反思产物：**二期**的反思流程写，一期只建表（免得二期再发一次迁移）。
 *
 * <p>硬规则（见 docs/self-layer-spec.md §4）：反思必须带 {@code inputEventIds} 证据链；
 * 合成失败就整条丢弃，**不写半成品**。重要度低于门槛时只留事件、不合成。
 */
@Entity
@Table(name = "agent_reflection", indexes = {
        @Index(name = "idx_self_reflection_level", columnList = "level,created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class AgentReflection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 反思层级：1 = 单条事件的归纳，2/3 = 更高层 */
    @Column(nullable = false)
    private Integer level = 1;

    /** 证据链：这次反思读了哪几条 agent_self_event.id */
    @Column(name = "input_event_ids", length = 500)
    private String inputEventIds;

    @Column(length = 1000, nullable = false)
    private String conclusion;

    @Column(nullable = false)
    private Integer importance = 0;

    /** 结论写回了哪个块（agent_self_block.id） */
    @Column(name = "written_back")
    private Long writtenBack;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
