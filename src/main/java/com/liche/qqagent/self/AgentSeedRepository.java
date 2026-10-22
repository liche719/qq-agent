package com.liche.qqagent.self;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 念头的读写（护栏式的查询很少，够用就行） */
public interface AgentSeedRepository extends JpaRepository<AgentSeed, Long> {

    /** 按状态取最近的几条（注入提示词用） */
    List<AgentSeed> findByStatusOrderByUpdatedAtDesc(String status, org.springframework.data.domain.Pageable pageable);

    long countByStatus(String status);
}
