package com.liche.wechatagent.interview;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InterviewRoundRepository extends JpaRepository<InterviewRound, Long> {

    List<InterviewRound> findByUserIdAndSessionIdOrderBySeqAsc(String userId, String sessionId);

    long countByUserIdAndSessionId(String userId, String sessionId);

    List<InterviewRound> findTop50ByUserIdOrderByCreatedAtDesc(String userId);
}
