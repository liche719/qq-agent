package com.liche.wechatagent.memory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface EpisodicMemoryRepository extends JpaRepository<EpisodicMemory, Long> {

    List<EpisodicMemory> findByUserIdOrderByCreatedAtDesc(String userId);

    List<EpisodicMemory> findByUserIdOrderByOccurredAtDesc(String userId);

    List<EpisodicMemory> findByUserIdAndStatusOrderByOccurredAtDesc(String userId, String status, Pageable pageable);
}
