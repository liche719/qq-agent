package com.liche.wechatagent.schedule;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScheduledTaskRepository extends JpaRepository<ScheduledTask, Long> {

    List<ScheduledTask> findByUserIdOrderByCreatedAtDesc(String userId);

    List<ScheduledTask> findByEnabledTrue();

    long countByUserId(String userId);
}
