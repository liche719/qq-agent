package com.liche.wechatagent.reminder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReminderTaskRepository extends JpaRepository<ReminderTask, Long> {

    List<ReminderTask> findByUserIdAndStatusOrderByTriggerAtAsc(String userId, String status);

    List<ReminderTask> findByUserIdAndStatus(String userId, String status);

    Optional<ReminderTask> findByIdAndUserId(Long id, String userId);
}
