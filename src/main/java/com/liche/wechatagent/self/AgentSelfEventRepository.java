package com.liche.wechatagent.self;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AgentSelfEventRepository extends JpaRepository<AgentSelfEvent, Long> {

    /** 面板与反思读的最近事件（只追加，按 id 倒序即时间倒序） */
    List<AgentSelfEvent> findTop200ByOrderByIdDesc();

    List<AgentSelfEvent> findTop20ByOrderByIdDesc();

    long countByKind(String kind);

    /** 某类事件（例如 JUDGE）按时间倒序取最近若干条 */
    List<AgentSelfEvent> findByKindOrderByIdDesc(String kind, Pageable pageable);

    /** 某个类别的全部判断——倾向提升要按 topic 把证据链整条拉出来 */
    List<AgentSelfEvent> findByKindAndTopicIgnoreCaseOrderByIdAsc(String kind, String topic);

    /** 反思输入：某个时间点之后的事件 */
    List<AgentSelfEvent> findByCreatedAtAfterOrderByIdAsc(LocalDateTime after);

    /** 判断类事件的类别清单（去重靠服务层，SQL 层不做 distinct 以免方言差异） */
    List<AgentSelfEvent> findByKindAndCreatedAtAfterOrderByIdAsc(String kind, LocalDateTime after);
}
