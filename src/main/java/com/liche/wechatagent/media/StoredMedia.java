package com.liche.wechatagent.media;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "stored_media", indexes = {
        @Index(name = "idx_media_user_status", columnList = "userId,status"),
        @Index(name = "idx_media_user_hash", columnList = "userId,sha256")
})
@Getter
@Setter
@NoArgsConstructor
public class StoredMedia {

    public static final String ACTIVE = "ACTIVE";
    public static final String TRASHED = "TRASHED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 128, nullable = false)
    private String userId;

    @Column(length = 255, nullable = false)
    private String fileName;

    @Column(length = 255)
    private String originalName;

    @Column(length = 128)
    private String contentType;

    @Column(length = 1024, nullable = false)
    private String relativePath;

    @Column(length = 64, nullable = false)
    private String sha256;

    private Long sizeBytes;

    @Column(length = 2000)
    private String summary;

    @Column(length = 1000)
    private String importanceReason;

    /** 长文本：可移植写法（MySQL→longtext、PostgreSQL→text） */
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column
    private String extractedText;

    @Column(length = 255)
    private String sourceMessageId;

    @Column(length = 2048)
    private String sourceUrl;

    @Column(length = 16, nullable = false)
    private String status = ACTIVE;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime trashedAt;

    @Column(length = 1000)
    private String trashReason;

    public StoredMedia(String userId, String fileName, String originalName, String contentType,
                       String relativePath, String sha256, long sizeBytes, String summary,
                       String importanceReason, String extractedText, String sourceMessageId) {
        this.userId = userId;
        this.fileName = fileName;
        this.originalName = originalName;
        this.contentType = contentType;
        this.relativePath = relativePath;
        this.sha256 = sha256;
        this.sizeBytes = sizeBytes;
        this.summary = summary;
        this.importanceReason = importanceReason;
        this.extractedText = extractedText;
        this.sourceMessageId = sourceMessageId;
        this.status = ACTIVE;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }
}
