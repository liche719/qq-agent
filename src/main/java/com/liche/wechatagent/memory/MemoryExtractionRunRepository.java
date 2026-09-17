package com.liche.wechatagent.memory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface MemoryExtractionRunRepository extends JpaRepository<MemoryExtractionRun, Long> {

    List<MemoryExtractionRun> findTop100ByOrderByCreatedAtDesc();

    List<MemoryExtractionRun> findByCreatedAtAfterOrderByCreatedAtDesc(LocalDateTime since);

    List<MemoryExtractionRun> findByUserIdOrderByCreatedAtDesc(String userId, org.springframework.data.domain.Pageable pageable);

    long countByCreatedAtAfter(LocalDateTime since);
}
