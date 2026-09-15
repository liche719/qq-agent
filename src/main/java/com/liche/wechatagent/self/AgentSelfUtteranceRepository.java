package com.liche.wechatagent.self;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AgentSelfUtteranceRepository extends JpaRepository<AgentSelfUtterance, Long> {

    List<AgentSelfUtterance> findAllByOrderByCreatedAtDesc();

    List<AgentSelfUtterance> findByStatusOrderByCreatedAtDesc(String status);

    /** 攒着还没说的里面**最早**的那条（先想先说的先发） */
    java.util.Optional<AgentSelfUtterance> findFirstByStatusOrderByCreatedAtAsc(String status);

    long countByCreatedAtAfter(LocalDateTime since);

    /** 今天已经说出去几条 */
    long countByStatusAndSentAtAfter(String status, LocalDateTime since);
}
