package com.liche.wechatagent.self;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentSelfEventRepository extends JpaRepository<AgentSelfEvent, Long> {

    /** 面板与反思读的最近事件（只追加，按 id 倒序即时间倒序） */
    List<AgentSelfEvent> findTop200ByOrderByIdDesc();

    List<AgentSelfEvent> findTop20ByOrderByIdDesc();

    long countByKind(String kind);
}
