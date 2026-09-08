package com.liche.wechatagent.memory;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ConversationMemoryRepository extends JpaRepository<ConversationMemory, Long> {

    List<ConversationMemory> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    List<ConversationMemory> findByUserIdAndContentContainingOrderByCreatedAtDesc(String userId,
                                                                                     String content,
                                                                                     Pageable pageable);

    List<ConversationMemory> findByUserIdAndCreatedAtAfterOrderByCreatedAtAsc(String userId,
                                                                                LocalDateTime createdAt);

    boolean existsByUserIdAndEventKey(String userId, String eventKey);
    long countByUserId(String userId);

    List<ConversationMemory> findByUserIdAndIdGreaterThanOrderByIdAsc(String userId, Long id, Pageable pageable);

    @Modifying
    @Query("delete from ConversationMemory memory where memory.expiresAt is not null and memory.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") LocalDateTime cutoff);
}
