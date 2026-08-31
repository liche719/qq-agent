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

/** 记忆操作日志：新增、更新与归档留痕；用户遗忘后会保留无正文操作事件。 */
@Entity
@Table(name = "memory_change_log", indexes = @Index(name = "idx_changelog_user", columnList = "userId"))
@Getter
@Setter
@NoArgsConstructor
public class MemoryChangeLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 128)
    private String userId;

    /** ADD / UPDATE / ARCHIVE / ARCHIVE_CREATE / CONFIRM_REJECT */
    @Column(length = 32)
    private String action;

    /** CORE / WORK / ARCHIVE */
    @Column(length = 32)
    private String layer;

    private Long targetId;

    @Column(length = 4000)
    private String beforeContent;

    @Column(length = 4000)
    private String afterContent;

    @Column(length = 512)
    private String reason;

    /** AUTO=自动提取 / USER=用户确认 / SYSTEM=系统流程 */
    @Column(length = 32)
    private String operator;

    private LocalDateTime createdAt;

    public MemoryChangeLog(String userId, String action, String layer, Long targetId,
                           String beforeContent, String afterContent, String reason, String operator) {
        this.userId = userId;
        this.action = action;
        this.layer = layer;
        this.targetId = targetId;
        this.beforeContent = beforeContent;
        this.afterContent = afterContent;
        this.reason = reason;
        this.operator = operator;
        this.createdAt = LocalDateTime.now();
    }
}
