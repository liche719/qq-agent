package com.liche.qqagent.agent;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

/** 反思记忆的读写（2026-10-25）：同一工具再次失败时，先翻它上次怎么绕过去的 */
public interface TaskReflectionRepository extends JpaRepository<TaskReflection, Long> {

    List<TaskReflection> findByUserIdAndCreatedAtAfterOrderByCreatedAtDesc(
            String userId, LocalDateTime after, Pageable pageable);

    List<TaskReflection> findByUserIdAndToolInAndCreatedAtAfterOrderByCreatedAtDesc(
            String userId, List<String> tools, LocalDateTime after, Pageable pageable);
}
