package com.smartgallery.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Audit log for indexing operations — tracks success, failure, and timing per
 * image.
 */
@Entity
@Table(name = "reindex_log", indexes = {
        @Index(name = "idx_log_image_id", columnList = "image_id"),
        @Index(name = "idx_log_status", columnList = "status")
})
public class ReindexLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "image_id")
    private Long imageId;

    @Column(name = "file_path", length = 2048)
    private String filePath;

    @Column(name = "status", length = 32)
    private String status; // SUCCESS | ERROR | SKIPPED

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Lob
    @Column(name = "error_message", columnDefinition = "CLOB")
    private String errorMessage;

    @Column(name = "duration_ms")
    private Long durationMs;

    public ReindexLogEntity() {
    }

    // ───────────── getters / setters ─────────────

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getImageId() {
        return imageId;
    }

    public void setImageId(Long imageId) {
        this.imageId = imageId;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(LocalDateTime processedAt) {
        this.processedAt = processedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }
}
