package com.liche.wechatagent.self;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AgentLessonRepository extends JpaRepository<AgentLesson, Long> {

    /** 清单：未关闭的按"最近又犯"排前面 */
    List<AgentLesson> findByStatusInOrderByLastSeenAtDesc(List<String> statuses);

    List<AgentLesson> findByCategoryAndStatusInOrderByLastSeenAtDesc(String category, List<String> statuses);

    List<AgentLesson> findByStatusInAndNextReviewAtBeforeOrderByNextReviewAtAsc(List<String> statuses,
                                                                               LocalDateTime time);

    long countByStatusIn(List<String> statuses);

    /** 之后有没有同类新教训（复查时判定"没再犯/又犯了"） */
    List<AgentLesson> findByCategoryAndLastSeenAtAfter(String category, LocalDateTime after);
}
