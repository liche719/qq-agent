package com.liche.wechatagent.memory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Entity
@Table(name = "conversation_memory", uniqueConstraints = {
        @UniqueConstraint(name = "uq_conversation_user_event", columnNames = {"user_id", "event_key"})
}, indexes = {
        @Index(name = "idx_conversation_user_created", columnList = "userId,createdAt"),
        @Index(name = "idx_conversation_expires", columnList = "expiresAt")
})
@Getter
@Setter
@NoArgsConstructor
public class ConversationMemory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 128, nullable = false)
    private String userId;

    @Column(length = 16, nullable = false)
    private String role;

    @Column(length = 128)
    private String eventKey;

    /** 长文本：用可移植写法（MySQL→longtext、PostgreSQL→text），别写死 LONGTEXT（2026-09-17 迁 pg 时踩到） */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false)
    private String content;

    @Column(length = 2000)
    private String sourceMessageIds = "";

    @Column(length = 1000)
    private String sourceMediaIds = "";

    private LocalDateTime createdAt;

    private LocalDateTime expiresAt;

    public ConversationMemory(String userId, String role, String eventKey, String content,
                              List<String> sourceMessageIds, LocalDateTime createdAt,
                              LocalDateTime expiresAt) {
        this(userId, role, eventKey, content, sourceMessageIds, List.of(), createdAt, expiresAt);
    }

    public ConversationMemory(String userId, String role, String eventKey, String content,
                              List<String> sourceMessageIds, List<Long> sourceMediaIds,
                              LocalDateTime createdAt, LocalDateTime expiresAt) {
        this.userId = userId;
        this.role = role;
        this.eventKey = eventKey;
        this.content = content;
        this.sourceMessageIds = join(sourceMessageIds);
        this.sourceMediaIds = joinLongs(sourceMediaIds);
        this.createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
        this.expiresAt = expiresAt;
    }

    public List<String> sourceMessageIdList() {
        if (sourceMessageIds == null || sourceMessageIds.isBlank()) {
            return List.of();
        }
        return Arrays.stream(sourceMessageIds.split("\\|"))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    public List<Long> sourceMediaIdList() {
        if (sourceMediaIds == null || sourceMediaIds.isBlank()) {
            return List.of();
        }
        return Arrays.stream(sourceMediaIds.split("\\|"))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> {
                    try {
                        return Long.parseLong(value);
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                })
                .filter(value -> value != null && value > 0)
                .distinct()
                .toList();
    }

    private static String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.replace('|', '_').trim())
                .distinct()
                .limit(40)
                .reduce((left, right) -> left + "|" + right)
                .orElse("");
    }

    private static String joinLongs(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream()
                .filter(value -> value != null && value > 0)
                .distinct()
                .limit(40)
                .map(String::valueOf)
                .reduce((left, right) -> left + "|" + right)
                .orElse("");
    }
}
