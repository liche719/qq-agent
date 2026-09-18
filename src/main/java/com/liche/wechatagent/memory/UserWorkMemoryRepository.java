package com.liche.wechatagent.memory;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface UserWorkMemoryRepository extends JpaRepository<UserWorkMemory, Long> {

    /**
     * 某个用户的全部工作记忆。
     *
     * <p>2026-09-18：原来这里是 {@code findByUserIdAndArchivedFalse} —— "归档"那套整块删掉之后
     * `archived` 列也没了（存量 34 行已恢复成活跃），所以这里就是"取全部"。
     */
    List<UserWorkMemory> findByUserId(String userId);

    List<UserWorkMemory> findByUserIdOrderByUpdatedAtDesc(String userId);
    List<UserWorkMemory> findByUserIdOrderByUpdatedAtDesc(String userId, Pageable pageable);

    long countByUserId(String userId);

    List<UserWorkMemory> findByValidUntilBefore(LocalDateTime validUntil);

    /** 所有出现过工作记忆的用户 */
    @Query("SELECT DISTINCT u.userId FROM UserWorkMemory u")
    List<String> findDistinctUserIds();

    /** 只更新使用时间这一列，避免整实体回写把并发修改的状态列冲掉 */
    @Modifying
    @Query("update UserWorkMemory memory set memory.lastUsedAt = :now where memory.id in :ids")
    int updateLastUsedAt(@Param("ids") List<Long> ids, @Param("now") LocalDateTime now);
}
