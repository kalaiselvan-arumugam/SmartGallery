package com.smartgallery.controller;

import com.smartgallery.dto.ModelStatusDto;
import com.smartgallery.service.ModelDownloaderService;
import com.smartgallery.service.ModelDownloaderService.ModelDownloadProgressEvent;
import com.smartgallery.service.ModelStatusService;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * REST controller for model download management and SSE progress streaming.
 *
 * Endpoints:
 * POST /api/models/download — start async model download
 * GET /api/models/status — check model file status
 * GET /api/models/progress — SSE stream for download progress
 * POST /api/models/verify — verify model files on disk
 */
@RestController
@RequestMapping("/api/models")
public class ModelController {

    private final ModelDownloaderService downloaderService;
    private final ModelStatusService statusService;

    // Active SSE clients subscribed to progress events
    private final List<SseEmitter> sseClients = new CopyOnWriteArrayList<>();

    public ModelController(ModelDownloaderService downloaderService,
            ModelStatusService statusService) {
        this.downloaderService = downloaderService;
        this.statusService = statusService;
    }

    /**
     * Starts model download asynchronously.
     * Body: { "repo": "optional-override-repo" }
     */
    @PostMapping("/download")
    public ResponseEntity<Map<String, String>> startDownload(
            @RequestBody(required = false) Map<String, String> body) {

        if (downloaderService.isDownloading()) {
            return ResponseEntity.status(409)
                    .body(Map.of("status", "ALREADY_RUNNING", "message", "Download is already in progress."));
        }

        String repo = (body != null) ? body.getOrDefault("repo", null) : null;
        downloaderService.startDownload(repo);

        return ResponseEntity.accepted()
                .body(Map.of("status", "STARTED", "message",
                        "Model download started. Subscribe to /api/models/progress for updates."));
    }

    /**
     * Returns the current model file status (disk check + inference readiness).
     */
    @GetMapping("/status")
    public ResponseEntity<ModelStatusDto> getStatus() {
        return ResponseEntity.ok(statusService.getStatus());
    }

    /**
     * SSE endpoint for streaming download progress to the UI.
     * The browser subscribes once; progress events are pushed as they arrive.
     */
    @GetMapping(value = "/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamProgress() {
        SseEmitter emitter = new SseEmitter(600_000L); // 10-minute timeout
        sseClients.add(emitter);

        emitter.onCompletion(() -> sseClients.remove(emitter));
        emitter.onTimeout(() -> sseClients.remove(emitter));
        emitter.onError(e -> sseClients.remove(emitter));

        // Send the current status immediately on connect
        try {
            ModelStatusDto currentStatus = statusService.getStatus();
            emitter.send(SseEmitter.event()
                    .name("status")
                    .data(Map.of(
                            "status", currentStatus.getStatus().name(),
                            "message", currentStatus.getMessage() != null ? currentStatus.getMessage() : "")));
        } catch (IOException e) {
            sseClients.remove(emitter);
        }

        return emitter;
    }

    /**
     * Verifies model files exist on disk and reports sizes.
     */
    @PostMapping("/verify")
    public ResponseEntity<ModelStatusDto> verifyModels() {
        return ResponseEntity.ok(statusService.getStatus());
    }

    /**
     * Receives ModelDownloadProgressEvents from ModelDownloaderService
     * and broadcasts them to all connected SSE clients.
     */
    @EventListener
    public void onDownloadProgress(ModelDownloadProgressEvent event) {
        if (sseClients.isEmpty())
            return;

        Map<String, Object> data = Map.of(
                "status", event.getStatus(),
                "message", event.getMessage() != null ? event.getMessage() : "",
                "file", event.getFile() != null ? event.getFile() : "",
                "bytesDownloaded", event.getBytesDownloaded(),
                "totalBytes", event.getTotalBytes(),
                "percentage", event.getPercentage());

        List<SseEmitter> dead = new CopyOnWriteArrayList<>();
        for (SseEmitter emitter : sseClients) {
            try {
                emitter.send(SseEmitter.event().name("progress").data(data));
                if ("READY".equals(event.getStatus()) || "ERROR".equals(event.getStatus())) {
                    emitter.complete();
                    dead.add(emitter);
                }
            } catch (IOException e) {
                dead.add(emitter);
            }
        }
        sseClients.removeAll(dead);
    }
}
