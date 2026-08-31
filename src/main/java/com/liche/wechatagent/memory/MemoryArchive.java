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

/** 记忆归档记录：原始记忆ID与摘要，永久可回溯 */
@Entity
@Table(name = "memory_archive", indexes = @Index(name = "idx_archive_user", columnList = "userId"))
@Getter
@Setter
@NoArgsConstructor
public class MemoryArchive {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 128)
    private String userId;

    @Column(length = 4000)
    private String summary;

    /** 被归档的原始记忆 ID 列表（JSON 数组） */
    @Column(length = 2000)
    private String originalIds;

    private LocalDateTime createdAt;

    public MemoryArchive(String userId, String summary, String originalIds) {
        this.userId = userId;
        this.summary = summary;
        this.originalIds = originalIds;
        this.createdAt = LocalDateTime.now();
    }
}
