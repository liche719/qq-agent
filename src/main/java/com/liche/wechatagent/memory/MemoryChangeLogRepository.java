package com.liche.wechatagent.memory;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MemoryChangeLogRepository extends JpaRepository<MemoryChangeLog, Long> {

    List<MemoryChangeLog> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE MemoryChangeLog log SET log.beforeContent = null, log.afterContent = null "
            + "WHERE log.userId = :userId AND log.layer = :layer AND log.targetId = :targetId")
    int redactContentForMemory(@Param("userId") String userId,
                               @Param("layer") String layer,
                               @Param("targetId") Long targetId);
}
