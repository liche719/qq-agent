package com.liche.wechatagent.self;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentQuestRepository extends JpaRepository<AgentQuest, Long> {

    /** 同时只有一个 ACTIVE：作业与注入都走它 */
    Optional<AgentQuest> findFirstByStatusOrderByUpdatedAtDesc(String status);

    List<AgentQuest> findByStatusOrderByUpdatedAtDesc(String status);

    List<AgentQuest> findAllByOrderByUpdatedAtDesc();

    long countByStatus(String status);
}
