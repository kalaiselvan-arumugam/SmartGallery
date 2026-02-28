package com.smartgallery.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * A watched folder path that the file system watcher monitors for new/changed
 * images.
 */
@Entity
@Table(name = "watched_folders")
public class WatchedFolderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "folder_path", nullable = false, length = 2048, unique = true)
    private String folderPath;

    @Column(name = "active")
    private boolean active = true;

    @Column(name = "added_at")
    private LocalDateTime addedAt;

    @Column(name = "image_count")
    private Long imageCount = 0L;

    public WatchedFolderEntity() {
    }

    public WatchedFolderEntity(String folderPath) {
        this.folderPath = folderPath;
        this.addedAt = LocalDateTime.now();
        this.active = true;
    }

    // ───────────── getters / setters ─────────────

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFolderPath() {
        return folderPath;
    }

    public void setFolderPath(String folderPath) {
        this.folderPath = folderPath;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public LocalDateTime getAddedAt() {
        return addedAt;
    }

    public void setAddedAt(LocalDateTime addedAt) {
        this.addedAt = addedAt;
    }

    public Long getImageCount() {
        return imageCount;
    }

    public void setImageCount(Long imageCount) {
        this.imageCount = imageCount;
    }
}
