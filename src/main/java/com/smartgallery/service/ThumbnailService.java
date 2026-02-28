package com.smartgallery.service;

import com.smartgallery.config.AppConfig;
import net.coobird.thumbnailator.Thumbnails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Creates and manages image thumbnails using Thumbnailator.
 *
 * The app generates all thumbnails itself — no external service is used.
 * Thumbnails are square-cropped JPEG files stored in the configured thumb
 * directory.
 */
@Service
public class ThumbnailService {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailService.class);
    private static final String[] SUPPORTED_EXTENSIONS = { "jpg", "jpeg", "png", "gif", "bmp", "webp", "tiff", "tif" };

    private final AppConfig appConfig;

    public ThumbnailService(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(Paths.get(appConfig.getThumbDir()));
    }

    /**
     * Creates a square thumbnail for the given image and saves it to the thumb
     * directory.
     *
     * @param imagePath path to the source image
     * @return path to the generated thumbnail, or null on failure
     */
    public Path createThumbnail(Path imagePath) {
        if (!isSupportedImage(imagePath)) {
            return null;
        }

        try {
            Path thumbDir = Paths.get(appConfig.getThumbDir());
            Files.createDirectories(thumbDir);

            // Generate a stable filename based on the source path hash
            String thumbFilename = toThumbFilename(imagePath);
            Path thumbPath = thumbDir.resolve(thumbFilename);

            // Skip if already exists and is non-empty
            if (Files.exists(thumbPath) && Files.size(thumbPath) > 0) {
                return thumbPath;
            }

            int size = appConfig.getThumbSize();

            // Thumbnailator: Resize to max box constraints while retaining true aspect
            // ratio
            Thumbnails.of(imagePath.toFile())
                    .size(size, size)
                    .keepAspectRatio(true)
                    .outputFormat("jpg")
                    .outputQuality(0.85)
                    .toFile(thumbPath.toFile());

            return thumbPath;
        } catch (Exception e) {
            log.warn("Failed to create thumbnail for {}: {}", imagePath.getFileName(), e.getMessage());
            return null;
        }
    }

    /**
     * Deletes the thumbnail associated with an image path.
     */
    public void deleteThumbnail(Path imagePath) {
        try {
            Path thumbDir = Paths.get(appConfig.getThumbDir());
            Path thumbPath = thumbDir.resolve(toThumbFilename(imagePath));
            Files.deleteIfExists(thumbPath);
        } catch (Exception e) {
            log.warn("Failed to delete thumbnail for {}: {}", imagePath, e.getMessage());
        }
    }

    /**
     * Returns true if the file has a supported image extension.
     */
    public boolean isSupportedImage(Path path) {
        if (path == null)
            return false;
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String ext : SUPPORTED_EXTENSIONS) {
            if (name.endsWith("." + ext))
                return true;
        }
        return false;
    }

    /**
     * Generates a deterministic thumbnail filename from the source image absolute
     * path.
     * Format: {MD5-of-absolutePath}.jpg
     */
    private String toThumbFilename(Path imagePath) {
        try {
            String absPath = imagePath.toAbsolutePath().normalize().toString();
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(absPath.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest) + ".jpg";
        } catch (Exception e) {
            // Fallback: sanitize filename
            return imagePath.getFileName().toString().replaceAll("[^a-zA-Z0-9.-]", "_") + ".thumb.jpg";
        }
    }
}
