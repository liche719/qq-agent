package com.liche.wechatagent.log;

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

/** 结构化操作日志（指令/提醒/搜索/错误等），按用户隔离 */
@Entity
@Table(name = "operation_log", indexes = @Index(name = "idx_oplog_user", columnList = "userId"))
@Getter
@Setter
@NoArgsConstructor
public class OperationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 128)
    private String userId;

    @Column(length = 64)
    private String action;

    @Column(length = 2000)
    private String detail;

    private LocalDateTime createdAt;

    public OperationLog(String userId, String action, String detail) {
        this.userId = userId;
        this.action = action;
        this.detail = detail;
        this.createdAt = LocalDateTime.now();
    }
}
