package com.liche.wechatagent.self;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AgentQuestNoteRepository extends JpaRepository<AgentQuestNote, Long> {

    List<AgentQuestNote> findByQuestIdOrderByCreatedAtDesc(Long questId);

    List<AgentQuestNote> findByQuestIdAndRetractedAtIsNullOrderByCreatedAtDesc(Long questId);

    List<AgentQuestNote> findByCreatedAtAfterOrderByCreatedAtDesc(LocalDateTime since);

    long countByQuestIdAndRetractedAtIsNull(Long questId);

    long countByQuestIdAndRetractedAtIsNotNull(Long questId);
}
