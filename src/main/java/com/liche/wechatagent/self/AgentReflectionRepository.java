package com.liche.wechatagent.self;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AgentReflectionRepository extends JpaRepository<AgentReflection, Long> {

    List<AgentReflection> findTop50ByOrderByIdDesc();

    /** 上一次成功反思（"攒够 N 轮"以它为起点） */
    Optional<AgentReflection> findTop1ByOrderByIdDesc();

    /** 今天的反思次数——预算硬顶用它判断 */
    long countByCreatedAtAfter(LocalDateTime after);

    List<AgentReflection> findByCreatedAtAfterOrderByIdAsc(LocalDateTime after);
}
