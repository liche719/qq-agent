package com.liche.wechatagent.exam;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface ExamProgressRepository extends JpaRepository<ExamProgress, Long> {

    List<ExamProgress> findByUserIdOrderBySubjectGroupAscIdAsc(String userId);

    List<ExamProgress> findByUserIdAndSubjectGroupOrderByIdAsc(String userId, String subjectGroup);

    /** 超期未完成的单元（面板与推送要提醒的那种） */
    List<ExamProgress> findByUserIdAndDueDateLessThanEqualOrderByDueDateAsc(String userId, LocalDate date);
}
