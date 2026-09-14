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
 * 领域里的一条笔记（spec §9.3）。
 *
 * <p>两条硬要求：
 * <ol>
 *   <li><b>带来源</b>：{@link #sourceUrl} 为空时面板单独计数——没有来源的"笔记"就是资料搬运。</li>
 *   <li><b>允许自己撤回</b>：{@link #retractedAt} 不是软删除，而是**标尺本身**——
 *       撤回比例说明它在核对过时结论，而不是在堆料（§9.3）。所以撤回的笔记仍然留在清单里。</li>
 * </ol>
 */
@Entity
@Table(name = "agent_quest_note", indexes = {
        @Index(name = "idx_self_quest_note_quest", columnList = "quest_id,created_at"),
        @Index(name = "idx_self_quest_note_recent", columnList = "created_at,retracted_at")
})
@Getter
@Setter
@NoArgsConstructor
public class AgentQuestNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "quest_id", nullable = false)
    private Long questId;

    @Column(length = 2000, nullable = false)
    private String content;

    /** 来源 URL：带来源的更新才算数 */
    @Column(name = "source_url", length = 1000)
    private String sourceUrl;

    @Column(name = "source_title", length = 300)
    private String sourceTitle;

    /** 自己判断这条过时了 → 撤回（保留原文，可回溯） */
    @Column(name = "retracted_at")
    private LocalDateTime retractedAt;

    @Column(name = "retract_reason", length = 300)
    private String retractReason;

    @Column(length = 300)
    private String evidence;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public boolean isRetracted() {
        return retractedAt != null;
    }
}
