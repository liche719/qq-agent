package com.liche.wechatagent.exam;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ExamCheckinRepository extends JpaRepository<ExamCheckin, Long> {

    Optional<ExamCheckin> findByUserIdAndCheckinDate(String userId, LocalDate checkinDate);

    List<ExamCheckin> findByUserIdAndCheckinDateBetweenOrderByCheckinDateAsc(String userId, LocalDate from, LocalDate to);
}
