package com.smartgallery.controller;

import com.smartgallery.config.AppConfig;
import com.smartgallery.dto.SearchRequest;
import com.smartgallery.dto.SearchResultItem;
import com.smartgallery.entity.ImageEntity;
import com.smartgallery.repository.ImageRepository;
import com.smartgallery.service.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST controller for image search and thumbnail serving.
 *
 * Endpoints:
 * POST /api/search — text + filter semantic search
 * POST /api/search/image — image-to-image search via upload
 * GET /api/search/tags — tag search
 * GET /api/images/{id}/thumb — serve thumbnail bytes
 * GET /api/images/{id} — get image metadata
 * PATCH /api/images/{id}/tags — update image tags
 */
@RestController
@RequestMapping("/api")
public class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);

    private final SearchService searchService;
    private final ImageRepository imageRepository;
    private final AppConfig appConfig;

    public SearchController(SearchService searchService,
            ImageRepository imageRepository,
            AppConfig appConfig) {
        this.searchService = searchService;
        this.imageRepository = imageRepository;
        this.appConfig = appConfig;
    }

    /**
     * Main semantic text search endpoint.
     * POST /api/search
     * Body: { "query": "...", "filters": {...}, "limit": 50, "offset": 0 }
     */
    @PostMapping("/search")
    public ResponseEntity<Map<String, Object>> search(@RequestBody SearchRequest request) {
        String query = request.getQuery() != null ? request.getQuery().trim() : "";

        if (request.getFilters() == null) {
            request.setFilters(new com.smartgallery.dto.SearchFilters());
        }

        com.smartgallery.util.DateFilterParser.ParsedQuery parsed = com.smartgallery.util.DateFilterParser.parse(query);
        query = parsed.cleanQuery;

        // NL dates override JSON body dates if specified in the query text
        if (parsed.dateFrom != null)
            request.getFilters().setDateFrom(parsed.dateFrom);
        if (parsed.dateTo != null)
            request.getFilters().setDateTo(parsed.dateTo);

        int limit = Math.min(request.getLimit() > 0 ? request.getLimit() : 50, appConfig.getSearchLimit());
        int offset = Math.max(0, request.getOffset());

        try {
            List<SearchResultItem> results = searchService.searchByText(query, request.getFilters(), limit, offset);
            return ResponseEntity.ok(Map.of(
                    "results", results,
                    "count", results.size(),
                    "totalCount", imageRepository.countIndexed(),
                    "query", query));
        } catch (Exception e) {
            log.error("Search error for query '{}': {}", query, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage(), "results", List.of()));
        }
    }

    /**
     * Image-to-image search via multipart file upload.
     * POST /api/search/image
     */
    @PostMapping("/search/image")
    public ResponseEntity<Map<String, Object>> searchByImage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No image file provided"));
        }

        Path tempFile = null;
        try {
            // Save upload to a temp file
            String suffix = "." + getExtension(file.getOriginalFilename());
            tempFile = Files.createTempFile("sg_query_", suffix);
            file.transferTo(tempFile.toFile());

            int effectiveLimit = Math.min(limit, appConfig.getSearchLimit());
            List<SearchResultItem> results = searchService.searchByImage(tempFile, null, effectiveLimit, 0);
            return ResponseEntity.ok(Map.of("results", results, "count", results.size()));

        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Image search error: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Image processing failed: " + e.getMessage()));
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Tag-based image search.
     * GET /api/search/tags?tag=mountain
     */
    @GetMapping("/search/tags")
    public ResponseEntity<Map<String, Object>> searchByTag(
            @RequestParam String tag,
            @RequestParam(defaultValue = "50") int limit) {
        List<SearchResultItem> results = searchService.searchByTag(tag, limit);
        return ResponseEntity.ok(Map.of("results", results, "count", results.size(), "tag", tag));
    }

    /**
     * Browse images in a specific folder path.
     * GET /api/search/browse?folder=/path/to/folder
     */
    @GetMapping("/search/browse")
    public ResponseEntity<Map<String, Object>> browseFolder(
            @RequestParam String folder,
            @RequestParam(defaultValue = "100") int limit) {
        List<SearchResultItem> results = searchService.browseFolder(folder, limit);
        return ResponseEntity.ok(Map.of("results", results, "count", results.size(), "folder", folder));
    }

    /**
     * Serves thumbnail image bytes.
     * GET /api/images/{id}/thumb
     */
    @GetMapping("/images/{id}/thumb")
    public ResponseEntity<byte[]> getThumbnail(@PathVariable Long id) {
        Optional<ImageEntity> opt = imageRepository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ImageEntity entity = opt.get();
        String thumbPath = entity.getThumbPath();

        // Try thumbnail first
        if (thumbPath != null) {
            File thumbFile = new File(thumbPath);
            if (thumbFile.exists()) {
                try {
                    byte[] bytes = Files.readAllBytes(thumbFile.toPath());
                    return ResponseEntity.ok()
                            .contentType(MediaType.IMAGE_JPEG)
                            .body(bytes);
                } catch (Exception e) {
                    log.warn("Failed to read thumbnail for id {}: {}", id, e.getMessage());
                }
            }
        }

        // Fall back to serving the original image
        File original = new File(entity.getFilePath());
        if (!original.exists()) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] bytes = Files.readAllBytes(original.toPath());
            String mime = Files.probeContentType(original.toPath());
            MediaType mediaType = mime != null ? MediaType.parseMediaType(mime) : MediaType.IMAGE_JPEG;
            return ResponseEntity.ok().contentType(mediaType).body(bytes);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Serves the full-resolution original image bytes.
     * GET /api/images/{id}/full
     */
    @GetMapping("/images/{id}/full")
    public ResponseEntity<byte[]> getFullImage(@PathVariable Long id) {
        Optional<ImageEntity> opt = imageRepository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        File original = new File(opt.get().getFilePath());
        if (!original.exists()) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] bytes = Files.readAllBytes(original.toPath());
            String mime = Files.probeContentType(original.toPath());
            MediaType mediaType = mime != null ? MediaType.parseMediaType(mime) : MediaType.IMAGE_JPEG;
            return ResponseEntity.ok().contentType(mediaType).body(bytes);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Returns image metadata by ID.
     * GET /api/images/{id}
     */
    @GetMapping("/images/{id}")
    public ResponseEntity<SearchResultItem> getImageMetadata(@PathVariable Long id) {
        return imageRepository.findById(id)
                .map(e -> {
                    SearchResultItem item = new SearchResultItem();
                    item.setId(e.getId());
                    item.setFilePath(e.getFilePath());
                    item.setFileName(new File(e.getFilePath()).getName());
                    item.setThumbUrl("/api/images/" + e.getId() + "/thumb");
                    item.setWidth(e.getWidth());
                    item.setHeight(e.getHeight());
                    item.setFileSize(e.getFileSize());
                    item.setLastModified(e.getLastModified() != null ? e.getLastModified().toString() : null);
                    item.setIndexedAt(e.getIndexedAt() != null ? e.getIndexedAt().toString() : null);
                    item.setExtraJson(e.getExtraJson());
                    item.setStatus(e.getStatus());
                    item.setLoved(e.isLoved());
                    item.setBlurred(e.isBlurred());
                    return ResponseEntity.ok(item);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Updates the tags/extraJson for an image.
     * PATCH /api/images/{id}/tags
     * Body: { "tags": ["landscape", "sunset"], "notes": "..." }
     */
    @PatchMapping("/images/{id}/tags")
    @Transactional
    public ResponseEntity<Map<String, String>> updateTags(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        return imageRepository.findById(id).map(entity -> {
            try {
                // Check for the favorite system flag
                if (body.containsKey("tags") && body.get("tags") instanceof java.util.List) {
                    java.util.List<?> tags = (java.util.List<?>) body.get("tags");
                    entity.setLoved(tags.contains("__sys_favorite__"));
                }

                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                entity.setExtraJson(mapper.writeValueAsString(body));
                imageRepository.save(entity);
                return ResponseEntity.ok(Map.of("status", "updated"));
            } catch (Exception e) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", e.getMessage()));
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * Toggles the blur privacy state for an image.
     * PATCH /api/images/{id}/blur?blurred=true
     */
    @PatchMapping("/images/{id}/blur")
    @Transactional
    public ResponseEntity<Map<String, String>> toggleBlur(
            @PathVariable Long id,
            @RequestParam boolean blurred) {
        return imageRepository.findById(id).map(entity -> {
            entity.setBlurred(blurred);
            imageRepository.save(entity);
            return ResponseEntity.ok(Map.of("status", "updated", "blurred", String.valueOf(blurred)));
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * Deletes an image from the index (does not delete the source file).
     * DELETE /api/images/{id}
     */
    @DeleteMapping("/images/{id}")
    @Transactional
    public ResponseEntity<Map<String, String>> deleteFromIndex(@PathVariable Long id) {
        return imageRepository.findById(id).map(entity -> {
            imageRepository.delete(entity);
            return ResponseEntity.ok(Map.of("status", "deleted", "id", String.valueOf(id)));
        }).orElse(ResponseEntity.notFound().build());
    }

    private String getExtension(String filename) {
        if (filename == null || !filename.contains("."))
            return "jpg";
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }
}
