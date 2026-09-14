package com.liche.wechatagent.self;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AgentQuestRunRepository extends JpaRepository<AgentQuestRun, Long> {

    /** 预算账：今天已经跑了几次（含跳过/失败，防止失败重试把预算吃穿） */
    long countByCreatedAtAfter(LocalDateTime since);

    long countByStatusAndCreatedAtAfter(String status, LocalDateTime since);

    AgentQuestRun findFirstByOrderByCreatedAtDesc();

    List<AgentQuestRun> findByCreatedAtAfterOrderByCreatedAtDesc(LocalDateTime since);
}
