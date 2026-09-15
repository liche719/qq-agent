package com.liche.wechatagent.self;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface AgentQuestRunRepository extends JpaRepository<AgentQuestRun, Long> {

    AgentQuestRun findFirstByOrderByCreatedAtDesc();

    /**
     * 今天已经花了多少元。
     *
     * <p>**必须落库算**，不能用内存计数：进程一重启内存就清零，那种预算拦不住任何东西。
     */
    @Query("select coalesce(sum(r.costYuan), 0) from AgentQuestRun r where r.createdAt >= :since")
    BigDecimal sumCostSince(@Param("since") LocalDateTime since);
}
