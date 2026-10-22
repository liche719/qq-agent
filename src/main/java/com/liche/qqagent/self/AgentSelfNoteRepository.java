package com.liche.qqagent.self;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 「它自己的话」的读写（2026-10-20 起 block 与 event 合成这一张表）。
 *
 * 查询都按 {@code id} 倒序——表是只追加的（块改写也是插新行、旧行标 active=false），
 * 所以 id 顺序就是时间顺序，不用再排 created_at。
 */
public interface AgentSelfNoteRepository extends JpaRepository<AgentSelfNote, Long> {

    /** 当前生效的块（每个 slot 最多一条） */
    List<AgentSelfNote> findByKindAndActiveTrueOrderByIdAsc(String kind);

    /** 某个槽位的当前块 */
    List<AgentSelfNote> findByKindAndSlotAndActiveTrueOrderByIdDesc(String kind, String slot,
                                                                   org.springframework.data.domain.Pageable pageable);

    /** 最近的事件（不含块） */
    List<AgentSelfNote> findByKindNotOrderByIdDesc(String kind, org.springframework.data.domain.Pageable pageable);

    /** 某个时间点之后的事件（反思与面板用） */
    List<AgentSelfNote> findByKindNotAndCreatedAtAfterOrderByIdAsc(String kind, LocalDateTime after);

    /** 某个 topic 的活动跑过几次（自主活动护栏用） */
    long countByKindNotAndTopicAndCreatedAtAfter(String kind, String topic, LocalDateTime after);

    /** 判断类按类别取全（倾向提升要按 topic 把证据链整条拉出来） */
    List<AgentSelfNote> findByKindAndTopicIgnoreCaseOrderByIdAsc(String kind, String topic);

    /** 某类记录按时间倒序取最近若干（判断类别扫描用） */
    List<AgentSelfNote> findByKindOrderByIdDesc(String kind, org.springframework.data.domain.Pageable pageable);

    /** 某个 topic 最近的一条（护栏用） */
    List<AgentSelfNote> findByKindNotAndTopicAndCreatedAtAfterOrderByIdDesc(String kind, String topic,
                                                                          LocalDateTime after,
                                                                          org.springframework.data.domain.Pageable pageable);
}
