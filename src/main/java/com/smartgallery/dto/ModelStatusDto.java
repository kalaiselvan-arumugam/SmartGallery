package com.smartgallery.dto;

import java.util.List;
import java.util.Map;

/**
 * Model status response with file-level details.
 */
public class ModelStatusDto {

    public enum Status {
        NOT_DOWNLOADED, DOWNLOADING, READY, PARTIAL, ERROR
    }

    private Status status;
    private String message;
    private List<ModelFileInfo> files;

    public static class ModelFileInfo {
        private String name;
        private boolean exists;
        private long sizeBytes;
        private String path;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public boolean isExists() {
            return exists;
        }

        public void setExists(boolean exists) {
            this.exists = exists;
        }

        public long getSizeBytes() {
            return sizeBytes;
        }

        public void setSizeBytes(long sizeBytes) {
            this.sizeBytes = sizeBytes;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<ModelFileInfo> getFiles() {
        return files;
    }

    public void setFiles(List<ModelFileInfo> files) {
        this.files = files;
    }
}
