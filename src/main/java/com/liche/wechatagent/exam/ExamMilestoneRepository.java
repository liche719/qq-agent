package com.liche.wechatagent.exam;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExamMilestoneRepository extends JpaRepository<ExamMilestone, Long> {

    List<ExamMilestone> findByUserIdOrderByDueDateAscIdAsc(String userId);
}
