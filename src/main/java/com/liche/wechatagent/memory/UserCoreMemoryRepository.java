package com.liche.wechatagent.memory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;

public interface UserCoreMemoryRepository extends JpaRepository<UserCoreMemory, Long> {

    List<UserCoreMemory> findByUserIdOrderByUpdatedAtDesc(String userId);
    List<UserCoreMemory> findByUserIdOrderByUpdatedAtDesc(String userId, Pageable pageable);

    List<UserCoreMemory> findByUserIdOrderByCreatedAtAsc(String userId);

    long countByUserId(String userId);

    /** 只更新使用时间这一列，避免整实体回写把并发修改的状态列（SUPERSEDED 等）冲掉 */
    @Modifying
    @Query("update UserCoreMemory memory set memory.lastUsedAt = :now where memory.id in :ids")
    int updateLastUsedAt(@Param("ids") List<Long> ids, @Param("now") LocalDateTime now);
}
