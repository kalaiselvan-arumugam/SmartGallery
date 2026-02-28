package com.smartgallery.dto;

/**
 * Search request from the UI — supports text queries with optional filters.
 */
public class SearchRequest {

    private String query;
    private SearchFilters filters;
    private int limit = 50;
    private int offset = 0;

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public SearchFilters getFilters() {
        return filters;
    }

    public void setFilters(SearchFilters filters) {
        this.filters = filters;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public int getOffset() {
        return offset;
    }

    public void setOffset(int offset) {
        this.offset = offset;
    }
}
