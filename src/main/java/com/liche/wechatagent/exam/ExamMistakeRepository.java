package com.liche.wechatagent.exam;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface ExamMistakeRepository extends JpaRepository<ExamMistake, Long> {

    List<ExamMistake> findByUserIdAndStatusOrderByIdAsc(String userId, String status);

    /** 今天（含以前）到期要复习的错题 */
    List<ExamMistake> findByUserIdAndStatusAndNextReviewDateLessThanEqualOrderByNextReviewDateAsc(
            String userId, String status, LocalDate date);

    long countByUserIdAndStatus(String userId, String status);
}
