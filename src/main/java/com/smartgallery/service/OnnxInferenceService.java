package com.smartgallery.service;

import ai.onnxruntime.*;
import com.smartgallery.config.AppConfig;
import com.smartgallery.util.EmbeddingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * ONNX Runtime service for computing CLIP image and text embeddings.
 *
 * Uses two ONNX models from Xenova/clip-vit-base-patch32:
 * - vision_model.onnx: encodes images (224×224 px, NCHW layout)
 * - text_model.onnx: encodes tokenized text
 *
 * Image preprocessing pipeline (must match CLIP training):
 * 1. Resize shortest side to 224 (or center-crop to square first)
 * 2. Convert to float, scale to [0, 1]
 * 3. Normalize per channel: mean = [0.48145466, 0.4578275, 0.40821073]
 * std = [0.26862954, 0.26130258, 0.27577711]
 * 4. Layout: NCHW [1, 3, 224, 224]
 *
 * ONNX model I/O (Xenova/clip-vit-base-patch32):
 * vision_model.onnx:
 * input: pixel_values [1, 3, 224, 224] float32
 * output: image_embeds [1, 512] float32
 * text_model.onnx:
 * input: input_ids [1, 77] int64
 * output: text_embeds [1, 512] float32
 */
@Service
public class OnnxInferenceService {

    private static final Logger log = LoggerFactory.getLogger(OnnxInferenceService.class);

    // CLIP normalization constants
    private static final float[] CLIP_MEAN = { 0.48145466f, 0.4578275f, 0.40821073f };
    private static final float[] CLIP_STD = { 0.26862954f, 0.26130258f, 0.27577711f };
    private static final int IMAGE_SIZE = 224;
    private static final int EMBEDDING_DIM = 512;
    private static final int MAX_SEQ_LEN = 77;

    private final AppConfig appConfig;
    private final ClipTokenizer clipTokenizer;

    private OrtEnvironment ortEnvironment;
    private OrtSession visualSession;
    private OrtSession textSession;
    private volatile boolean modelsLoaded = false;

    public OnnxInferenceService(AppConfig appConfig, ClipTokenizer clipTokenizer) {
        this.appConfig = appConfig;
        this.clipTokenizer = clipTokenizer;
    }

    /**
     * Called at startup — tries to load models if they are already present on disk.
     * Does not fail the application if models are absent (they must be downloaded
     * first).
     */
    @PostConstruct
    public void tryLoadModels() {
        try {
            Path visualPath = Paths.get(appConfig.getModelDir(), "vision_model.onnx");
            Path textPath = Paths.get(appConfig.getModelDir(), "text_model.onnx");
            Path tokenizerPath = Paths.get(appConfig.getModelDir(), "tokenizer.json");

            if (Files.exists(visualPath) && Files.exists(textPath) && Files.exists(tokenizerPath)) {
                loadModels(visualPath, textPath, tokenizerPath);
            } else {
                log.info("ONNX models not found at {}. Use Settings to download them.", appConfig.getModelDir());
            }
        } catch (Exception e) {
            log.error("Failed to load ONNX models at startup: {}", e.getMessage());
        }
    }

    /**
     * Loads (or reloads) the ONNX sessions. Called after model download completes.
     */
    public synchronized void loadModels(Path visualPath, Path textPath, Path tokenizerPath)
            throws OrtException, IOException {
        log.info("Loading ONNX models...");

        // Close existing sessions if any
        closeSessionsSilently();

        ortEnvironment = OrtEnvironment.getEnvironment();

        // Configure session options for performance
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);

        visualSession = ortEnvironment.createSession(visualPath.toString(), opts);
        log.info("Visual ONNX model loaded: {}", visualPath.getFileName());

        textSession = ortEnvironment.createSession(textPath.toString(), opts);
        log.info("Text ONNX model loaded: {}", textPath.getFileName());

        // Load the tokenizer
        clipTokenizer.load(tokenizerPath);

        modelsLoaded = true;
        log.info("ONNX models and tokenizer ready.");
    }

    /**
     * Returns true if both ONNX models and tokenizer are loaded and ready.
     */
    public boolean isReady() {
        return modelsLoaded && visualSession != null && textSession != null && clipTokenizer.isLoaded();
    }

    /**
     * Computes a 512-dimensional L2-normalized CLIP image embedding.
     *
     * @param imagePath path to the source image file
     * @return float[512] L2-normalized embedding, or null if the image cannot be
     *         processed
     */
    public float[] embedImage(Path imagePath) {
        if (!isReady()) {
            log.warn("Cannot embed image — ONNX models not loaded");
            return null;
        }
        try {
            // ── Step 1: Load and preprocess image ──────────────────────────────
            BufferedImage original = ImageIO.read(imagePath.toFile());
            if (original == null) {
                log.warn("Could not read image: {}", imagePath);
                return null;
            }

            // Center-crop to square, then resize to 224×224
            BufferedImage square = centerCropToSquare(original);
            BufferedImage resized = resizeTo(square, IMAGE_SIZE, IMAGE_SIZE);

            // ── Step 2: Convert to NCHW float32 tensor ─────────────────────────
            // Shape: [1, 3, 224, 224]
            float[] pixelValues = imageToNchwFloat(resized);

            // ── Step 3: Run visual ONNX model ──────────────────────────────────
            try (OnnxTensor inputTensor = OnnxTensor.createTensor(
                    ortEnvironment,
                    FloatBuffer.wrap(pixelValues),
                    new long[] { 1, 3, IMAGE_SIZE, IMAGE_SIZE });
                    OrtSession.Result result = visualSession.run(
                            Collections.singletonMap("pixel_values", inputTensor))) {

                // ── Step 4: Extract output embedding ──────────────────────────
                float[][] output = (float[][]) result.get("image_embeds").get().getValue();
                float[] embedding = output[0];

                // ── Step 5: L2 normalize ───────────────────────────────────────
                return EmbeddingUtils.l2Normalize(embedding);
            }
        } catch (Exception e) {
            log.error("Failed to embed image {}: {}", imagePath, e.getMessage());
            return null;
        }
    }

    /**
     * Computes a 512-dimensional L2-normalized CLIP text embedding.
     *
     * @param text the search query or description
     * @return float[512] L2-normalized embedding
     */
    public float[] embedText(String text) {
        if (!isReady()) {
            log.warn("Cannot embed text — ONNX models not loaded");
            return null;
        }
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            // ── Step 1: Tokenize text ──────────────────────────────────────────
            ClipTokenizer.TokenOutput tokens = clipTokenizer.tokenize(text);

            // ── Step 2: Create input tensors (shape: [1, 77]) ──────────────────
            try (OnnxTensor inputIdsTensor = OnnxTensor.createTensor(
                    ortEnvironment,
                    LongBuffer.wrap(tokens.inputIds),
                    new long[] { 1, MAX_SEQ_LEN });
                    OrtSession.Result result = textSession.run(
                            Map.of("input_ids", inputIdsTensor))) {

                // ── Step 3: Extract text embedding ────────────────────────────
                float[][] output = (float[][]) result.get("text_embeds").get().getValue();
                float[] embedding = output[0];

                // ── Step 4: L2 normalize ───────────────────────────────────────
                return EmbeddingUtils.l2Normalize(embedding);
            }
        } catch (Exception e) {
            log.error("Failed to embed text '{}': {}", text, e.getMessage());
            return null;
        }
    }

    /**
     * Center-crops a BufferedImage to a square using the shorter dimension.
     */
    private BufferedImage centerCropToSquare(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        if (w == h)
            return img;
        int size = Math.min(w, h);
        int x = (w - size) / 2;
        int y = (h - size) / 2;
        return img.getSubimage(x, y, size, size);
    }

    /**
     * Resizes a BufferedImage to the specified width and height using bilinear
     * interpolation.
     */
    private BufferedImage resizeTo(BufferedImage img, int targetW, int targetH) {
        BufferedImage resized = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(img, 0, 0, targetW, targetH, null);
        g.dispose();
        return resized;
    }

    /**
     * Converts a 224x224 RGB BufferedImage to a flat NCHW float32 array.
     *
     * Layout order: [channel][row][col]
     * Channel order: R=0, G=1, B=2
     * Normalization: value = (pixel/255 - mean[c]) / std[c]
     *
     * @param img 224x224 RGB image
     * @return float array of length 3 * 224 * 224 = 150528
     */
    private float[] imageToNchwFloat(BufferedImage img) {
        int w = img.getWidth(); // 224
        int h = img.getHeight(); // 224
        int channelSize = w * h;
        float[] pixels = new float[3 * channelSize];

        // Extract individual channel planes
        float[] rPlane = new float[channelSize];
        float[] gPlane = new float[channelSize];
        float[] bPlane = new float[channelSize];

        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                int rgb = img.getRGB(col, row);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int idx = row * w + col;
                // Normalize: (px/255 - mean) / std
                rPlane[idx] = (r / 255.0f - CLIP_MEAN[0]) / CLIP_STD[0];
                gPlane[idx] = (g / 255.0f - CLIP_MEAN[1]) / CLIP_STD[1];
                bPlane[idx] = (b / 255.0f - CLIP_MEAN[2]) / CLIP_STD[2];
            }
        }

        // Pack into NCHW layout: [R plane][G plane][B plane]
        System.arraycopy(rPlane, 0, pixels, 0, channelSize);
        System.arraycopy(gPlane, 0, pixels, channelSize, channelSize);
        System.arraycopy(bPlane, 0, pixels, 2 * channelSize, channelSize);
        return pixels;
    }

    /**
     * Closes ONNX sessions silently (for reload or shutdown).
     */
    private void closeSessionsSilently() {
        modelsLoaded = false;
        if (visualSession != null) {
            try {
                visualSession.close();
            } catch (Exception ignored) {
            }
            visualSession = null;
        }
        if (textSession != null) {
            try {
                textSession.close();
            } catch (Exception ignored) {
            }
            textSession = null;
        }
        if (ortEnvironment != null) {
            try {
                ortEnvironment.close();
            } catch (Exception ignored) {
            }
            ortEnvironment = null;
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down ONNX sessions...");
        closeSessionsSilently();
    }
}
