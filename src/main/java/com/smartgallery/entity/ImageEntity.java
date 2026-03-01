package com.smartgallery.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Represents an indexed image file with its embedding and metadata.
 * The embedding is stored as a raw float32 byte array (BLOB).
 */
@Entity
@Table(name = "images", indexes = {
        @Index(name = "idx_file_path", columnList = "file_path", unique = true),
        @Index(name = "idx_file_hash", columnList = "file_hash"),
        @Index(name = "idx_last_modified", columnList = "last_modified")
})
public class ImageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "file_path", nullable = false, length = 2048)
    private String filePath;

    @Column(name = "thumb_path", length = 2048)
    private String thumbPath;

    @Column(name = "width")
    private Integer width;

    @Column(name = "height")
    private Integer height;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "file_hash", length = 64)
    private String fileHash;

    @Column(name = "last_modified")
    private LocalDateTime lastModified;

    @Column(name = "indexed_at")
    private LocalDateTime indexedAt;

    /**
     * CLIP embedding stored as raw float32 bytes (512 floats * 4 bytes = 2048
     * bytes).
     * Use EmbeddingUtils to convert between float[] and byte[].
     */
    @Lob
    @Column(name = "embedding", columnDefinition = "BLOB")
    private byte[] embedding;

    /**
     * JSON blob for extra metadata: tags, EXIF, user notes, detection results.
     */
    @Lob
    @Column(name = "extra_json", columnDefinition = "CLOB")
    private String extraJson;

    @Column(name = "status", length = 32)
    private String status = "INDEXED"; // INDEXED | ERROR | PENDING

    @Column(name = "is_loved", nullable = false)
    private boolean isLoved = false;

    @Column(name = "is_blurred", nullable = false)
    private boolean isBlurred = false;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    // ───────────── constructors ─────────────

    public ImageEntity() {
    }

    // ───────────── getters / setters ─────────────

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getThumbPath() {
        return thumbPath;
    }

    public void setThumbPath(String thumbPath) {
        this.thumbPath = thumbPath;
    }

    public Integer getWidth() {
        return width;
    }

    public void setWidth(Integer width) {
        this.width = width;
    }

    public Integer getHeight() {
        return height;
    }

    public void setHeight(Integer height) {
        this.height = height;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getFileHash() {
        return fileHash;
    }

    public void setFileHash(String fileHash) {
        this.fileHash = fileHash;
    }

    public LocalDateTime getLastModified() {
        return lastModified;
    }

    public void setLastModified(LocalDateTime lastModified) {
        this.lastModified = lastModified;
    }

    public LocalDateTime getIndexedAt() {
        return indexedAt;
    }

    public void setIndexedAt(LocalDateTime indexedAt) {
        this.indexedAt = indexedAt;
    }

    public byte[] getEmbedding() {
        return embedding;
    }

    public void setEmbedding(byte[] embedding) {
        this.embedding = embedding;
    }

    public String getExtraJson() {
        return extraJson;
    }

    public void setExtraJson(String extraJson) {
        this.extraJson = extraJson;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isLoved() {
        return isLoved;
    }

    public void setLoved(boolean loved) {
        isLoved = loved;
    }

    public boolean isBlurred() {
        return isBlurred;
    }

    public void setBlurred(boolean blurred) {
        isBlurred = blurred;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }
}
