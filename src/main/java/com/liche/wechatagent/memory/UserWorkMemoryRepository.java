package com.liche.wechatagent.memory;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface UserWorkMemoryRepository extends JpaRepository<UserWorkMemory, Long> {

    List<UserWorkMemory> findByUserIdAndArchivedFalse(String userId);

    List<UserWorkMemory> findByUserIdOrderByUpdatedAtDesc(String userId);
    List<UserWorkMemory> findByUserIdOrderByUpdatedAtDesc(String userId, Pageable pageable);

    long countByUserIdAndArchivedFalse(String userId);

    /** 当前仍然生效的工作记忆（面板总览用；已归档的不算，和用户页口径保持一致） */
    long countByArchivedFalse();

    List<UserWorkMemory> findByArchivedFalseAndValidUntilBefore(java.time.LocalDateTime validUntil);

    /** 最老旧的低优先级记忆（用于归档压缩） */
    List<UserWorkMemory> findByUserIdAndArchivedFalseOrderByPriorityAscCreatedAtAsc(String userId, Pageable pageable);

    /** 所有出现过工作记忆的用户（用于周期归档扫描） */
    @org.springframework.data.jpa.repository.Query("SELECT DISTINCT u.userId FROM UserWorkMemory u")
    List<String> findDistinctUserIds();

    /** 只更新使用时间这一列，避免整实体回写把并发修改的状态列（archived 等）冲掉 */
    @Modifying
    @Query("update UserWorkMemory memory set memory.lastUsedAt = :now where memory.id in :ids")
    int updateLastUsedAt(@Param("ids") List<Long> ids, @Param("now") LocalDateTime now);
}
