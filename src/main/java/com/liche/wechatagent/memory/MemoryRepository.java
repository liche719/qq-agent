package com.liche.wechatagent.memory;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 记忆仓库（2026-09-18 三张表合并后唯一的一个）。
 *
 * <p>合并前这里有三个几乎同构的接口（core/work/episode），只是查询条件里带的 kind 不同。
 * 现在 kind 是一列，所以"某个用户 + 某种 kind + 某个状态"都用同一组方法表达。
 */
public interface MemoryRepository extends JpaRepository<Memory, Long> {

    List<Memory> findByUserId(String userId);

    List<Memory> findByUserIdOrderByUpdatedAtDesc(String userId);
    List<Memory> findByUserIdOrderByUpdatedAtDesc(String userId, Pageable pageable);

    List<Memory> findByUserIdOrderByCreatedAtAsc(String userId);
    List<Memory> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    List<Memory> findByUserIdAndKindOrderByUpdatedAtDesc(String userId, String kind);
    /** 面板用户详情用：合并前三张表各自分页，现在按 kind 分页取，再在调用方拼成一个数组 */
    List<Memory> findByUserIdAndKindOrderByUpdatedAtDesc(String userId, String kind, Pageable pageable);
    List<Memory> findByUserIdAndKindOrderByOccurredAtDesc(String userId, String kind);
    List<Memory> findByUserIdAndKindOrderByCreatedAtAsc(String userId, String kind);

    List<Memory> findByUserIdAndAlwaysInjectTrue(String userId);

    List<Memory> findByUserIdAndStatusOrderByOccurredAtDesc(String userId, String status, Pageable pageable);

    long countByUserId(String userId);
    long countByUserIdAndKind(String userId, String kind);
    long countByUserIdAndStatus(String userId, String status);

    /** 全库口径的按 kind 计数（总览面板用：不能传 null 用户，那样会被守卫挡成 0） */
    long countByKind(String kind);

    /** 到期时间已过的工作型记忆（生命周期扫描用） */
    List<Memory> findByValidUntilBefore(LocalDateTime validUntil);

    /** 所有出现过记忆的用户（启动补齐向量时遍历用） */
    @Query("SELECT DISTINCT m.userId FROM Memory m")
    List<String> findDistinctUserIds();

    /**
     * 只刷 lastUsedAt。
     *
     * <p>**不要**用 saveAll 整实体回写：全项目没有 {@code @DynamicUpdate}，回写会按内存里的旧快照覆盖所有列——
     * 并发（提取线程把某条标成 SUPERSEDED、或用户刚删掉）时会把状态冲回去。
     */
    @Modifying
    @Query("update Memory m set m.lastUsedAt = :now where m.id in :ids")
    int updateLastUsedAt(@Param("ids") List<Long> ids, @Param("now") LocalDateTime now);
}
