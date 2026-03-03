package com.smartgallery.controller;

import com.smartgallery.service.ImageIndexerService;
import com.smartgallery.service.OcrModelService;
import com.smartgallery.service.OcrService;
import com.smartgallery.service.SettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

/**
 * REST + SSE endpoints for OCR model management.
 */
@RestController
@RequestMapping("/api/ocr")
public class OcrController {

    @Autowired
    private OcrModelService ocrModelService;

    @Autowired
    private OcrService ocrService;

    @Autowired
    private SettingsService settingsService;

    @Autowired
    private ImageIndexerService imageIndexerService;

    // ── Model catalog ─────────────────────────────────────────────────────────

    /** Returns all models in the catalog with their download/active status. */
    @GetMapping("/models")
    public ResponseEntity<List<Map<String, Object>>> getModels() {
        List<Map<String, Object>> models = ocrModelService.getModelStatuses();
        // Annotate each with whether it's in the active list
        String activeList = settingsService.getSetting("ocr.active.models").orElse("");
        Set<String> activeKeys = new HashSet<>(Arrays.asList(activeList.split(",")));
        models.forEach(m -> m.put("active", activeKeys.contains(m.get("key"))));
        return ResponseEntity.ok(models);
    }

    // ── Download ──────────────────────────────────────────────────────────────

    /**
     * Starts an async download of the given model key.
     * Client should open /api/ocr/download/stream concurrently to receive SSE
     * events.
     */
    @PostMapping("/download/{modelKey}")
    public ResponseEntity<Map<String, String>> startDownload(@PathVariable String modelKey) {
        boolean exists = OcrModelService.CATALOG.stream()
                .anyMatch(m -> m.key().equals(modelKey));
        if (!exists) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Unknown model key: " + modelKey));
        }
        ocrModelService.downloadModel(modelKey);
        return ResponseEntity.ok(Map.of("status", "started", "modelKey", modelKey));
    }

    /** SSE stream for download progress events. */
    @GetMapping("/download/stream")
    public SseEmitter downloadStream() {
        return ocrModelService.createSseEmitter();
    }

    /** Deletes a downloaded model from disk. */
    @DeleteMapping("/models/{modelKey}")
    public ResponseEntity<Map<String, Object>> deleteModel(@PathVariable String modelKey) {
        boolean ok = ocrModelService.deleteModel(modelKey);
        // Also remove from active list if present
        String active = settingsService.getSetting("ocr.active.models").orElse("");
        List<String> activeList = new ArrayList<>(Arrays.asList(active.split(",")));
        activeList.remove(modelKey);
        settingsService.saveSetting("ocr.active.models", String.join(",", activeList));
        return ResponseEntity.ok(Map.of("success", ok));
    }

    // ── Active models ─────────────────────────────────────────────────────────

    /** Returns the list of currently active (selected) model keys. */
    @GetMapping("/active")
    public ResponseEntity<Map<String, Object>> getActiveModels() {
        String raw = settingsService.getSetting("ocr.active.models").orElse("");
        List<String> keys = raw.isBlank()
                ? List.of()
                : Arrays.asList(raw.split(","));
        return ResponseEntity.ok(Map.of("activeModels", keys));
    }

    /** Sets which downloaded models are active for OCR scanning. */
    @PostMapping("/active")
    public ResponseEntity<Map<String, Object>> setActiveModels(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> requested = (List<String>) body.getOrDefault("activeModels", List.of());

        // Validate: only allow downloaded models to be activated
        List<String> validated = requested.stream()
                .filter(ocrModelService::isDownloaded)
                .toList();

        settingsService.saveSetting("ocr.active.models", String.join(",", validated));
        return ResponseEntity.ok(Map.of("activeModels", validated));
    }

    // ── Hardware ──────────────────────────────────────────────────────────────

    @GetMapping("/hardware")
    public ResponseEntity<Map<String, String>> getHardware() {
        String hw = settingsService.getSetting("ocr.hardware").orElse("cpu");
        return ResponseEntity.ok(Map.of("hardware", hw));
    }

    @PostMapping("/hardware")
    public ResponseEntity<Map<String, String>> setHardware(@RequestBody Map<String, Object> body) {
        String hw = body.getOrDefault("hardware", "cpu").toString().toLowerCase();
        if (!Set.of("cpu", "gpu", "directml").contains(hw)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid hardware: " + hw));
        }
        settingsService.saveSetting("ocr.hardware", hw);
        return ResponseEntity.ok(Map.of("hardware", hw));
    }

    // ── Hot reload ────────────────────────────────────────────────────────────

    /**
     * Reloads the OCR engine with the currently saved settings.
     */
    @PostMapping("/reload")
    public ResponseEntity<Map<String, String>> reloadEngine() {
        try {
            ocrService.reloadEngine();
            return ResponseEntity.ok(Map.of("status", "reloaded",
                    "note", "Restart the application to fully apply model changes."));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Force re-OCR all images — clears stored text and re-runs OCR engine on every
     * image.
     * Must be called after changing the active OCR model (e.g. switching to Tamil).
     */
    @PostMapping("/reocr-all")
    public ResponseEntity<Map<String, String>> forceReOcrAll() {
        imageIndexerService.forceReOcrAll();
        return ResponseEntity.ok(Map.of("status", "started",
                "note", "Re-OCR started in background. Watch server logs for [OCR] progress."));
    }
}
