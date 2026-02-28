package com.smartgallery.service;

import com.smartgallery.config.AppConfig;
import com.smartgallery.dto.ModelStatusDto;
import com.smartgallery.dto.ModelStatusDto.ModelFileInfo;
import com.smartgallery.dto.ModelStatusDto.Status;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks the current state of downloaded ONNX model files on disk.
 */
@Service
public class ModelStatusService {

    private static final String[][] MODEL_FILES = {
            { "vision_model.onnx", "Image Encoder (vision_model.onnx)" },
            { "text_model.onnx", "Text Encoder (text_model.onnx)" },
            { "tokenizer.json", "Tokenizer (tokenizer.json)" }
    };

    private final AppConfig appConfig;
    private final ModelDownloaderService downloaderService;
    private final OnnxInferenceService onnxInferenceService;

    public ModelStatusService(AppConfig appConfig,
            ModelDownloaderService downloaderService,
            OnnxInferenceService onnxInferenceService) {
        this.appConfig = appConfig;
        this.downloaderService = downloaderService;
        this.onnxInferenceService = onnxInferenceService;
    }

    /**
     * Returns the current status of the model files on disk and inference
     * readiness.
     */
    public ModelStatusDto getStatus() {
        ModelStatusDto dto = new ModelStatusDto();
        List<ModelFileInfo> files = new ArrayList<>();

        if (downloaderService.isDownloading()) {
            dto.setStatus(Status.DOWNLOADING);
            dto.setMessage("Download in progress...");
            dto.setFiles(files);
            return dto;
        }

        Path modelDir = Paths.get(appConfig.getModelDir());
        int presentCount = 0;

        for (String[] fileEntry : MODEL_FILES) {
            String fileName = fileEntry[0];
            String displayName = fileEntry[1];
            Path filePath = modelDir.resolve(fileName);

            ModelFileInfo info = new ModelFileInfo();
            info.setName(displayName);
            info.setPath(filePath.toAbsolutePath().toString());

            boolean exists = Files.exists(filePath);
            info.setExists(exists);

            if (exists) {
                try {
                    info.setSizeBytes(Files.size(filePath));
                } catch (Exception ignored) {
                }
                presentCount++;
            } else {
                info.setSizeBytes(0);
            }
            files.add(info);
        }

        dto.setFiles(files);

        if (presentCount == 0) {
            dto.setStatus(Status.NOT_DOWNLOADED);
            dto.setMessage("No model files found. Please enter a Hugging Face token and click Download Models.");
        } else if (presentCount < MODEL_FILES.length) {
            dto.setStatus(Status.PARTIAL);
            dto.setMessage("Some model files are missing. Please re-run the download.");
        } else if (onnxInferenceService.isReady()) {
            dto.setStatus(Status.READY);
            dto.setMessage("All models loaded and ready for inference.");
        } else {
            // Files exist but not loaded yet
            String lastError = downloaderService.getLastError();
            if (lastError != null) {
                dto.setStatus(Status.ERROR);
                dto.setMessage("Error: " + lastError);
            } else {
                dto.setStatus(Status.PARTIAL);
                dto.setMessage("Model files present but not loaded. Restart may be needed.");
            }
        }

        return dto;
    }
}
