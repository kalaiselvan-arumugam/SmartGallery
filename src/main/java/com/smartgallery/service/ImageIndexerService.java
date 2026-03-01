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
import java.util.stream.Collectors;
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
    private final SettingsService settingsService;

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
            InMemoryVectorStore vectorStore,
            SettingsService settingsService) {
        this.imageRepository = imageRepository;
        this.reindexLogRepository = reindexLogRepository;
        this.thumbnailService = thumbnailService;
        this.onnxInferenceService = onnxInferenceService;
        this.vectorStore = vectorStore;
        this.settingsService = settingsService;
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

        boolean exifEnabled = settingsService.getSetting(SettingsService.KEY_EXIF_ENABLED)
                .map(Boolean::parseBoolean).orElse(true);

        try {
            // ── 1. Compute file hash ──────────────────────────────────────────
            String newHash = computeSha256(imagePath);
            long fileSize = Files.size(imagePath);
            LocalDateTime lastMod = LocalDateTime.ofInstant(
                    Files.getLastModifiedTime(imagePath).toInstant(), ZoneId.systemDefault());

            // Check if already indexed with the same hash
            ImageEntity existing = imageRepository.findByFilePath(imagePath.toString()).orElse(null);
            boolean needsEmbedding = existing == null || existing.getEmbedding() == null
                    || !newHash.equals(existing.getFileHash());
            boolean exifParsed = existing != null && existing.getExtraJson() != null
                    && existing.getExtraJson().contains("\"exif_parsed\":true");

            // If EXIF is disabled, we don't care if it's parsed as long as embedding is
            // good
            if (!needsEmbedding && (!exifEnabled || exifParsed)) {
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

            // ── 3.5 Compute EXIF and GPS Metadata ────────────────────────────
            Double latitude = null;
            Double longitude = null;
            java.util.Map<String, String> exifMap = new java.util.HashMap<>();

            if (exifEnabled) {
                try {
                    com.drew.metadata.Metadata metadata = com.drew.imaging.ImageMetadataReader
                            .readMetadata(imagePath.toFile());

                    // GPS
                    com.drew.metadata.exif.GpsDirectory gpsDir = metadata
                            .getFirstDirectoryOfType(com.drew.metadata.exif.GpsDirectory.class);
                    if (gpsDir != null && gpsDir.getGeoLocation() != null) {
                        latitude = gpsDir.getGeoLocation().getLatitude();
                        longitude = gpsDir.getGeoLocation().getLongitude();
                    }

                    // IFD0 (Make, Model)
                    com.drew.metadata.exif.ExifIFD0Directory ifd0Dir = metadata
                            .getFirstDirectoryOfType(com.drew.metadata.exif.ExifIFD0Directory.class);
                    if (ifd0Dir != null) {
                        if (ifd0Dir.containsTag(com.drew.metadata.exif.ExifIFD0Directory.TAG_MAKE))
                            exifMap.put("Camera maker",
                                    ifd0Dir.getString(com.drew.metadata.exif.ExifIFD0Directory.TAG_MAKE));
                        if (ifd0Dir.containsTag(com.drew.metadata.exif.ExifIFD0Directory.TAG_MODEL))
                            exifMap.put("Camera model",
                                    ifd0Dir.getString(com.drew.metadata.exif.ExifIFD0Directory.TAG_MODEL));
                    }

                    // SubIFD
                    com.drew.metadata.exif.ExifSubIFDDirectory subIfdDir = metadata
                            .getFirstDirectoryOfType(com.drew.metadata.exif.ExifSubIFDDirectory.class);
                    if (subIfdDir != null) {
                        if (subIfdDir.containsTag(com.drew.metadata.exif.ExifSubIFDDirectory.TAG_FNUMBER))
                            exifMap.put("F-stop", "f/"
                                    + subIfdDir.getString(com.drew.metadata.exif.ExifSubIFDDirectory.TAG_FNUMBER));
                        if (subIfdDir.containsTag(com.drew.metadata.exif.ExifSubIFDDirectory.TAG_EXPOSURE_TIME))
                            exifMap.put("Exposure time",
                                    subIfdDir.getString(com.drew.metadata.exif.ExifSubIFDDirectory.TAG_EXPOSURE_TIME)
                                            + " sec.");
                        if (subIfdDir.containsTag(com.drew.metadata.exif.ExifSubIFDDirectory.TAG_ISO_EQUIVALENT))
                            exifMap.put("ISO speed", "ISO-" + subIfdDir
                                    .getString(com.drew.metadata.exif.ExifSubIFDDirectory.TAG_ISO_EQUIVALENT));
                        if (subIfdDir.containsTag(com.drew.metadata.exif.ExifSubIFDDirectory.TAG_EXPOSURE_BIAS))
                            exifMap.put("Exposure bias",
                                    subIfdDir.getString(com.drew.metadata.exif.ExifSubIFDDirectory.TAG_EXPOSURE_BIAS)
                                            + " step");
                        if (subIfdDir.containsTag(com.drew.metadata.exif.ExifSubIFDDirectory.TAG_FOCAL_LENGTH))
                            exifMap.put("Focal length",
                                    subIfdDir.getString(com.drew.metadata.exif.ExifSubIFDDirectory.TAG_FOCAL_LENGTH)
                                            + " mm");
                        if (subIfdDir.containsTag(com.drew.metadata.exif.ExifSubIFDDirectory.TAG_MAX_APERTURE))
                            exifMap.put("Max aperture",
                                    subIfdDir.getString(com.drew.metadata.exif.ExifSubIFDDirectory.TAG_MAX_APERTURE));
                        if (subIfdDir.containsTag(com.drew.metadata.exif.ExifSubIFDDirectory.TAG_METERING_MODE))
                            exifMap.put("Metering mode", subIfdDir
                                    .getDescription(com.drew.metadata.exif.ExifSubIFDDirectory.TAG_METERING_MODE));
                        if (subIfdDir.containsTag(com.drew.metadata.exif.ExifSubIFDDirectory.TAG_FLASH))
                            exifMap.put("Flash mode",
                                    subIfdDir.getDescription(com.drew.metadata.exif.ExifSubIFDDirectory.TAG_FLASH));
                        if (subIfdDir.containsTag(
                                com.drew.metadata.exif.ExifSubIFDDirectory.TAG_35MM_FILM_EQUIV_FOCAL_LENGTH))
                            exifMap.put("35mm focal len", subIfdDir.getString(
                                    com.drew.metadata.exif.ExifSubIFDDirectory.TAG_35MM_FILM_EQUIV_FOCAL_LENGTH));
                    }
                } catch (Exception e) {
                    log.debug("Failed to extract EXIF for {}: {}", imagePath.getFileName(), e.getMessage());
                }
            }

            // Merge EXIF into extraJson
            String existingJson = (existing != null && existing.getExtraJson() != null
                    && !existing.getExtraJson().isBlank()) ? existing.getExtraJson() : "{}";
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.node.ObjectNode rootNode = (com.fasterxml.jackson.databind.node.ObjectNode) mapper
                        .readTree(existingJson);
                if (!exifMap.isEmpty()) {
                    rootNode.set("exif", mapper.valueToTree(exifMap));
                }
                rootNode.put("exif_parsed", true);
                existingJson = mapper.writeValueAsString(rootNode);
            } catch (Exception e) {
            }

            // ── 4. Compute CLIP embedding (if models are ready) ──────────────
            byte[] embeddingBytes = null;
            if (needsEmbedding && onnxInferenceService.isReady()) {
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
            entity.setLatitude(latitude);
            entity.setLongitude(longitude);
            entity.setExtraJson(existingJson);

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
     * Scans for images that have missing EXIF data and triggers a partial re-index.
     */
    @Async("indexingExecutor")
    public void scanMissingExif() {
        log.info("Starting background scan for missing EXIF metadata...");
        List<ImageEntity> all = imageRepository.findAll();
        List<ImageEntity> missing = all.stream()
                .filter(e -> e.getExtraJson() == null || !e.getExtraJson().contains("\"exif_parsed\":true"))
                .collect(Collectors.toList());

        if (missing.isEmpty()) {
            log.info("No images found with missing EXIF.");
            return;
        }

        log.info("Found {} images requiring EXIF parsing. Updating...", missing.size());
        for (ImageEntity entity : missing) {
            Path path = Paths.get(entity.getFilePath());
            if (Files.exists(path)) {
                indexSingleFile(path);
            }
        }
        log.info("Background EXIF update complete.");
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
