package com.liche.wechatagent.memory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface MemoryExtractionRunRepository extends JpaRepository<MemoryExtractionRun, Long> {

    List<MemoryExtractionRun> findTop100ByOrderByCreatedAtDesc();

    List<MemoryExtractionRun> findByCreatedAtAfterOrderByCreatedAtDesc(LocalDateTime since);

    List<MemoryExtractionRun> findByUserIdOrderByCreatedAtDesc(String userId, org.springframework.data.domain.Pageable pageable);

    /**
     * 最近几次提取（新→旧）。轮次驱动的计数需要"上一次**真正生效**的提取"是什么时候，
     * 而 FAILED 那次不该算数（否则失败一次就把一段对话从待提取窗口里放过去了），
     * 所以取几条回来在代码里挑——`skip_reason &lt;&gt; 'FAILED'` 写在 SQL 里会把 NULL 行也排掉。
     */
    List<MemoryExtractionRun> findTop5ByUserIdOrderByCreatedAtDesc(String userId);

    long countByCreatedAtAfter(LocalDateTime since);
}
