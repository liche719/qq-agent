package com.liche.wechatagent.memory;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserWorkMemoryRepository extends JpaRepository<UserWorkMemory, Long> {

    List<UserWorkMemory> findByUserIdAndArchivedFalse(String userId);

    List<UserWorkMemory> findByUserIdOrderByUpdatedAtDesc(String userId);

    long countByUserIdAndArchivedFalse(String userId);

    List<UserWorkMemory> findByArchivedFalseAndValidUntilBefore(java.time.LocalDateTime validUntil);

    /** 最老旧的低优先级记忆（用于归档压缩） */
    List<UserWorkMemory> findByUserIdAndArchivedFalseOrderByPriorityAscCreatedAtAsc(String userId, Pageable pageable);

    /** 所有出现过工作记忆的用户（用于周期归档扫描） */
    @org.springframework.data.jpa.repository.Query("SELECT DISTINCT u.userId FROM UserWorkMemory u")
    List<String> findDistinctUserIds();
}
