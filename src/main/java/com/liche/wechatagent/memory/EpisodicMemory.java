package com.liche.wechatagent.memory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** A durable summary of one meaningful experience in the user's life. */
@Entity
@Table(name = "episodic_memory", indexes = {
        @Index(name = "idx_episode_user_occurred", columnList = "userId,occurredAt"),
        @Index(name = "idx_episode_user_status", columnList = "userId,status")
})
@Getter
@Setter
@NoArgsConstructor
public class EpisodicMemory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 128, nullable = false)
    private String userId;

    @Column(length = 200, nullable = false)
    private String title;

    @Lob
    @Column(columnDefinition = "LONGTEXT", nullable = false)
    private String summary;

    @Column(length = 32, nullable = false)
    private String status = MemoryStatus.ACTIVE.name();

    @Column(length = 32, nullable = false)
    private String episodeType = "EXPERIENCE";

    private Integer importance = 3;

    private Integer confidence = 85;

    @Column(length = 32, nullable = false)
    private String sourceType = "USER_DERIVED";

    @Column(length = 1000)
    private String keywords = "";

    private LocalDateTime occurredAt;

    private LocalDateTime endedAt;

    private LocalDateTime lastConfirmedAt;

    private LocalDateTime lastUsedAt;

    private Long supersededById;

    @Column(length = 2000)
    private String sourceMessageIds = "";

    @Column(length = 1000)
    private String sourceMediaIds = "";

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public EpisodicMemory(String userId, String title, String summary, String episodeType,
                          int importance, int confidence, LocalDateTime occurredAt,
                          MemoryProvenance provenance) {
        LocalDateTime now = LocalDateTime.now();
        this.userId = userId;
        this.title = title;
        this.summary = summary;
        this.episodeType = episodeType;
        this.importance = importance;
        this.confidence = confidence;
        this.occurredAt = occurredAt == null ? now : occurredAt;
        this.lastConfirmedAt = now;
        this.createdAt = now;
        this.updatedAt = now;
        MemoryProvenance source = provenance == null ? MemoryProvenance.automatic("extraction") : provenance;
        this.sourceType = source.sourceType();
        this.sourceMessageIds = source.messageIdsColumn();
        this.sourceMediaIds = source.mediaIdsColumn();
    }
}
