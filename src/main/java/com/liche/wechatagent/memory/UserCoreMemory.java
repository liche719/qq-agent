package com.liche.wechatagent.memory;

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

import java.time.LocalDateTime;

/** 第三层：永久核心记忆，保存稳定身份、长期目标、原则和长期偏好。 */
@Entity
@Table(name = "user_core_memory", indexes = @Index(name = "idx_core_user", columnList = "userId"))
@Getter
@Setter
@NoArgsConstructor
public class UserCoreMemory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 128)
    private String userId;

    @Column(length = 4000)
    private String content;

    @Column(length = 32)
    private String status = MemoryStatus.ACTIVE.name();

    @Column(length = 32)
    private String sourceType = "USER_EXPLICIT";

    private Integer confidence = 100;

    private Integer importance = 5;

    @Column(length = 1000)
    private String keywords = "";

    private LocalDateTime lastConfirmedAt;

    private LocalDateTime lastUsedAt;

    private LocalDateTime lastDecisionAt;

    private Long supersededById;

    @Column(length = 2000)
    private String sourceMessageIds = "";

    @Column(length = 1000)
    private String sourceMediaIds = "";

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public UserCoreMemory(String userId, String content) {
        this.userId = userId;
        this.content = content;
        this.status = MemoryStatus.ACTIVE.name();
        this.sourceType = "USER_EXPLICIT";
        this.confidence = 100;
        this.importance = 5;
        this.keywords = "";
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        this.lastConfirmedAt = this.createdAt;
        this.lastDecisionAt = this.createdAt;
    }
}
