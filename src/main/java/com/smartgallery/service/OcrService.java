package com.smartgallery.service;

import com.benjaminwan.ocrlibrary.OcrResult;
import io.github.mymonstercat.Model;
import io.github.mymonstercat.ocr.InferenceEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * OCR service backed by RapidOCR v1.2.2 (ONNX runtime).
 *
 * ── How custom language models work ──────────────────────────────────────────
 *
 * RapidOcr.dll v1.2.2 has a fixed model-loading design:
 * • It extracts bundled model files to C:\...\AppData\Local\Temp\ocrJava\onnx\
 * • initModels() is hard-coded to that temp directory; passing any external
 * path causes an EXCEPTION_ACCESS_VIOLATION crash (confirmed experimentally).
 *
 * Workaround: before the DLL's first runOcr() call (which triggers initModels),
 * we overwrite the bundled recognition model and character dictionary in the
 * DLL's own temp directory with the user's downloaded Tamil/custom models.
 * The detection model is language-agnostic and shared — we leave it untouched.
 *
 * Safe window: the temp dir is populated by loadFileIfNeeded() (which we
 * trigger
 * via reflection) BEFORE initModels() runs. After loadFileIfNeeded() the files
 * exist and are writable; we overwrite rec + dict, then let the first runOcr()
 * call initModels() normally — it now loads our custom files from the same dir.
 */
@Service
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);

    private InferenceEngine engine;

    @Autowired
    @Lazy
    private OcrModelService ocrModelService;

    @Autowired
    private SettingsService settingsService;

    @PostConstruct
    public void init() {
        try {
            engine = InferenceEngine.getInstance(Model.ONNX_PPOCR_V4);
        } catch (Exception e) {
            log.error("Failed to obtain InferenceEngine singleton: {}", e.getMessage());
            return;
        }

        // Trigger loadFileIfNeeded so the DLL temp dir is populated,
        // then swap in the Tamil model files before the DLL's first initModels() call.
        String modelKey = resolveActiveModelKey();
        if (modelKey != null) {
            injectCustomRecModel(modelKey);
        } else {
            log.info("OCR engine ready. Using bundled Chinese+English PPOCR-V4 model.");
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void reloadEngine() {
        log.info("OCR settings saved. Restart the application to apply model changes " +
                "(RapidOCR v1.2.2 requires a restart to switch recognition models).");
    }

    public synchronized String extractText(File imageFile) {
        if (engine == null)
            return null;

        boolean ocrEnabled = settingsService.getSetting(SettingsService.KEY_OCR_INDEXING_ENABLED)
                .map(Boolean::parseBoolean).orElse(false);
        if (!ocrEnabled)
            return null;

        if (imageFile == null || !imageFile.exists()
                || imageFile.length() == 0
                || imageFile.length() > 25 * 1024 * 1024) {
            return null;
        }

        try {
            OcrResult result = engine.runOcr(imageFile.getAbsolutePath());
            if (result == null || result.getStrRes() == null || result.getStrRes().isBlank()) {
                return null;
            }
            return result.getStrRes().trim();
        } catch (Exception e) {
            log.error("OCR error for {}: {}", imageFile.getName(), e.getMessage());
            return null;
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private String resolveActiveModelKey() {
        String activeRaw = settingsService.getSetting("ocr.active.models").orElse("");
        if (activeRaw.isBlank())
            return null;

        return Arrays.stream(activeRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .filter(k -> ocrModelService.isDownloaded(k))
                .findFirst()
                .orElse(null);
    }

    /**
     * Injects a custom recognition model into RapidOCR's controlled temp directory.
     *
     * Steps:
     * 1. Call loadFileIfNeeded() via reflection — extracts bundled DLL + models to
     * temp dir.
     * 2. Overwrite the bundled rec model + dict with the user's downloaded files.
     * (Detection model is shared; we leave ch_PP-OCRv4_det_infer.onnx untouched.)
     * 3. The next runOcr() → initModels() picks up our files from the same temp
     * dir.
     */
    private void injectCustomRecModel(String modelKey) {
        Path srcDir = Path.of(OcrModelService.OCR_MODELS_DIR, modelKey);
        Path srcRec = srcDir.resolve("rec_model.onnx");
        Path srcDict = srcDir.resolve("dict.txt");

        if (!Files.exists(srcRec)) {
            log.warn("Custom rec model not found at {}. Using bundled model.", srcRec);
            return;
        }

        try {
            // Step 1: ensure the DLL's temp dir exists and rec model is in place
            triggerLibraryExtraction();

            // Step 2: find the temp dir
            Path tempDir = Path.of(System.getProperty("java.io.tmpdir"), "ocrJava", "onnx");
            if (!Files.isDirectory(tempDir)) {
                log.warn("RapidOCR temp dir not found at {}. Using bundled model.", tempDir);
                return;
            }

            // Step 3: overwrite rec model
            Path destRec = tempDir.resolve("ch_PP-OCRv4_rec_infer.onnx");
            Path destDict = tempDir.resolve("ppocr_keys_v1.txt");

            Files.copy(srcRec, destRec, StandardCopyOption.REPLACE_EXISTING);
            log.info("Injected custom rec model: {} → {}", srcRec.getFileName(), destRec);

            if (Files.exists(srcDict)) {
                Files.copy(srcDict, destDict, StandardCopyOption.REPLACE_EXISTING);
                log.info("Injected custom dict: {} → {}", srcDict.getFileName(), destDict);
            }

            log.info("✓ OCR will use custom model '{}' on next scan.", modelKey);

        } catch (Exception e) {
            log.error("Failed to inject custom model '{}': {}. Using bundled model.",
                    modelKey, e.getMessage());
        }
    }

    /**
     * Calls the private-static InferenceEngine.loadFileIfNeeded() via reflection
     * to extract the bundled DLL and model files to the RapidOCR temp directory.
     * This does NOT call initModels() — that happens lazily on the first runOcr().
     */
    private void triggerLibraryExtraction() throws Exception {
        Method m = InferenceEngine.class.getDeclaredMethod("loadFileIfNeeded", Model.class);
        m.setAccessible(true);
        m.invoke(null, Model.ONNX_PPOCR_V4);
        log.debug("RapidOCR native library extracted to temp dir.");
    }
}
