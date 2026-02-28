package com.smartgallery.controller;

import com.smartgallery.config.AppConfig;
import com.smartgallery.repository.ImageRepository;
import com.smartgallery.repository.WatchedFolderRepository;
import com.smartgallery.service.ImageIndexerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for indexing operations.
 *
 * Endpoints:
 * POST /api/index/reindex — trigger full re-index of all watched folders
 * GET /api/index/status — returns current indexing queue stats
 */
@RestController
@RequestMapping("/api/index")
public class IndexController {

    private final ImageIndexerService imageIndexerService;
    private final WatchedFolderRepository watchedFolderRepository;
    private final AppConfig appConfig;
    private final ImageRepository imageRepository;

    public IndexController(ImageIndexerService imageIndexerService,
            WatchedFolderRepository watchedFolderRepository,
            AppConfig appConfig,
            ImageRepository imageRepository) {
        this.imageIndexerService = imageIndexerService;
        this.watchedFolderRepository = watchedFolderRepository;
        this.appConfig = appConfig;
        this.imageRepository = imageRepository;
    }

    /**
     * Triggers a full re-index of all watched folders asynchronously.
     * POST /api/index/reindex
     */
    @PostMapping("/reindex")
    public ResponseEntity<Map<String, Object>> reindex() {
        java.util.Set<String> normalizedPaths = new java.util.HashSet<>();
        List<String> pathsToScan = new java.util.ArrayList<>();

        // Add active paths from DB
        watchedFolderRepository.findByActiveTrue().forEach(f -> {
            try {
                String norm = java.nio.file.Paths.get(f.getFolderPath()).toAbsolutePath().normalize().toString();
                if (normalizedPaths.add(norm)) {
                    pathsToScan.add(norm);
                }
            } catch (Exception ignored) {
            }
        });

        // Add default paths from config
        for (String dir : appConfig.getImageDirList()) {
            try {
                String norm = java.nio.file.Paths.get(dir).toAbsolutePath().normalize().toString();
                if (normalizedPaths.add(norm)) {
                    pathsToScan.add(norm);
                }
            } catch (Exception ignored) {
            }
        }

        imageIndexerService.indexAllPaths(pathsToScan);

        return ResponseEntity.accepted().body(Map.of(
                "status", "REINDEX_STARTED",
                "message", "Re-indexing started for " + pathsToScan.size() + " unique folder(s).",
                "folders", pathsToScan));
    }

    /**
     * Returns the current state of the indexing pipeline.
     * GET /api/index/status
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(Map.of(
                "totalIndexed", imageIndexerService.getTotalIndexed(),
                "favoritesCount", imageRepository.countByIsLovedTrue(),
                "processedCount", imageIndexerService.getProcessedCount(),
                "errorCount", imageIndexerService.getErrorCount(),
                "currentFile", imageIndexerService.getCurrentFile(),
                "lastRunTime", imageIndexerService.getLastRunTime()));
    }
}
