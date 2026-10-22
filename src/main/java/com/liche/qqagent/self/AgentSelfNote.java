package com.liche.qqagent.self;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 「它自己的一句话」（2026-10-20 大改：把 {@code agent_self_block} 与 {@code agent_self_event} 合并）。
 *
 * <p>为什么并：块的当前值和它的历史流水本来是同一件事的两个投影——块每次更新都会写一条事件。
 * 两张表各带一个 repo、各一套方法，工具层于是长出 {@code selfAppend/selfReplace/selfSummarize}
 * 三个"差不多"的东西（实测这三个合计只被调用 2 次）。
 *
 * <p>新模型一句话：**一句话 + 类型 + 槽位 + 证据**。
 * <ul>
 *   <li>{@code kind=BLOCK}：它对自己的描述（人设/任务/项目），{@code slot} 表明是哪个槽位；
 *       每个槽位只有一条 {@code active}，被改写的标 false 并用 {@code supersededBy} 指向新的一条
 *       ——**历史自带，不用另存**。</li>
 *   <li>其它 {@code kind}（NOTE / JUDGE / DISAGREE / QUEST_* …）：只追加的流水，{@code active} 恒 true。</li>
 * </ul>
 */
@Entity
@Table(name = "agent_self_note")
@Getter
@Setter
@NoArgsConstructor
public class AgentSelfNote {

    public static final String KIND_BLOCK = "BLOCK";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kind", length = 32, nullable = false)
    private String kind = "NOTE";

    /** 只对 BLOCK 有意义：PERSONA / TASK / PROJECT */
    @Column(name = "slot", length = 32, nullable = false)
    private String slot = "";

    @Column(name = "content", nullable = false)
    private String content = "";

    @Column(name = "evidence", length = 200, nullable = false)
    private String evidence = "";

    @Column(name = "topic", length = 128, nullable = false)
    private String topic = "";

    /** 立场（A/B/中立）；只对 JUDGE 有意义——倾向提升按 topic+stance 累计证据 */
    @Column(name = "stance", length = 16, nullable = false)
    private String stance = "";

    @Column(name = "importance", nullable = false)
    private int importance = 0;

    /** 只对 BLOCK 有意义；其它类型恒 true */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    /** 块被改写时指向新那条的 id */
    @Column(name = "superseded_by", nullable = false)
    private long supersededBy = 0;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public boolean isBlock() {
        return KIND_BLOCK.equals(kind);
    }
}
