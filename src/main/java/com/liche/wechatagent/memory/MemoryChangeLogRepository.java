package com.liche.wechatagent.memory;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MemoryChangeLogRepository extends JpaRepository<MemoryChangeLog, Long> {

    List<MemoryChangeLog> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);
}
