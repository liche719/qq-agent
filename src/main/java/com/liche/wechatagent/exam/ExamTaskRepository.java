package com.liche.wechatagent.exam;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface ExamTaskRepository extends JpaRepository<ExamTask, Long> {

    List<ExamTask> findByUserIdAndPlanDateOrderBySortOrderAscIdAsc(String userId, LocalDate planDate);

    List<ExamTask> findByUserIdAndPlanDateBetweenOrderByPlanDateAscSortOrderAscIdAsc(String userId, LocalDate from, LocalDate to);

    List<ExamTask> findByUserIdAndStatusOrderByPlanDateAscIdAsc(String userId, String status);

    long countByUserIdAndPlanDate(String userId, LocalDate planDate);
}
