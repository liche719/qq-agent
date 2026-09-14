package com.liche.wechatagent.self;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AgentStanceRepository extends JpaRepository<AgentStance, Long> {

    List<AgentStance> findByStatusOrderByUpdatedAtDesc(String status);

    List<AgentStance> findByTopicAndStatusOrderByIdDesc(String topic, String status);

    /** 复查队列：到点了还没复查的（ACTIVE 且 next_review_at 已过） */
    List<AgentStance> findByStatusAndNextReviewAtBeforeOrderByNextReviewAtAsc(String status, LocalDateTime time);

    long countByStatus(String status);
}
