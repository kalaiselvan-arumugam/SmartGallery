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
import java.util.List;
import java.util.stream.Collectors;
import net.sourceforge.tess4j.Word;
import net.sourceforge.tess4j.ITessAPI;

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

            // 3. Deskew the image (correct rotation/tilt, angle is in degrees)
            ImageDeskew id = new ImageDeskew(image);
            double imageSkewAngle = id.getSkewAngle();
            if (imageSkewAngle > 0.05d || imageSkewAngle < -0.05d) {
                log.debug("Deskewing image by {} degrees", -imageSkewAngle);
                image = ImageHelper.rotateImage(image, -imageSkewAngle);
            }

            // Extract words and their individual confidence scores
            List<Word> words = tesseract.getWords(image, ITessAPI.TessPageIteratorLevel.RIL_WORD);
            if (words == null || words.isEmpty()) {
                return null;
            }

            StringBuilder sb = new StringBuilder();
            double totalConfidenceWeighted = 0;
            int totalWeight = 0;

            for (Word word : words) {
                String text = word.getText();
                if (text == null || text.trim().isEmpty()) continue;

                sb.append(text).append(" ");
                int weight = text.length();
                totalConfidenceWeighted += (word.getConfidence() * weight);
                totalWeight += weight;
                log.debug("  Word: '{}' | Confidence: {}", text.trim(), word.getConfidence());
            }

            if (totalWeight == 0) return null;

            double avgConfidence = totalConfidenceWeighted / totalWeight;
            String result = sb.toString().trim();

            // Tess4J word-level confidence for real text reliably scores 60%+.
            // Hallucinated noise on textures, walls, and natural scenes typically scores < 50%.
            // This threshold is the primary noise gate — heuristics below only catch edge cases.
            final double CONFIDENCE_THRESHOLD = 60.0;
            if (avgConfidence < CONFIDENCE_THRESHOLD) {
                log.info("OCR text rejected due to low confidence ({}%) for {}: {}",
                        String.format("%.1f", avgConfidence), imageFile.getName(), result);
                return null;
            }

            if (!isValidText(result)) {
                log.info("OCR text rejected as noise by heuristics for {}: {}", imageFile.getName(), result);
                return null;
            }

            log.info("Extracted OCR text (Confidence: {}%) from {}: {}",
                    String.format("%.1f", avgConfidence), imageFile.getName(), result);
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
     * Lightweight safety net — the primary noise filter is now the Tesseract confidence
     * score gate above. This method only catches extreme structural garbage that slips
     * through (completely blank output, zero letter content, or 5+ consecutive identical
     * letters like "vvvvv" or Tamil hallucination strings like "வவவவவ").
     *
     * Previous aggressive heuristics (symbol ratio, short-word ratio, density, isolation)
     * were removed because they caused false rejections on valid content such as:
     *   - URLs (high symbol count from '/', ':', '.')
     *   - Tamil text (many single-character words)
     *   - Mixed-language screenshots
     */
    private boolean isValidText(String text) {
        if (text == null || text.isBlank()) return false;

        String trimmed = text.trim();

        // Must have at least 3 characters
        if (trimmed.length() < 3) return false;

        // Must contain at least one real letter or digit
        boolean hasAlphanumeric = trimmed.codePoints().anyMatch(Character::isLetterOrDigit);
        if (!hasAlphanumeric) return false;

        // Reject if any letter repeats 5+ times consecutively — always noise (e.g. "வவவவவ", "aaaaa")
        if (hasExcessiveRepetition(trimmed)) return false;

        return true;
    }

    private boolean hasExcessiveRepetition(String s) {
        if (s.length() < 5) return false;
        int maxLetterRepeat = 1;
        int currentRepeat = 1;

        int[] codePoints = s.codePoints().toArray();
        for (int i = 1; i < codePoints.length; i++) {
            if (codePoints[i] == codePoints[i - 1] && Character.isLetter(codePoints[i])) {
                currentRepeat++;
                maxLetterRepeat = Math.max(maxLetterRepeat, currentRepeat);
            } else {
                currentRepeat = 1;
            }
        }
        // 5 identical letters in a row is always noise
        return maxLetterRepeat >= 5;
    }
}

