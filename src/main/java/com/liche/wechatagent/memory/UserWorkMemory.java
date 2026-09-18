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

/** 第二层：中期工作记忆（长期留存，只有用户主动遗忘才会删除） */
@Entity
@Table(name = "user_work_memory", indexes = @Index(name = "idx_work_user", columnList = "userId"))
@Getter
@Setter
@NoArgsConstructor
public class UserWorkMemory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 128)
    private String userId;

    @Column(length = 2000)
    private String content;

    /** 优先级 1-5，默认 3，越大越优先加载 */
    private Integer priority = 3;

    /** 来源：extraction=自动提取 / confirm=用户确认 */
    @Column(length = 32)
    private String source = "extraction";

    @Column(length = 32)
    private String status = MemoryStatus.ACTIVE.name();

    @Column(length = 32)
    private String sourceType = "USER_EXPLICIT";

    private Integer confidence = 100;

    private Integer importance = 3;

    @Column(length = 1000)
    private String keywords = "";

    private LocalDateTime validFrom;

    private LocalDateTime validUntil;

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

    public UserWorkMemory(String userId, String content, Integer priority, String source) {
        this.userId = userId;
        this.content = content;
        this.priority = priority == null ? 3 : priority;
        this.source = source == null ? "extraction" : source;
        this.status = MemoryStatus.ACTIVE.name();
        this.sourceType = "USER_EXPLICIT";
        this.confidence = 100;
        this.importance = 3;
        this.keywords = "";
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        this.validFrom = this.createdAt;
        this.lastConfirmedAt = this.createdAt;
        this.lastDecisionAt = this.createdAt;
    }
}
