package com.liche.wechatagent.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** 用户档案：每用户独立人设提示词（无全局固定系统提示词） */
@Entity
@Table(name = "user_profile")
@Getter
@Setter
@NoArgsConstructor
public class UserProfile {

    /** OpenClaw 返回的微信 user_id，行级多租户主键 */
    @Id
    @Column(length = 128)
    private String userId;

    /** 该用户专属人设提示词 */
    @Column(length = 4000)
    private String persona;

    /** Whether the user allows automatic extraction of work memories. */
    private Boolean memoryEnabled = true;

    private Boolean proactiveCareEnabled = false;

    @Column(length = 16)
    private String proactiveCareCadence = "WEEKLY";

    private LocalDateTime nextCareAt;

    private LocalDateTime lastCareAt;

    @Column(length = 32)
    private String lastChannel;

    @Column(length = 128)
    private String lastBotId;

    private LocalDateTime lastSeenAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public UserProfile(String userId, String persona) {
        this.userId = userId;
        this.persona = persona;
        this.memoryEnabled = true;
        this.proactiveCareEnabled = false;
        this.proactiveCareCadence = "WEEKLY";
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }
}
