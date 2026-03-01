package com.smartgallery.controller;

import com.smartgallery.entity.WatchedFolderEntity;
import com.smartgallery.repository.ImageRepository;
import com.smartgallery.repository.WatchedFolderRepository;
import com.smartgallery.service.FileSystemWatcherService;
import com.smartgallery.service.ImageIndexerService;
import com.smartgallery.service.SettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST controller for settings management and folder management.
 *
 * Endpoints:
 * GET /api/settings — get all public settings
 * POST /api/settings/token — save HF token
 * DELETE /api/settings/token — clear HF token
 * GET /api/settings/token/status — check if token is set
 * GET /api/settings/folders — list watched folders
 * POST /api/settings/folders — add a watched folder
 * DELETE /api/settings/folders/{id} — remove a watched folder
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settingsService;
    private final WatchedFolderRepository watchedFolderRepository;
    private final FileSystemWatcherService watcherService;
    private final ImageRepository imageRepository;
    private final ImageIndexerService imageIndexerService;

    public SettingsController(SettingsService settingsService,
            WatchedFolderRepository watchedFolderRepository,
            FileSystemWatcherService watcherService,
            ImageRepository imageRepository,
            ImageIndexerService imageIndexerService) {
        this.settingsService = settingsService;
        this.watchedFolderRepository = watchedFolderRepository;
        this.watcherService = watcherService;
        this.imageRepository = imageRepository;
        this.imageIndexerService = imageIndexerService;
    }

    /**
     * Returns public settings (token is masked, never returned in plain text).
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getSettings() {
        return ResponseEntity.ok(Map.of(
                "hasToken", settingsService.hasHfToken(),
                "tokenMasked", settingsService.hasHfToken() ? "hf_****" : ""));
    }

    /**
     * Saves the Hugging Face API token (encrypted).
     * POST /api/settings/token
     * Body: { "token": "hf_xxxx..." }
     */
    @PostMapping("/token")
    public ResponseEntity<Map<String, String>> saveToken(@RequestBody Map<String, String> body) {
        String token = body.get("token");
        if (token == null || token.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Token cannot be empty"));
        }
        try {
            settingsService.saveHfToken(token);
            return ResponseEntity.ok(Map.of("status", "saved", "message", "Token saved and encrypted."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Clears the stored Hugging Face token.
     */
    @DeleteMapping("/token")
    public ResponseEntity<Map<String, String>> clearToken() {
        settingsService.clearHfToken();
        return ResponseEntity.ok(Map.of("status", "cleared", "message", "Token removed."));
    }

    /**
     * Returns whether a token is currently stored (no plain text exposed).
     */
    @GetMapping("/token/status")
    public ResponseEntity<Map<String, Object>> getTokenStatus() {
        return ResponseEntity.ok(Map.of("hasToken", settingsService.hasHfToken()));
    }

    /**
     * Returns advanced settings (EXIF toggle, Search threshold).
     */
    @GetMapping("/advanced")
    public ResponseEntity<Map<String, Object>> getAdvancedSettings() {
        boolean exifEnabled = settingsService.getSetting(SettingsService.KEY_EXIF_ENABLED)
                .map(Boolean::parseBoolean).orElse(true);
        boolean exifVisible = settingsService.getSetting(SettingsService.KEY_EXIF_VISIBLE)
                .map(Boolean::parseBoolean).orElse(true);
        boolean mapVisible = settingsService.getSetting(SettingsService.KEY_MAP_VISIBLE)
                .map(Boolean::parseBoolean).orElse(true);
        boolean autoIndexing = settingsService.getSetting(SettingsService.KEY_AUTO_INDEXING_ENABLED)
                .map(Boolean::parseBoolean).orElse(true);
        float threshold = settingsService.getSetting(SettingsService.KEY_SEARCH_THRESHOLD)
                .map(Float::parseFloat).orElse(0.24f);

        return ResponseEntity.ok(Map.of(
                "exifEnabled", exifEnabled,
                "exifVisible", exifVisible,
                "mapVisible", mapVisible,
                "autoIndexingEnabled", autoIndexing,
                "searchThreshold", threshold));
    }

    /**
     * Saves advanced settings.
     */
    @PostMapping("/advanced")
    public ResponseEntity<Map<String, Object>> saveAdvancedSettings(@RequestBody Map<String, Object> body) {
        if (body.containsKey("exifEnabled")) {
            boolean wasEnabled = settingsService.getSetting(SettingsService.KEY_EXIF_ENABLED)
                    .map(Boolean::parseBoolean).orElse(true);
            boolean nowEnabled = Boolean.parseBoolean(body.get("exifEnabled").toString());
            settingsService.saveSetting(SettingsService.KEY_EXIF_ENABLED, String.valueOf(nowEnabled));

            // If toggled ON, trigger background scan for missing EXIF
            if (!wasEnabled && nowEnabled) {
                imageIndexerService.scanMissingExif();
            }
        }
        if (body.containsKey("exifVisible")) {
            settingsService.saveSetting(SettingsService.KEY_EXIF_VISIBLE, body.get("exifVisible").toString());
        }
        if (body.containsKey("mapVisible")) {
            settingsService.saveSetting(SettingsService.KEY_MAP_VISIBLE, body.get("mapVisible").toString());
        }
        if (body.containsKey("autoIndexingEnabled")) {
            settingsService.saveSetting(SettingsService.KEY_AUTO_INDEXING_ENABLED,
                    body.get("autoIndexingEnabled").toString());
        }
        if (body.containsKey("searchThreshold")) {
            float threshold = Float.parseFloat(body.get("searchThreshold").toString());
            // Clamp between 0.0 and 1.0
            threshold = Math.max(0.0f, Math.min(1.0f, threshold));
            settingsService.saveSetting(SettingsService.KEY_SEARCH_THRESHOLD, String.valueOf(threshold));
        }
        return ResponseEntity.ok(Map.of("status", "saved"));
    }

    /**
     * Lists all watched folders.
     */
    @GetMapping("/folders")
    public ResponseEntity<List<Map<String, Object>>> getFolders() {
        List<Map<String, Object>> result = watchedFolderRepository.findAll()
                .stream()
                .map(f -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", f.getId());
                    m.put("folderPath", f.getFolderPath());
                    m.put("active", f.isActive());
                    m.put("addedAt", f.getAddedAt() != null ? f.getAddedAt().toString() : "");
                    m.put("imageCount", imageRepository.countByFilePathStartingWith(f.getFolderPath()));
                    return m;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /**
     * Adds a new watched folder.
     * POST /api/settings/folders
     * Body: { "folderPath": "C:/Users/me/Pictures" }
     */
    @PostMapping("/folders")
    public ResponseEntity<Map<String, Object>> addFolder(@RequestBody Map<String, String> body) {
        String path = body.get("folderPath");
        if (path == null || path.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "folderPath is required"));
        }
        try {
            watcherService.addWatchFolder(path);
            WatchedFolderEntity entity = watchedFolderRepository.findByFolderPath(
                    java.nio.file.Paths.get(path).toAbsolutePath().normalize().toString()).orElseThrow();
            return ResponseEntity.ok(Map.of(
                    "status", "added",
                    "id", entity.getId(),
                    "folderPath", entity.getFolderPath()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to add folder: " + e.getMessage()));
        }
    }

    /**
     * Removes a watched folder by ID.
     * DELETE /api/settings/folders/{id}
     */
    @DeleteMapping("/folders/{id}")
    public ResponseEntity<Map<String, String>> removeFolder(@PathVariable Long id) {
        return watchedFolderRepository.findById(id).map(entity -> {
            watcherService.removeWatchFolder(entity.getFolderPath());
            return ResponseEntity.ok(Map.of(
                    "status", "removed",
                    "folderPath", entity.getFolderPath()));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
