package com.smartgallery.service;

import com.smartgallery.entity.ImageEntity;
import com.smartgallery.entity.ReindexLogEntity;
import com.smartgallery.repository.ImageRepository;
import com.smartgallery.repository.ReindexLogRepository;
import com.smartgallery.util.EmbeddingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Indexes image files: creates thumbnails, computes CLIP embeddings, and stores
 * in H2.
 *
 * For each image:
 * 1. Compute SHA-256 hash — skip if unchanged
 * 2. Create thumbnail via ThumbnailService
 * 3. Compute CLIP embedding via OnnxInferenceService
 * 4. L2-normalize and store embedding BLOB in ImageEntity
 * 5. Log result in reindex_log
 *
 * Indexing runs on the "indexingExecutor" thread pool.
 * The in-memory vector store is updated in sync after each successful
 * embedding.
 */
@Service
public class ImageIndexerService {

    private static final Logger log = LoggerFactory.getLogger(ImageIndexerService.class);

    private final ImageRepository imageRepository;
    private final ReindexLogRepository reindexLogRepository;
    private final ThumbnailService thumbnailService;
    private final OnnxInferenceService onnxInferenceService;
    private final InMemoryVectorStore vectorStore;

    // Indexing state
    private final AtomicInteger queuedCount = new AtomicInteger(0);
    private final AtomicInteger processedCount = new AtomicInteger(0);
    private final AtomicInteger errorCount = new AtomicInteger(0);
    private final AtomicLong lastRunTime = new AtomicLong(0);
    private final AtomicReference<String> currentFile = new AtomicReference<>("");

    public ImageIndexerService(ImageRepository imageRepository,
            ReindexLogRepository reindexLogRepository,
            ThumbnailService thumbnailService,
            OnnxInferenceService onnxInferenceService,
            InMemoryVectorStore vectorStore) {
        this.imageRepository = imageRepository;
        this.reindexLogRepository = reindexLogRepository;
        this.thumbnailService = thumbnailService;
        this.onnxInferenceService = onnxInferenceService;
        this.vectorStore = vectorStore;
    }

    /**
     * On startup: loads all existing embeddings from DB into the vector store.
     */
    @PostConstruct
    public void loadExistingEmbeddings() {
        try {
            List<Object[]> data = imageRepository.findAllEmbeddingData();
            vectorStore.loadAll(data);
            log.info("Loaded {} embeddings into vector store", data.size());
        } catch (Exception e) {
            log.error("Failed to load embeddings from DB at startup: {}", e.getMessage());
        }
    }

    /**
     * Indexes all image files under all watched paths.
     * Runs asynchronously on the indexing executor.
     *
     * @param watchedPaths list of directories to scan
     */
    @Async("indexingExecutor")
    public void indexAllPaths(List<String> watchedPaths) {
        processedCount.set(0);
        errorCount.set(0);
        lastRunTime.set(System.currentTimeMillis());
        log.info("Starting full reindex of {} folders...", watchedPaths.size());

        for (String folder : watchedPaths) {
            Path dir = Paths.get(folder).toAbsolutePath().normalize();
            if (!Files.isDirectory(dir)) {
                log.warn("Skipping non-existent folder: {}", dir);
                continue;
            }
            try {
                Files.walk(dir)
                        .filter(Files::isRegularFile)
                        .filter(thumbnailService::isSupportedImage)
                        .forEach(this::indexSingleFile);
            } catch (IOException e) {
                log.error("Error walking directory {}: {}", dir, e.getMessage());
            }
        }

        // After full scan, reload the vector store from DB for consistency
        List<Object[]> data = imageRepository.findAllEmbeddingData();
        vectorStore.loadAll(data);

        log.info("Reindex complete. Processed: {}, Errors: {}", processedCount.get(), errorCount.get());
    }

    /**
     * Indexes a single image file. Skips if the file is unchanged (same hash).
     * If ONNX models are not loaded, creates only thumbnail and stores basic
     * metadata.
     *
     * @param imagePath path to the image file
     */
    @Transactional
    public void indexSingleFile(Path imagePath) {
        imagePath = imagePath.toAbsolutePath().normalize();
        currentFile.set(imagePath.getFileName().toString());
        queuedCount.incrementAndGet();

        long startMs = System.currentTimeMillis();
        ReindexLogEntity logEntry = new ReindexLogEntity();
        logEntry.setFilePath(imagePath.toString());
        logEntry.setProcessedAt(LocalDateTime.now());

        try {
            // ── 1. Compute file hash ──────────────────────────────────────────
            String newHash = computeSha256(imagePath);
            long fileSize = Files.size(imagePath);
            LocalDateTime lastMod = LocalDateTime.ofInstant(
                    Files.getLastModifiedTime(imagePath).toInstant(), ZoneId.systemDefault());

            // Check if already indexed with the same hash
            ImageEntity existing = imageRepository.findByFilePath(imagePath.toString()).orElse(null);
            if (existing != null && newHash.equals(existing.getFileHash()) && existing.getEmbedding() != null) {
                logEntry.setStatus("SKIPPED");
                logEntry.setImageId(existing.getId());
                logEntry.setDurationMs(System.currentTimeMillis() - startMs);
                reindexLogRepository.save(logEntry);
                processedCount.incrementAndGet();
                return;
            }

            // ── 2. Create thumbnail ───────────────────────────────────────────
            Path thumbPath = thumbnailService.createThumbnail(imagePath);

            // ── 3. Read image dimensions ─────────────────────────────────────
            int width = 0, height = 0;
            try {
                BufferedImage img = ImageIO.read(imagePath.toFile());
                if (img != null) {
                    width = img.getWidth();
                    height = img.getHeight();
                }
            } catch (Exception ignored) {
            }

            // ── 4. Compute CLIP embedding (if models are ready) ──────────────
            byte[] embeddingBytes = null;
            if (onnxInferenceService.isReady()) {
                float[] embedding = onnxInferenceService.embedImage(imagePath);
                if (embedding != null) {
                    embeddingBytes = EmbeddingUtils.toBytes(embedding);
                }
            }

            // ── 5. Save to DB ─────────────────────────────────────────────────
            ImageEntity entity = (existing != null) ? existing : new ImageEntity();
            entity.setFilePath(imagePath.toString());
            entity.setThumbPath(thumbPath != null ? thumbPath.toString() : null);
            entity.setWidth(width > 0 ? width : null);
            entity.setHeight(height > 0 ? height : null);
            entity.setFileSize(fileSize);
            entity.setFileHash(newHash);
            entity.setLastModified(lastMod);
            entity.setIndexedAt(LocalDateTime.now());
            entity.setStatus("INDEXED");
            if (embeddingBytes != null) {
                entity.setEmbedding(embeddingBytes);
            }

            ImageEntity saved = imageRepository.save(entity);

            // ── 6. Update in-memory vector store ─────────────────────────────
            if (embeddingBytes != null) {
                vectorStore.addOrUpdate(saved.getId(), EmbeddingUtils.fromBytes(embeddingBytes));
            }

            logEntry.setStatus("SUCCESS");
            logEntry.setImageId(saved.getId());
            processedCount.incrementAndGet();

        } catch (Exception e) {
            log.error("Failed to index {}: {}", imagePath.getFileName(), e.getMessage());
            logEntry.setStatus("ERROR");
            logEntry.setErrorMessage(e.getMessage());
            errorCount.incrementAndGet();
        } finally {
            logEntry.setDurationMs(System.currentTimeMillis() - startMs);
            reindexLogRepository.save(logEntry);
            currentFile.set("");
        }
    }

    /**
     * Removes a deleted image from the DB and vector store.
     */
    @Transactional
    public void removeDeletedImage(Path imagePath) {
        String pathStr = imagePath.toAbsolutePath().normalize().toString();
        imageRepository.findByFilePath(pathStr).ifPresent(entity -> {
            vectorStore.remove(entity.getId());
            thumbnailService.deleteThumbnail(imagePath);
            imageRepository.delete(entity);
            log.info("Removed deleted image from index: {}", imagePath.getFileName());
        });
    }

    /**
     * Computes the SHA-256 digest of a file's contents.
     */
    private String computeSha256(Path path) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream is = Files.newInputStream(path)) {
                byte[] buf = new byte[65536];
                int read;
                while ((read = is.read(buf)) != -1) {
                    md.update(buf, 0, read);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new IOException("Failed to compute SHA-256 for " + path, e);
        }
    }

    // ───────────── Status accessors ─────────────

    public int getQueuedCount() {
        return queuedCount.get();
    }

    public int getProcessedCount() {
        return processedCount.get();
    }

    public int getErrorCount() {
        return errorCount.get();
    }

    public long getLastRunTime() {
        return lastRunTime.get();
    }

    public String getCurrentFile() {
        return currentFile.get();
    }

    public int getTotalIndexed() {
        return (int) imageRepository.countIndexed();
    }
}
