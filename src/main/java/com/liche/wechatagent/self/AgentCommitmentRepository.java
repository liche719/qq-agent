package com.liche.wechatagent.self;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentCommitmentRepository extends JpaRepository<AgentCommitment, Long> {

    List<AgentCommitment> findByStatusOrderByDueAtAsc(String status);

    long countByStatus(String status);
}
