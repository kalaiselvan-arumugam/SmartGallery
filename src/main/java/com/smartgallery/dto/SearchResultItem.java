package com.smartgallery.dto;

/**
 * A single image result returned by the search API.
 */
public class SearchResultItem {

    private Long id;
    private String filePath;
    private String fileName;
    private String thumbUrl;
    private double score;
    private Integer width;
    private Integer height;
    private Long fileSize;
    private String lastModified;
    private String indexedAt;
    private String extraJson;
    private String status;
    private boolean isLoved;
    private boolean isBlurred;

    public SearchResultItem() {
    }

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

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getThumbUrl() {
        return thumbUrl;
    }

    public void setThumbUrl(String thumbUrl) {
        this.thumbUrl = thumbUrl;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
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

    public String getLastModified() {
        return lastModified;
    }

    public void setLastModified(String lastModified) {
        this.lastModified = lastModified;
    }

    public String getIndexedAt() {
        return indexedAt;
    }

    public void setIndexedAt(String indexedAt) {
        this.indexedAt = indexedAt;
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

    public void setLoved(boolean isLoved) {
        this.isLoved = isLoved;
    }

    public boolean isBlurred() {
        return isBlurred;
    }

    public void setBlurred(boolean isBlurred) {
        this.isBlurred = isBlurred;
    }
}
