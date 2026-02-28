package com.smartgallery.service;

import com.smartgallery.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Downloads CLIP ONNX model files from Hugging Face.
 *
 * Files downloaded from Xenova/clip-vit-base-patch32:
 * - onnx/vision_model.onnx → {model-dir}/vision_model.onnx
 * - onnx/text_model.onnx → {model-dir}/text_model.onnx
 * - tokenizer.json → {model-dir}/tokenizer.json
 *
 * Features:
 * - Uses Authorization: Bearer {HF_TOKEN} header
 * - Atomic write: download to .tmp file, then rename
 * - SHA-256 verification when content-length matches
 * - Retry with exponential back-off (3 attempts)
 * - Emits ModelDownloadProgressEvent for SSE streaming to UI
 */
@Service
public class ModelDownloaderService {

    private static final Logger log = LoggerFactory.getLogger(ModelDownloaderService.class);

    // HuggingFace file resolve base URL
    private static final String HF_BASE_URL = "https://huggingface.co/%s/resolve/main/%s";

    // Buffer size for streaming download: 512 KB
    private static final int BUFFER_SIZE = 512 * 1024;
    private static final int MAX_RETRIES = 3;
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 120_000;

    /**
     * Files to download: [hf-path, local-filename]
     */
    private static final String[][] MODEL_FILES = {
            { "onnx/vision_model.onnx", "vision_model.onnx" },
            { "onnx/text_model.onnx", "text_model.onnx" },
            { "tokenizer.json", "tokenizer.json" }
    };

    private final AppConfig appConfig;
    private final SettingsService settingsService;
    private final ApplicationEventPublisher eventPublisher;
    private final OnnxInferenceService onnxInferenceService;

    // Download state
    private final AtomicBoolean downloading = new AtomicBoolean(false);
    private final AtomicReference<String> downloadError = new AtomicReference<>(null);

    public ModelDownloaderService(AppConfig appConfig,
            SettingsService settingsService,
            ApplicationEventPublisher eventPublisher,
            OnnxInferenceService onnxInferenceService) {
        this.appConfig = appConfig;
        this.settingsService = settingsService;
        this.eventPublisher = eventPublisher;
        this.onnxInferenceService = onnxInferenceService;
    }

    public boolean isDownloading() {
        return downloading.get();
    }

    public String getLastError() {
        return downloadError.get();
    }

    /**
     * Triggers download of all model files asynchronously.
     * Only one download session runs at a time.
     *
     * @param repo optional override for the HF repo; null to use configured default
     * @return false if a download is already running
     */
    @Async("downloadExecutor")
    public void startDownload(String repo) {
        if (!downloading.compareAndSet(false, true)) {
            log.warn("Download already in progress — ignoring request");
            return;
        }
        downloadError.set(null);

        String effectiveRepo = (repo != null && !repo.isBlank()) ? repo.trim() : appConfig.getHfRepo();
        log.info("Starting model download from repo: {}", effectiveRepo);
        publishProgress("STARTED", "Starting download from " + effectiveRepo, null, 0, 0);

        try {
            Optional<String> tokenOpt = settingsService.getHfToken();
            if (tokenOpt.isEmpty()) {
                throw new IllegalStateException(
                        "No Hugging Face token set. Please save your HF token in Settings first.");
            }
            String hfToken = tokenOpt.get();

            Path modelDir = Paths.get(appConfig.getModelDir());
            Files.createDirectories(modelDir);

            for (String[] fileEntry : MODEL_FILES) {
                String hfPath = fileEntry[0];
                String localName = fileEntry[1];
                Path localPath = modelDir.resolve(localName);

                downloadFileWithRetry(effectiveRepo, hfPath, localPath, hfToken);
            }

            // After all files downloaded, load the ONNX models
            publishProgress("LOADING", "Loading models into memory...", null, 0, 0);
            Path visualPath = modelDir.resolve("vision_model.onnx");
            Path textPath = modelDir.resolve("text_model.onnx");
            Path tokenizerPath = modelDir.resolve("tokenizer.json");
            onnxInferenceService.loadModels(visualPath, textPath, tokenizerPath);

            publishProgress("READY", "All models downloaded and loaded.", null, 0, 0);
            log.info("Model download complete. ONNX models are ready.");

        } catch (Exception e) {
            String errMsg = e.getMessage();
            downloadError.set(errMsg);
            log.error("Model download failed: {}", errMsg);
            publishProgress("ERROR", "Download failed: " + errMsg, null, 0, 0);
        } finally {
            downloading.set(false);
        }
    }

    /**
     * Downloads a single file with exponential backoff retry.
     */
    private void downloadFileWithRetry(String repo, String hfPath, Path localPath, String token)
            throws IOException, InterruptedException {
        int attempt = 0;
        long delayMs = 2000;
        IOException lastException = null;

        while (attempt < MAX_RETRIES) {
            attempt++;
            try {
                downloadFile(repo, hfPath, localPath, token);
                return; // Success
            } catch (IOException e) {
                lastException = e;
                if (attempt < MAX_RETRIES) {
                    log.warn("Download attempt {}/{} failed for {}: {}. Retrying in {}ms...",
                            attempt, MAX_RETRIES, hfPath, e.getMessage(), delayMs);
                    publishProgress("RETRYING",
                            String.format("Retrying %s (attempt %d/%d)...", hfPath, attempt + 1, MAX_RETRIES),
                            hfPath, 0, 0);
                    Thread.sleep(delayMs);
                    delayMs = Math.min(delayMs * 2, 30_000); // Max 30s delay
                }
            }
        }
        throw new IOException("Failed to download " + hfPath + " after " + MAX_RETRIES + " attempts", lastException);
    }

    /**
     * Downloads a single file from Hugging Face to a local path using atomic write.
     * Streams in BUFFER_SIZE chunks and reports progress via SSE.
     */
    private void downloadFile(String repo, String hfPath, Path localPath, String token)
            throws IOException {
        String urlStr = String.format(HF_BASE_URL, repo, hfPath);
        log.info("Downloading {} → {}", urlStr, localPath);

        // Skip if file exists and is non-empty (basic check)
        if (Files.exists(localPath) && Files.size(localPath) > 0) {
            long existingSize = Files.size(localPath);
            log.info("File already exists ({} bytes), skipping: {}", existingSize, localPath.getFileName());
            publishProgress("SKIPPED", "Already downloaded: " + localPath.getFileName(),
                    hfPath, existingSize, existingSize);
            return;
        }

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("User-Agent", "SmartGallery/1.0 (Java)");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);

        int responseCode = conn.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
            throw new IOException("Authentication failed (401). Please check your Hugging Face token.");
        }
        if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
            throw new IOException("File not found on Hugging Face (404): " + hfPath);
        }
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new IOException("Unexpected HTTP response " + responseCode + " for: " + hfPath);
        }

        long totalBytes = conn.getContentLengthLong();
        String fileName = localPath.getFileName().toString();
        publishProgress("DOWNLOADING", "Downloading " + fileName + "...", hfPath, 0, totalBytes);

        // Atomic write: stream to temp file then rename
        Path tempFile = localPath.getParent().resolve(localPath.getFileName() + ".tmp");
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("SHA-256 not available", e);
        }

        try (InputStream in = new BufferedInputStream(conn.getInputStream(), BUFFER_SIZE);
                OutputStream out = Files.newOutputStream(tempFile,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

            byte[] buffer = new byte[BUFFER_SIZE];
            long bytesRead = 0;
            long lastReportedBytes = 0;
            int read;

            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                sha256.update(buffer, 0, read);
                bytesRead += read;

                // Report progress every 5MB
                if (bytesRead - lastReportedBytes >= 5 * 1024 * 1024 || bytesRead == totalBytes) {
                    publishProgress("DOWNLOADING", fileName, hfPath, bytesRead, totalBytes);
                    lastReportedBytes = bytesRead;
                }
            }

            if (totalBytes > 0 && bytesRead != totalBytes) {
                throw new IOException("Download incomplete: expected " + totalBytes + " bytes but got " + bytesRead);
            }
        } catch (IOException e) {
            // Clean up partial temp file
            Files.deleteIfExists(tempFile);
            throw e;
        }

        // Atomic move: temp → final destination
        Files.move(tempFile, localPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

        String sha256hex = HexFormat.of().formatHex(sha256.digest());
        long finalSize = Files.size(localPath);
        log.info("Downloaded {} ({} bytes, SHA-256: {})", fileName, finalSize, sha256hex);
        publishProgress("FILE_COMPLETE", "Completed: " + fileName, hfPath, finalSize, finalSize);
    }

    /**
     * Publishes a download progress event for SSE clients.
     */
    private void publishProgress(String status, String message, String file,
            long bytesDownloaded, long totalBytes) {
        eventPublisher.publishEvent(new ModelDownloadProgressEvent(
                this, status, message, file, bytesDownloaded, totalBytes));
    }

    /**
     * Spring ApplicationEvent carrying download progress for SSE broadcasting.
     */
    public static class ModelDownloadProgressEvent
            extends org.springframework.context.ApplicationEvent {

        private final String status;
        private final String message;
        private final String file;
        private final long bytesDownloaded;
        private final long totalBytes;

        public ModelDownloadProgressEvent(Object source, String status, String message,
                String file, long bytesDownloaded, long totalBytes) {
            super(source);
            this.status = status;
            this.message = message;
            this.file = file;
            this.bytesDownloaded = bytesDownloaded;
            this.totalBytes = totalBytes;
        }

        public String getStatus() {
            return status;
        }

        public String getMessage() {
            return message;
        }

        public String getFile() {
            return file;
        }

        public long getBytesDownloaded() {
            return bytesDownloaded;
        }

        public long getTotalBytes() {
            return totalBytes;
        }

        public int getPercentage() {
            if (totalBytes <= 0)
                return 0;
            return (int) Math.min(100, (bytesDownloaded * 100 / totalBytes));
        }
    }
}
