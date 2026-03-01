package com.smartgallery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "app")
public class AppConfig {

    /** Directory where ONNX model files are stored */
    private String modelDir = "./data/models";

    /** Comma-separated list of image directories to watch and index */
    private String imageDirs = "./data/images";

    /** Directory where generated thumbnails are stored */
    private String thumbDir = "./data/thumbs";

    /** Base data directory */
    private String dataDir = "./data";

    /** Hugging Face repository for CLIP ONNX models */
    private String hfRepo = "Xenova/clip-vit-base-patch32";

    /** Allow automatic model downloads from Hugging Face */
    private boolean allowAutoDownload = true;

    /** Thumbnail size in pixels (square) */
    private int thumbSize = 300;

    /** File watcher debounce delay in milliseconds */
    private long watcherDebounceMs = 1500;

    /** Maximum search results returned */
    private int searchLimit = 100;

    // ───────────── getters / setters ─────────────

    public String getModelDir() {
        return modelDir;
    }

    public void setModelDir(String modelDir) {
        this.modelDir = modelDir;
    }

    public String getImageDirs() {
        return imageDirs;
    }

    public void setImageDirs(String imageDirs) {
        this.imageDirs = imageDirs;
    }

    /** Returns the list of image directories split by comma */
    public List<String> getImageDirList() {
        return Arrays.stream(imageDirs.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    public String getThumbDir() {
        return thumbDir;
    }

    public void setThumbDir(String thumbDir) {
        this.thumbDir = thumbDir;
    }

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public String getHfRepo() {
        return hfRepo;
    }

    public void setHfRepo(String hfRepo) {
        this.hfRepo = hfRepo;
    }

    public boolean isAllowAutoDownload() {
        return allowAutoDownload;
    }

    public void setAllowAutoDownload(boolean allowAutoDownload) {
        this.allowAutoDownload = allowAutoDownload;
    }

    public int getThumbSize() {
        return thumbSize;
    }

    public void setThumbSize(int thumbSize) {
        this.thumbSize = thumbSize;
    }

    public long getWatcherDebounceMs() {
        return watcherDebounceMs;
    }

    public void setWatcherDebounceMs(long watcherDebounceMs) {
        this.watcherDebounceMs = watcherDebounceMs;
    }

    public int getSearchLimit() {
        return searchLimit;
    }

    public void setSearchLimit(int searchLimit) {
        this.searchLimit = searchLimit;
    }
}
