package com.liche.wechatagent.reminder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;

public interface ReminderTaskRepository extends JpaRepository<ReminderTask, Long> {
    long countByUserId(String userId);

    List<ReminderTask> findByUserIdAndStatusOrderByTriggerAtAsc(String userId, String status);

    List<ReminderTask> findByUserIdAndStatus(String userId, String status);

    List<ReminderTask> findByStatus(String status);

    List<ReminderTask> findByUserIdOrderByUpdatedAtDesc(String userId);
    List<ReminderTask> findByUserIdOrderByUpdatedAtDesc(String userId, Pageable pageable);

    List<ReminderTask> findByStatusAndTriggerAtBefore(String status, LocalDateTime time);

    Optional<ReminderTask> findByIdAndUserId(Long id, String userId);
}
