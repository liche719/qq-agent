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

    /** 只取用户/机器人对话行（工具调用等 system 行不占提取窗口） */
    List<ConversationMemory> findByUserIdAndRoleInOrderByCreatedAtDesc(String userId, List<String> roles,
                                                                      Pageable pageable);

    List<ConversationMemory> findByUserIdAndContentContainingOrderByCreatedAtDesc(String userId,
                                                                                     String content,
                                                                                     Pageable pageable);

    List<ConversationMemory> findByUserIdAndCreatedAtAfterOrderByCreatedAtAsc(String userId,
                                                                                LocalDateTime createdAt);

    boolean existsByUserIdAndEventKey(String userId, String eventKey);
    long countByUserId(String userId);

    /**
     * 上一次提取之后，机主又说了多少条（**轮次驱动的触发就靠它**，2026-09-18）。
     *
     * <p>为什么从库里数、而不是在内存里记计数器：这个应用**每次部署都重启**，
     * 内存计数器会被反复清零——那样"攒够 15 轮"可能永远触发不了，记忆提取等于停摆。
     */
    @Query("select count(memory) from ConversationMemory memory where memory.userId = :userId "
            + "and memory.role = 'user' and memory.createdAt > :after")
    long countUserTurnsAfter(@Param("userId") String userId, @Param("after") LocalDateTime after);

    /** 待提取窗口里**最早**那条机主消息的时间（判"拖了 6 小时还没提取"用） */
    @Query("select min(memory.createdAt) from ConversationMemory memory where memory.userId = :userId "
            + "and memory.role = 'user' and memory.createdAt > :after")
    LocalDateTime oldestUserTurnAfter(@Param("userId") String userId, @Param("after") LocalDateTime after);

    List<ConversationMemory> findByUserIdAndIdGreaterThanOrderByIdAsc(String userId, Long id, Pageable pageable);

    @Modifying
    @Query("delete from ConversationMemory memory where memory.expiresAt is not null and memory.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") LocalDateTime cutoff);
}
