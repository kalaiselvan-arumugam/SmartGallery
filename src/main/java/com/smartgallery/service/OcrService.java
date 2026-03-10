package com.smartgallery.service;

import net.sourceforge.tess4j.Tesseract;
import com.recognition.software.jdeskew.ImageDeskew;
import net.sourceforge.tess4j.util.ImageHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * OCR service backed by Tess4J (Tesseract OCR Wrapper).
 */
@Service
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);

    private Tesseract tesseract;

    @Autowired
    @Lazy
    private OcrModelService ocrModelService;

    @Autowired
    private SettingsService settingsService;

    @PostConstruct
    public void init() {
        // Enforce UTF-8 for JNA so Tesseract C++ strings aren't mangled on Windows
        System.setProperty("jna.encoding", "UTF8");
        tesseract = new Tesseract();
        // Point to our persistent tessdata folder
        tesseract.setDatapath(OcrModelService.OCR_MODELS_DIR);

        String activeModelKey = resolveActiveModelKey();
        if (activeModelKey != null) {
            tesseract.setLanguage(activeModelKey);
            log.info("OCR engine ready. Using Tesseract models: '{}'.", activeModelKey);
        } else {
            // Default to 'eng' in Tesseract; requires eng.traineddata to be present or it
            // will error,
            // but we advise to download eng model via UI.
            tesseract.setLanguage("eng");
            log.info("OCR engine ready. Using default Tesseract model 'eng'.");
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void reloadEngine() {
        String activeModelKey = resolveActiveModelKey();
        if (activeModelKey != null) {
            tesseract.setLanguage(activeModelKey);
            log.info("Reloaded OCR engine languages: '{}'", activeModelKey);
        } else {
            tesseract.setLanguage("eng");
            log.info("Reloaded OCR engine languages: 'eng'");
        }
    }

    public synchronized String extractText(File imageFile) {
        if (tesseract == null)
            return null;

        boolean ocrEnabled = settingsService.getSetting(SettingsService.KEY_OCR_INDEXING_ENABLED)
                .map(Boolean::parseBoolean).orElse(true);
        if (!ocrEnabled)
            return null;

        if (imageFile == null || !imageFile.exists()
                || imageFile.length() == 0
                || imageFile.length() > 25 * 1024 * 1024) {
            return null;
        }

        try {
            BufferedImage image = ImageIO.read(imageFile);
            if (image == null)
                return null;

            // PREPROCESSING: Enhance image for Tesseract's strict requirements
            // 1. Convert to grayscale and improve contrast for better text recognition
            image = ImageHelper.convertImageToGrayscale(image);

            // 2. Minimum size recommendation for Tesseract is 300 DPI or ~20px text height.
            // A simple approach is upscaling low resolution images.
            if (image.getWidth() < 1000 || image.getHeight() < 1000) {
                image = ImageHelper.getScaledInstance(image, image.getWidth() * 2, image.getHeight() * 2);
            }

            // 3. Deskew the image (correct rotation/tilt)
            ImageDeskew id = new ImageDeskew(image);
            double imageSkewAngle = id.getSkewAngle();
            // the skew angle is in radians or degrees? getSkewAngle returns degrees! Wait,
            // it returns double, where positive is counterclockwise.
            // Documentation for getSkewAngle states it returns angle in degrees.
            if ((imageSkewAngle > 0.05d || imageSkewAngle < -0.05d)) {
                log.debug("Deskewing image by {} degrees", -imageSkewAngle);
                image = ImageHelper.rotateImage(image, -imageSkewAngle);
            }

            String result = tesseract.doOCR(image);
            if (result == null || result.isBlank()) {
                return null;
            }
            
            result = result.trim();
            if (!isValidText(result)) {
                log.info("OCR text rejected as noise for {}: {}", imageFile.getName(), result);
                return null;
            }

            log.info("Extracted OCR text from {}: {}", imageFile.getName(), result);
            return result;
        } catch (Exception e) {
            log.error("OCR error for {}: {}", imageFile.getName(), e.getMessage());
            return null;
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /**
     * Tesseract allows multiple languages combined with a plus sign, e.g.
     * "eng+tam".
     */
    private String resolveActiveModelKey() {
        String activeRaw = settingsService.getSetting("ocr.active.models").orElse("eng,tam");
        if (activeRaw.isBlank())
            return null;

        String languages = Arrays.stream(activeRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .filter(k -> ocrModelService.isDownloaded(k))
                .collect(Collectors.joining("+"));

        return languages.isEmpty() ? null : languages;
    }

    /**
     * Heuristic to determine if the OCR result is genuine text or just noisy symbols.
     * Rejects extremely short strings or strings that consist overwhelmingly of punctuation/symbols.
     */
    private boolean isValidText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        long nonWhitespaceCount = 0;
        long alphanumericCount = 0;

        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            if (!Character.isWhitespace(codePoint)) {
                nonWhitespaceCount++;
                if (Character.isAlphabetic(codePoint) || Character.isDigit(codePoint)) {
                    alphanumericCount++;
                }
            }
            i += Character.charCount(codePoint);
        }

        // If it's less than 3 alphanumeric characters total, it's considered noise (e.g., "- 1 .")
        if (alphanumericCount < 3) {
            return false;
        }

        // If less than 40% of the non-whitespace characters are alphanumeric, reject it (e.g., "/..///|...A")
        if (nonWhitespaceCount > 0 && ((double) alphanumericCount / nonWhitespaceCount) < 0.4) {
            return false;
        }

        return true;
    }
}
