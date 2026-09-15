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
 * 它想说、但**现在不说**的一句话（三期领域②的「口」，用户选的是先观察）。
 *
 * <p>为什么要有它：如果它连"想说"都没有地方放，那它就只剩两种状态——**憋着**（等于没有表达）
 * 或者**打扰机主**（用户明确不要）。第三种状态是"记在自己这儿"：它有表达欲这件事被看见了，
 * 但机主不会收到任何消息。
 *
 * <p>{@link #why} 必填：面板上只看句子看不出它在想什么，"为什么想说"才是有信息量的那一半。
 * 这也是以后决定要不要开口时唯一的判据来源——**它自己想说的**，不是"对机主有没有用"。
 */
@Entity
@Table(name = "agent_self_utterance", indexes = {
        @Index(name = "idx_self_utterance_status", columnList = "status,created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class AgentSelfUtterance {

    /** 想说，但口没开（当前唯一的正常状态） */
    public static final String STATUS_PENDING = "PENDING";
    /** 已经说给机主了（口开了以后用） */
    public static final String STATUS_SENT = "SENT";
    /** 被闸拦下（条数/时段），留着记录（口开了以后用） */
    public static final String STATUS_SUPPRESSED = "SUPPRESSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 1000, nullable = false)
    private String content;

    /** 为什么想说（必填） */
    @Column(length = 500, nullable = false)
    private String why;

    @Column(name = "quest_id")
    private Long questId;

    @Column(length = 16, nullable = false)
    private String status = STATUS_PENDING;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(length = 300)
    private String evidence;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
