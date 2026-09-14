package com.liche.wechatagent.self;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentSelfBlockRepository extends JpaRepository<AgentSelfBlock, Long> {

    List<AgentSelfBlock> findAllByOrderByBlockTypeAscLabelAsc();

    List<AgentSelfBlock> findByBlockTypeOrderByLabelAsc(String blockType);

    Optional<AgentSelfBlock> findByBlockTypeAndLabel(String blockType, String label);
}
