package com.smartgallery.service;

import com.smartgallery.dto.SearchFilters;
import com.smartgallery.dto.SearchResultItem;
import com.smartgallery.entity.ImageEntity;
import com.smartgallery.repository.ImageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Orchestrates CLIP semantic search and filtering.
 *
 * Flow for text search:
 * 1. Embed the query text via OnnxInferenceService.embedText()
 * 2. Run top-K vector search in InMemoryVectorStore (cosine similarity)
 * 3. Fetch full metadata from DB for the result IDs
 * 4. Apply date/tag/folder filters
 * 5. Return SearchResultItems sorted by score
 *
 * Flow for image-to-image search:
 * Same as above but step 1 uses OnnxInferenceService.embedImage()
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final OnnxInferenceService onnxInferenceService;
    private final InMemoryVectorStore vectorStore;
    private final ImageRepository imageRepository;

    public SearchService(OnnxInferenceService onnxInferenceService,
            InMemoryVectorStore vectorStore,
            ImageRepository imageRepository) {
        this.onnxInferenceService = onnxInferenceService;
        this.vectorStore = vectorStore;
        this.imageRepository = imageRepository;
    }

    /**
     * Semantic text search using CLIP text encoder.
     *
     * @param query   natural language query (e.g. "me on a bike in mountains")
     * @param filters optional filters (date range, tags, folder)
     * @param limit   maximum results to return
     * @return list of matching images ordered by similarity score descending
     */
    @Transactional(readOnly = true)
    public List<SearchResultItem> searchByText(String query, SearchFilters filters, int limit, int offset) {
        if (!onnxInferenceService.isReady()) {
            log.warn("ONNX models not ready — falling back to filename search");
            return fallbackFilenameSearch(query, filters, limit, offset);
        }

        float[] queryEmbedding = onnxInferenceService.embedText(query);
        if (queryEmbedding == null) {
            log.warn("Failed to embed query '{}', falling back to filename search", query);
            return fallbackFilenameSearch(query, filters, limit, offset);
        }

        return runVectorSearch(queryEmbedding, filters, limit, offset);
    }

    /**
     * Image-to-image search: finds images visually similar to the uploaded image.
     *
     * @param imagePath path to the uploaded query image (temp file)
     * @param filters   optional filters
     * @param limit     maximum results to return
     * @return list of similar images ordered by similarity score descending
     */
    @Transactional(readOnly = true)
    public List<SearchResultItem> searchByImage(Path imagePath, SearchFilters filters, int limit, int offset) {
        if (!onnxInferenceService.isReady()) {
            throw new IllegalStateException("ONNX models are not loaded. Please download models first.");
        }

        float[] queryEmbedding = onnxInferenceService.embedImage(imagePath);
        if (queryEmbedding == null) {
            throw new IllegalArgumentException("Could not process the uploaded image.");
        }

        return runVectorSearch(queryEmbedding, filters, limit, offset);
    }

    /**
     * Runs top-K vector similarity search and fetches enriched result metadata.
     */
    private List<SearchResultItem> runVectorSearch(float[] queryEmbedding, SearchFilters filters, int limit,
            int offset) {
        if (filters == null) {
            filters = new SearchFilters();
        }
        if (filters.getMinScore() == null) {
            // Default semantic similarity cutoff threshold for basic queries
            filters.setMinScore(0.24f);
        }

        // Over-fetch to allow for post-filtering
        int fetchK = Math.max(limit * 4, 100);
        List<InMemoryVectorStore.SearchHit> hits = vectorStore.findTopK(queryEmbedding, Math.min(fetchK, 2000), offset);

        if (hits.isEmpty()) {
            return Collections.emptyList();
        }

        // Fetch DB metadata for result IDs
        List<Long> orderedIds = hits.stream().map(h -> h.imageId).collect(Collectors.toList());
        Map<Long, ImageEntity> entityMap = imageRepository.findAllById(orderedIds)
                .stream().collect(Collectors.toMap(ImageEntity::getId, e -> e));

        // Build results in score-rank order, applying filters
        List<SearchResultItem> results = new ArrayList<>();
        for (InMemoryVectorStore.SearchHit hit : hits) {
            ImageEntity entity = entityMap.get(hit.imageId);
            if (entity == null)
                continue;

            // Apply filters
            if (!passesFilters(entity, hit.score, filters))
                continue;

            results.add(toResultItem(entity, hit.score));
            if (results.size() >= limit)
                break;
        }

        return results;
    }

    /**
     * Fallback search by filename when models are not loaded.
     */
    @Transactional(readOnly = true)
    public List<SearchResultItem> fallbackFilenameSearch(String query, SearchFilters filters, int limit, int offset) {
        int pageNumber = limit > 0 ? offset / limit : 0;

        if (query == null || query.isBlank()) {
            // Return most recent images
            return imageRepository.findAll(
                    org.springframework.data.domain.PageRequest.of(pageNumber, limit,
                            org.springframework.data.domain.Sort.by("indexedAt").descending()))
                    .stream()
                    .filter(e -> passesFilters(e, 0.0, filters))
                    .map(e -> toResultItem(e, 0.0))
                    .collect(Collectors.toList());
        }

        // Custom filename query pagination filtering via stream limit/skip because
        // repository method wasn't paginated
        return imageRepository.findByFileNameContaining(query)
                .stream()
                .filter(e -> passesFilters(e, 0.5, filters))
                .skip(offset)
                .limit(limit)
                .map(e -> toResultItem(e, 0.5))
                .collect(Collectors.toList());
    }

    /**
     * Tag-based search: finds images whose extraJson contains the given tag.
     */
    @Transactional(readOnly = true)
    public List<SearchResultItem> searchByTag(String tag, int limit) {
        if ("__sys_favorite__".equals(tag)) {
            return imageRepository.findByIsLovedTrue()
                    .stream()
                    .limit(limit)
                    .map(e -> toResultItem(e, 1.0))
                    .collect(Collectors.toList());
        }

        return imageRepository.findByTag("%\"" + tag + "\"%")
                .stream()
                .limit(limit)
                .map(e -> toResultItem(e, 1.0))
                .collect(Collectors.toList());
    }

    /**
     * Returns all images belonging to a specific folder.
     */
    @Transactional(readOnly = true)
    public List<SearchResultItem> browseFolder(String folderPath, int limit) {
        return imageRepository.findByFolderPath("%" + folderPath + "%")
                .stream()
                .limit(limit)
                .map(e -> toResultItem(e, 0.0))
                .collect(Collectors.toList());
    }

    /**
     * Applies post-vector-search filters to an image entity.
     */
    private boolean passesFilters(ImageEntity entity, double score, SearchFilters filters) {
        if (filters == null)
            return true;

        // Minimum score filter
        if (filters.getMinScore() != null && score < filters.getMinScore())
            return false;

        // Folder path filter
        if (filters.getFolderPath() != null && !filters.getFolderPath().isBlank()) {
            if (!entity.getFilePath().contains(filters.getFolderPath()))
                return false;
        }

        // Date from/to filter (based on lastModified)
        if (entity.getLastModified() != null) {
            if (filters.getDateFrom() != null && !filters.getDateFrom().isBlank()) {
                LocalDateTime from = LocalDateTime.parse(filters.getDateFrom() + "T00:00:00");
                if (entity.getLastModified().isBefore(from))
                    return false;
            }
            if (filters.getDateTo() != null && !filters.getDateTo().isBlank()) {
                LocalDateTime to = LocalDateTime.parse(filters.getDateTo() + "T23:59:59");
                if (entity.getLastModified().isAfter(to))
                    return false;
            }
        }

        // Tag filter logic
        if (filters.getTags() != null && !filters.getTags().isEmpty()) {
            // Check for the special favorite flag
            if (filters.getTags().contains("__sys_favorite__")) {
                if (!entity.isLoved()) {
                    return false;
                }
                // If it was the only tag, we're done
                if (filters.getTags().size() == 1) {
                    return true;
                }
            }

            String extraJson = entity.getExtraJson();
            if (extraJson == null || extraJson.isBlank())
                return false;
            try {
                com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(extraJson);
                com.fasterxml.jackson.databind.JsonNode tagsNode = root.get("tags");
                if (tagsNode == null || !tagsNode.isArray())
                    return false;

                for (String requiredTag : filters.getTags()) {
                    if (requiredTag.equals("__sys_favorite__"))
                        continue; // Already checked above

                    boolean found = false;
                    for (com.fasterxml.jackson.databind.JsonNode tNode : tagsNode) {
                        if (tNode.asText().equalsIgnoreCase(requiredTag)) {
                            found = true;
                            break;
                        }
                    }
                    if (!found)
                        return false;
                }
            } catch (Exception e) {
                return false;
            }
        }

        return true;
    }

    /**
     * Converts an ImageEntity to a SearchResultItem DTO for the API response.
     */
    private SearchResultItem toResultItem(ImageEntity entity, double score) {
        SearchResultItem item = new SearchResultItem();
        item.setId(entity.getId());
        item.setFilePath(entity.getFilePath());
        item.setFileName(new File(entity.getFilePath()).getName());
        item.setThumbUrl("/api/images/" + entity.getId() + "/thumb");
        item.setScore(Math.round(score * 10000.0) / 10000.0); // 4 decimal places
        item.setWidth(entity.getWidth());
        item.setHeight(entity.getHeight());
        item.setFileSize(entity.getFileSize());
        item.setLastModified(entity.getLastModified() != null ? entity.getLastModified().format(DATE_FMT) : null);
        item.setIndexedAt(entity.getIndexedAt() != null ? entity.getIndexedAt().format(DATE_FMT) : null);
        item.setExtraJson(entity.getExtraJson());
        item.setStatus(entity.getStatus());
        item.setLoved(entity.isLoved());
        item.setBlurred(entity.isBlurred());
        return item;
    }
}
