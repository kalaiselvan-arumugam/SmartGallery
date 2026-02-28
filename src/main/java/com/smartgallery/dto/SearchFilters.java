package com.smartgallery.dto;

import java.util.List;

/**
 * Filters applied on top of semantic search results.
 */
public class SearchFilters {

    /** Filter by folder path prefix */
    private String folderPath;

    /** Filter by date from (ISO date string e.g. "2023-01-01") */
    private String dateFrom;

    /** Filter by date to (ISO date string) */
    private String dateTo;

    /** Filter by tags contained in extraJson */
    private List<String> tags;

    /** Minimum similarity score (0.0 to 1.0) */
    private Float minScore;

    public String getFolderPath() {
        return folderPath;
    }

    public void setFolderPath(String folderPath) {
        this.folderPath = folderPath;
    }

    public String getDateFrom() {
        return dateFrom;
    }

    public void setDateFrom(String dateFrom) {
        this.dateFrom = dateFrom;
    }

    public String getDateTo() {
        return dateTo;
    }

    public void setDateTo(String dateTo) {
        this.dateTo = dateTo;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public Float getMinScore() {
        return minScore;
    }

    public void setMinScore(Float minScore) {
        this.minScore = minScore;
    }
}
