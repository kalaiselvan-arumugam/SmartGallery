package com.smartgallery.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PostConstruct;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages OCR language model downloads from GitHub tesseract-ocr/tessdata_fast
 * and notifies OcrService to hot-reload when a new model is activated.
 */
@Service
public class OcrModelService {

    private static final Logger log = LoggerFactory.getLogger(OcrModelService.class);

    // Tesseract's "tessdata_best" repo provides significantly higher accuracy,
    // especially for complex scripts like Tamil.
    private static final String TESSDATA_URL_BASE = "https://github.com/tesseract-ocr/tessdata_best/raw/main/";
    public static final String OCR_MODELS_DIR = "D:/SmartGallery/data/tessdata";

    // ── Model catalog ────────────────────────────────────────────────────────
    public static final List<OcrModelInfo> CATALOG = List.of(
            new OcrModelInfo("eng", "🇺🇸 English"),
            new OcrModelInfo("tam", "🇮🇳 Tamil"),
            new OcrModelInfo("tel", "🇮🇳 Telugu"),
            new OcrModelInfo("chi_sim", "🇨🇳 Chinese (Simplified)"),
            new OcrModelInfo("chi_tra", "🇨🇳 Chinese (Traditional)"),
            new OcrModelInfo("hin", "🇮🇳 Hindi / Devanagari"),
            new OcrModelInfo("ara", "🇸🇦 Arabic"),
            new OcrModelInfo("kor", "🇰🇷 Korean"),
            new OcrModelInfo("jpn", "🇯🇵 Japanese"),
            new OcrModelInfo("spa", "🇪🇸 Spanish (Latin)"),
            new OcrModelInfo("fra", "🇫🇷 French (Latin)"),
            new OcrModelInfo("deu", "🇩🇪 German (Latin)"),
            new OcrModelInfo("rus", "🇷🇺 Russian (Cyrillic)"),
            new OcrModelInfo("kan", "🇮🇳 Kannada"));

    public record OcrModelInfo(String key, String displayName) {
    }

    // ── Runtime state ─────────────────────────────────────────────────────────
    private final AtomicBoolean downloading = new AtomicBoolean(false);
    private final AtomicReference<String> currentDownloadKey = new AtomicReference<>();
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(Path.of(OCR_MODELS_DIR));
        } catch (IOException e) {
            log.error("Failed to create tessdata directory: {}", e.getMessage());
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Returns all models with their downloaded status. */
    public List<Map<String, Object>> getModelStatuses() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (OcrModelInfo info : CATALOG) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", info.key());
            m.put("displayName", info.displayName());
            m.put("downloaded", isDownloaded(info.key()));
            m.put("downloading", info.key().equals(currentDownloadKey.get()));
            result.add(m);
        }
        return result;
    }

    public boolean isDownloaded(String modelKey) {
        Path file = Path.of(OCR_MODELS_DIR, modelKey + ".traineddata");
        return Files.exists(file);
    }

    /** Creates a new SSE emitter for download progress. */
    public SseEmitter createSseEmitter() {
        SseEmitter emitter = new SseEmitter(300_000L); // 5 min timeout
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    /** Triggers async download of the traineddata file for the given key. */
    @Async("downloadExecutor")
    public void downloadModel(String modelKey) {
        if (!downloading.compareAndSet(false, true)) {
            publishEvent("ERROR", modelKey, "A download is already in progress.");
            return;
        }
        currentDownloadKey.set(modelKey);

        OcrModelInfo info = CATALOG.stream()
                .filter(m -> m.key().equals(modelKey))
                .findFirst().orElse(null);
        if (info == null) {
            publishEvent("ERROR", modelKey, "Unknown model key: " + modelKey);
            downloading.set(false);
            currentDownloadKey.set(null);
            return;
        }

        Path destFile = Path.of(OCR_MODELS_DIR, modelKey + ".traineddata");
        try {
            publishEvent("PROGRESS", modelKey, "Starting download for " + info.displayName());

            String url = TESSDATA_URL_BASE + modelKey + ".traineddata";
            downloadSingleFile(url, destFile, modelKey, "Traineddata model");

            publishEvent("DONE", modelKey, "Download complete for " + info.displayName());
            log.info("OCR model downloaded: {}", modelKey);

        } catch (Exception e) {
            log.error("Failed to download OCR model {}: {}", modelKey, e.getMessage());
            publishEvent("ERROR", modelKey, "Download failed: " + e.getMessage());
            try {
                Files.deleteIfExists(destFile);
            } catch (IOException ignored) {
            }
        } finally {
            downloading.set(false);
            currentDownloadKey.set(null);
        }
    }

    /** Deletes a downloaded model from disk. */
    public boolean deleteModel(String modelKey) {
        try {
            return Files.deleteIfExists(Path.of(OCR_MODELS_DIR, modelKey + ".traineddata"));
        } catch (IOException e) {
            log.error("Failed to delete model {}: {}", modelKey, e.getMessage());
            return false;
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private void downloadSingleFile(String url, Path dest, String modelKey, String label)
            throws IOException, InterruptedException {

        publishEvent("PROGRESS", modelKey, String.format("Downloading %s…", label));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            // GitHub might redirect or return 404. Our client follows redirects.
            throw new IOException("HTTP " + response.statusCode() + " for: " + url);
        }

        long totalBytes = response.headers()
                .firstValueAsLong("content-length")
                .orElse(-1L);

        try (InputStream in = response.body();
                OutputStream out = Files.newOutputStream(dest,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

            byte[] buf = new byte[65536];
            long downloaded = 0;
            int read;
            long lastReportedPct = -1;

            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
                downloaded += read;
                if (totalBytes > 0) {
                    long pct = downloaded * 100 / totalBytes;
                    if (pct != lastReportedPct && pct % 5 == 0) {
                        lastReportedPct = pct;
                        publishEvent("PROGRESS", modelKey,
                                String.format("%s: %d%% (%s / %s)",
                                        label, pct,
                                        humanBytes(downloaded), humanBytes(totalBytes)));
                    }
                }
            }
        }

        log.info("Downloaded {} → {} ({} bytes)", url, dest.getFileName(), Files.size(dest));
    }

    private void publishEvent(String type, String modelKey, String message) {
        Map<String, String> data = Map.of("type", type, "modelKey", modelKey, "message", message);
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().data(data));
                if ("DONE".equals(type) || "ERROR".equals(type)) {
                    emitter.complete();
                    dead.add(emitter);
                }
            } catch (Exception e) {
                dead.add(emitter);
            }
        }
        emitters.removeAll(dead);
    }

    private String humanBytes(long bytes) {
        if (bytes < 1024)
            return bytes + " B";
        if (bytes < 1024 * 1024)
            return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
