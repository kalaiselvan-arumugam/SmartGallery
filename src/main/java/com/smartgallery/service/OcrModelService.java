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
 * Manages OCR language model downloads from HuggingFace and
 * notifies OcrService to hot-reload when a new model is activated.
 */
@Service
public class OcrModelService {

    private static final Logger log = LoggerFactory.getLogger(OcrModelService.class);

    private static final String HF_BASE = "https://huggingface.co/deepghs/paddleocr/resolve/main";
    /** Shared detection model — language-agnostic */
    private static final String DET_FOLDER = "det/ch_PP-OCRv4_det";
    public static final String OCR_MODELS_DIR = "D:/SmartGallery/data/ocr-models";

    // ── Model catalog ────────────────────────────────────────────────────────
    public static final List<OcrModelInfo> CATALOG = List.of(
            new OcrModelInfo("en_v4", "🇺🇸 English (V4)", "en_PP-OCRv4_rec"),
            new OcrModelInfo("ta_v3", "🇮🇳 Tamil (V3)", "ta_PP-OCRv3_rec"),
            new OcrModelInfo("te_v3", "🇮🇳 Telugu (V3)", "te_PP-OCRv3_rec"),
            new OcrModelInfo("ch_v4", "🇨🇳 Chinese + English (V4)", "ch_PP-OCRv4_rec"),
            new OcrModelInfo("devanagari_v3", "🇮🇳 Hindi / Devanagari (V3)", "devanagari_PP-OCRv3_rec"),
            new OcrModelInfo("arabic_v3", "🇸🇦 Arabic (V3)", "arabic_PP-OCRv3_rec"),
            new OcrModelInfo("korean_v3", "🇰🇷 Korean (V3)", "korean_PP-OCRv3_rec"),
            new OcrModelInfo("japan_v3", "🇯🇵 Japanese (V3)", "japan_PP-OCRv3_rec"),
            new OcrModelInfo("latin_v3", "🌍 Latin / European (V3)", "latin_PP-OCRv3_rec"),
            new OcrModelInfo("cyrillic_v3", "🇷🇺 Russian / Cyrillic (V3)", "cyrillic_PP-OCRv3_rec"),
            new OcrModelInfo("ka_v3", "🇮🇳 Kannada (V3)", "ka_PP-OCRv3_rec"));

    public record OcrModelInfo(String key, String displayName, String folder) {
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
            log.error("Failed to create OCR models directory: {}", e.getMessage());
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
            m.put("folder", info.folder());
            m.put("downloaded", isDownloaded(info.key()));
            m.put("downloading", info.key().equals(currentDownloadKey.get()));
            result.add(m);
        }
        return result;
    }

    public boolean isDownloaded(String modelKey) {
        Path dir = Path.of(OCR_MODELS_DIR, modelKey);
        return Files.exists(dir.resolve("rec_model.onnx"))
                && Files.exists(dir.resolve("det_model.onnx"));
    }

    public Path getRecModelPath(String modelKey) {
        return Path.of(OCR_MODELS_DIR, modelKey, "rec_model.onnx");
    }

    public Path getDetModelPath(String modelKey) {
        return Path.of(OCR_MODELS_DIR, modelKey, "det_model.onnx");
    }

    public Path getDictPath(String modelKey) {
        Path p = Path.of(OCR_MODELS_DIR, modelKey, "dict.txt");
        return Files.exists(p) ? p : null;
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

    /** Triggers async download of the three model files for the given key. */
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

        Path destDir = Path.of(OCR_MODELS_DIR, modelKey);
        try {
            Files.createDirectories(destDir);
            publishEvent("PROGRESS", modelKey, "Starting download for " + info.displayName());

            // 1. Detection model (shared across languages)
            String detUrl = HF_BASE + "/" + DET_FOLDER + "/model.onnx";
            downloadSingleFile(detUrl, destDir.resolve("det_model.onnx"), modelKey, "Det model", 1, 3);

            // 2. Recognition model (language-specific)
            String recUrl = HF_BASE + "/rec/" + info.folder() + "/model.onnx";
            downloadSingleFile(recUrl, destDir.resolve("rec_model.onnx"), modelKey, "Rec model", 2, 3);

            // 3. Dictionary / character map (language-specific)
            String dictUrl = HF_BASE + "/rec/" + info.folder() + "/dict.txt";
            downloadSingleFile(dictUrl, destDir.resolve("dict.txt"), modelKey, "Dictionary", 3, 3);

            publishEvent("DONE", modelKey, "Download complete for " + info.displayName());
            log.info("OCR model downloaded: {}", modelKey);

        } catch (Exception e) {
            log.error("Failed to download OCR model {}: {}", modelKey, e.getMessage());
            publishEvent("ERROR", modelKey, "Download failed: " + e.getMessage());
            // Cleanup partial files
            try {
                deleteDirectory(destDir);
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
            deleteDirectory(Path.of(OCR_MODELS_DIR, modelKey));
            return true;
        } catch (IOException e) {
            log.error("Failed to delete model {}: {}", modelKey, e.getMessage());
            return false;
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private void downloadSingleFile(String url, Path dest, String modelKey,
            String label, int stepNum, int totalSteps)
            throws IOException, InterruptedException {

        publishEvent("PROGRESS", modelKey, String.format("[%d/%d] Downloading %s…", stepNum, totalSteps, label));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();

        // Stream-to-file so we can report progress
        HttpResponse<InputStream> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
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
                                String.format("[%d/%d] %s: %d%% (%s / %s)",
                                        stepNum, totalSteps, label, pct,
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

    private void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir))
            return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
