package com.liche.wechatagent.self;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface AgentQuestRunRepository extends JpaRepository<AgentQuestRun, Long> {

    /** 预算账：今天已经跑了几次（含跳过/失败，防止失败重试把预算吃穿） */
    long countByCreatedAtAfter(LocalDateTime since);

    long countByStatusAndCreatedAtAfter(String status, LocalDateTime since);

    AgentQuestRun findFirstByOrderByCreatedAtDesc();

    List<AgentQuestRun> findByCreatedAtAfterOrderByCreatedAtDesc(LocalDateTime since);

    /**
     * 今天已经花了多少元。
     *
     * <p>**必须落库算**，不能用内存计数：进程一重启内存就清零，那种预算拦不住任何东西。
     */
    @Query("select coalesce(sum(r.costYuan), 0) from AgentQuestRun r where r.createdAt >= :since")
    BigDecimal sumCostSince(@Param("since") LocalDateTime since);
}
