package com.smartgallery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

import java.io.File;

@SpringBootApplication
@EnableAsync
public class SmartGalleryApplication {

    private static final Logger log = LoggerFactory.getLogger(SmartGalleryApplication.class);

    public static void main(String[] args) {
        // Ensure required data directories exist before Spring context loads
        ensureDirectories();
        SpringApplication.run(SmartGalleryApplication.class, args);
        log.info("==========================================================");
        log.info("  SmartGallery is running at http://localhost");
        log.info("  Open your browser to get started.");
        log.info("  Go to Settings to enter your Hugging Face token.");
        log.info("==========================================================");
    }

    private static void ensureDirectories() {
        String[] dirs = {
                "./data", "./data/db", "./data/models", "./data/images", "./data/thumbs"
        };
        for (String dir : dirs) {
            File f = new File(dir);
            if (!f.exists()) {
                f.mkdirs();
            }
        }
    }
}
