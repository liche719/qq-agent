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
 * 账：它许过的诺、做过的预测。
 *
 * <p>这是"得失"的落点：到期没兑现 → {@code BROKEN}，下次它自己那侧会带着这条。
 * **一期只记账，不影响它对用户的开场**（先看数据，别一上来就让它道歉）。
 */
@Entity
@Table(name = "agent_commitment", indexes = {
        @Index(name = "idx_self_commitment_status", columnList = "status,due_at")
})
@Getter
@Setter
@NoArgsConstructor
public class AgentCommitment {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_KEPT = "KEPT";
    public static final String STATUS_BROKEN = "BROKEN";
    public static final String STATUS_ABANDONED = "ABANDONED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 500, nullable = false)
    private String content;

    @Column(name = "due_at")
    private LocalDateTime dueAt;

    @Column(length = 16, nullable = false)
    private String status = STATUS_OPEN;

    @Column(length = 300)
    private String evidence;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
