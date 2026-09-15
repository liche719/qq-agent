package com.liche.wechatagent.self;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AgentSelfUtteranceRepository extends JpaRepository<AgentSelfUtterance, Long> {

    List<AgentSelfUtterance> findAllByOrderByCreatedAtDesc();

    List<AgentSelfUtterance> findByStatusOrderByCreatedAtDesc(String status);

    long countByCreatedAtAfter(LocalDateTime since);
}
