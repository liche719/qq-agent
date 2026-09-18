package com.liche.wechatagent.memory;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MemoryFactRepository extends JpaRepository<MemoryFact, Long> {

    List<MemoryFact> findByUserIdAndStatusOrderByUpdatedAtDesc(String userId, String status);

    /** 面板用：有效 + 被取代的历史一起看（含状态列） */
    List<MemoryFact> findByUserIdOrderByUpdatedAtDesc(String userId, Pageable pageable);

    List<MemoryFact> findByIdInAndUserId(List<Long> ids, String userId);

    /**
     * 同一个"属性槽"（subject + predicate 完全一样）的当前有效事实。
     *
     * <p>**这是"改教室没生效"的最后一道网**（计划 §12.3 第 1 条"精确匹配优先"）：
     * 向量召回是有门槛的，换个说法（"周二晚上的数学课教室改成506" vs "第一周周二晚数学课教室是303"）
     * 有可能差一点点没被召回到，那就退化成"新增一条"，同一个槽出现两个值、旧值也没作废。
     * 所以除了向量召回，还要按 subject+predicate 精确再查一次。
     */
    Optional<MemoryFact> findFirstByUserIdAndSubjectIgnoreCaseAndPredicateIgnoreCaseAndStatus(
            String userId, String subject, String predicate, String status);

    /** 同一件事（subject 相同）的当前有效事实：用来沿用它的**原始拼法**，免得同一件事两种写法各成一张卡 */
    Optional<MemoryFact> findFirstByUserIdAndSubjectIgnoreCaseAndStatus(String userId, String subject, String status);

    long countByUserIdAndStatus(String userId, String status);
}
