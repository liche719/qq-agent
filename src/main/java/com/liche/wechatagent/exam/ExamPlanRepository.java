package com.liche.wechatagent.exam;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExamPlanRepository extends JpaRepository<ExamPlan, String> {

    /** 需要自动推送的计划：开着 enabled 的都算（单用户场景一般只有一条） */
    List<ExamPlan> findByEnabledTrue();
}
