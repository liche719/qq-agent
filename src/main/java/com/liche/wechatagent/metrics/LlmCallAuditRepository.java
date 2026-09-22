package com.liche.wechatagent.metrics;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface LlmCallAuditRepository extends JpaRepository<LlmCallAudit, Long> {

    /** 某个时刻以后一共花了多少元——**必须落库算**，内存计数一重启就等于免费 */
    @Query("select coalesce(sum(a.costYuan), 0) from LlmCallAudit a where a.createdAt >= :since")
    BigDecimal sumCostSince(@Param("since") LocalDateTime since);

    /**
     * 按场景聚合（调用数 / 金额 / 平均输入 / 缓存命中率），花钱多的排在前面。
     * 面板与手工排查共用这一条，避免每处各写一份口径。
     */
    @Query(value = """
            select scenario,
                   count(*)                                              as calls,
                   coalesce(sum(cost_yuan), 0)                           as yuan,
                   coalesce(round(avg(prompt_tokens)), 0)                 as avg_prompt,
                   coalesce(sum(cache_hit_tokens), 0)                     as hit_tokens,
                   coalesce(sum(prompt_tokens), 0)                        as prompt_tokens,
                   coalesce(sum(completion_tokens), 0)                    as completion_tokens
              from llm_call_audit
             where created_at >= :since
             group by scenario
             order by yuan desc
            """, nativeQuery = true)
    List<Object[]> summaryByScenario(@Param("since") LocalDateTime since);

    /** 按天聚合 */
    @Query(value = """
            select created_at::date                                  as day,
                   count(*)                                          as calls,
                   coalesce(sum(cost_yuan), 0)                       as yuan,
                   coalesce(sum(prompt_tokens), 0)                   as prompt_tokens,
                   coalesce(sum(completion_tokens), 0)               as completion_tokens,
                   coalesce(sum(cache_hit_tokens), 0)                as hit_tokens
              from llm_call_audit
             where created_at >= :since
             group by 1
             order by 1
            """, nativeQuery = true)
    List<Object[]> summaryByDay(@Param("since") LocalDateTime since);
}
