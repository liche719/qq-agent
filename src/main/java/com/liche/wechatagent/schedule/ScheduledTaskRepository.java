package com.liche.wechatagent.schedule;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface ScheduledTaskRepository extends JpaRepository<ScheduledTask, Long> {

    List<ScheduledTask> findByUserIdOrderByCreatedAtDesc(String userId);

    List<ScheduledTask> findByEnabledTrue();

    long countByUserId(String userId);

    /**
     * 只写回「这次执行拥有」的那几列。
     *
     * <p>**不要**用「执行前读整行 → 跑一分钟 Agent → save」：全项目没有 {@code @DynamicUpdate}，
     * save 是 merge + 全列 UPDATE，会把执行前那份旧快照的 {@code enabled / title / instruction / cron}
     * 一起写回去。用户在任务执行期间点了「暂停」（库里已改成 {@code enabled=false}、Quartz job 也删了），
     * 结果会被这一行覆盖回 {@code enabled=true}——面板显示"已启用 + 有下次时间"，实际永远不会再跑
     * （只有下次重启 resync 才自愈），这正是 {@code setEnabled} 里那段回滚注释在防的坏状态。
     */
    @Modifying
    @Transactional
    @Query("update ScheduledTask t set t.status = :status, t.lastRunAt = :lastRunAt, t.lastResult = :lastResult, "
            + "t.lastError = :lastError, t.runCount = :runCount, t.nextRunAt = :nextRunAt, t.updatedAt = :updatedAt "
            + "where t.id = :id")
    int updateRunResult(@Param("id") Long id, @Param("status") String status,
                        @Param("lastRunAt") LocalDateTime lastRunAt, @Param("lastResult") String lastResult,
                        @Param("lastError") String lastError, @Param("runCount") Integer runCount,
                        @Param("nextRunAt") LocalDateTime nextRunAt, @Param("updatedAt") LocalDateTime updatedAt);
}
