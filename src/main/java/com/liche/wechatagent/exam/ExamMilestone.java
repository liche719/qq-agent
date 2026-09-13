package com.liche.wechatagent.exam;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 阶段里程碑：带截止日的检查点（基础一轮 2027-03-31、408 一轮 2027-06-30…），超期未完成会被点名。 */
@Entity
@Table(name = "exam_milestone", indexes = {
        @Index(name = "idx_exam_milestone_user", columnList = "user_id,due_date")
})
@Getter
@Setter
@NoArgsConstructor
public class ExamMilestone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", length = 128, nullable = false)
    private String userId;

    @Column(length = 120, nullable = false)
    private String name;

    private LocalDate dueDate;
    private LocalDateTime doneAt;

    @Column(length = 300)
    private String note;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
