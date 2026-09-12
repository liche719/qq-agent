package com.liche.wechatagent.memory;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

public interface EpisodicMemoryRepository extends JpaRepository<EpisodicMemory, Long> {
    long countByUserId(String userId);

    List<EpisodicMemory> findByUserIdOrderByCreatedAtDesc(String userId);
    List<EpisodicMemory> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    List<EpisodicMemory> findByUserIdOrderByOccurredAtDesc(String userId);

    List<EpisodicMemory> findByUserIdAndStatusOrderByOccurredAtDesc(String userId, String status, Pageable pageable);

    /**
     * 只刷 lastUsedAt。
     *
     * <p>**不要**用 saveAll 整实体回写：全项目没有 {@code @DynamicUpdate}，回写会按内存里的旧快照
     * 覆盖所有列——并发（记忆提取线程把某条标成 SUPERSEDED/归档、或用户刚删掉）时会把状态冲回去，
     * 已失效的记忆"复活"、已删的还可能被重新插回。core/work 记忆的同类问题已按这个方式修过。
     */
    @Modifying
    @Query("update EpisodicMemory memory set memory.lastUsedAt = :now where memory.id in :ids")
    int updateLastUsedAt(@Param("ids") List<Long> ids, @Param("now") LocalDateTime now);
}
