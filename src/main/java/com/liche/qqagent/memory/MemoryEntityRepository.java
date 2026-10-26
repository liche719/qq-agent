package com.liche.qqagent.memory;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 实体归一的读写（2026-10-26） */
public interface MemoryEntityRepository extends JpaRepository<MemoryEntity, Long> {

    Optional<MemoryEntity> findFirstByUserIdAndCanonical(String userId, String canonical);

    List<MemoryEntity> findByUserIdOrderByFactCountDescUpdatedAtDesc(String userId, Pageable pageable);
}
