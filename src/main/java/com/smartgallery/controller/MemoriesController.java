package com.smartgallery.controller;

import com.smartgallery.dto.SearchResultItem;
import com.smartgallery.entity.ImageEntity;
import com.smartgallery.repository.ImageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * REST controller for the "Memories" feature.
 *
 * Exposes:
 *   GET /api/memories/on-this-day   — images from same month+day in any prior year
 *   GET /api/memories/today         — images modified today
 */
@RestController
@RequestMapping("/api/memories")
public class MemoriesController {

    private static final Logger log = LoggerFactory.getLogger(MemoriesController.class);

    private final ImageRepository imageRepository;

    public MemoriesController(ImageRepository imageRepository) {
        this.imageRepository = imageRepository;
    }

    /**
     * Returns images taken on the same month and day in any previous year.
     * Groups results by year so the frontend can show "X years ago" labels.
     */
    @GetMapping("/on-this-day")
    public List<SearchResultItem> getOnThisDay() {
        LocalDate today = LocalDate.now();
        int month = today.getMonthValue();
        int day = today.getDayOfMonth();
        int currentYear = today.getYear();

        List<ImageEntity> entities = imageRepository.findOnThisDay(month, day, currentYear);
        log.debug("Memories: found {} images for {}-{} in previous years", entities.size(), month, day);

        return entities.stream()
                .map(this::toResultItem)
                .collect(Collectors.toList());
    }

    /**
     * Returns images whose lastModified timestamp falls within today's date.
     */
    @GetMapping("/today")
    public List<SearchResultItem> getPhotosOfTheDay() {
        LocalDate today = LocalDate.now();
        LocalDateTime start = today.atStartOfDay();
        LocalDateTime end = today.atTime(23, 59, 59);

        List<ImageEntity> entities = imageRepository.findByLastModifiedBetween(start, end);
        log.debug("Memories: found {} images from today", entities.size());

        return entities.stream()
                .map(this::toResultItem)
                .collect(Collectors.toList());
    }

    private SearchResultItem toResultItem(ImageEntity entity) {
        SearchResultItem item = new SearchResultItem();
        item.setId(entity.getId());
        item.setFilePath(entity.getFilePath());
        item.setFileName(new File(entity.getFilePath()).getName());
        item.setThumbUrl("/api/images/" + entity.getId() + "/thumb");
        item.setScore(0.0);
        item.setWidth(entity.getWidth());
        item.setHeight(entity.getHeight());
        item.setFileSize(entity.getFileSize());
        item.setLastModified(entity.getLastModified() != null ? entity.getLastModified().toString() : "");
        item.setIndexedAt(entity.getIndexedAt() != null ? entity.getIndexedAt().toString() : "");
        item.setExtraJson(entity.getExtraJson());
        item.setExtractedText(entity.getExtractedText());
        item.setStatus(entity.getStatus());
        item.setLoved(entity.isLoved());
        item.setBlurred(entity.isBlurred());
        item.setLatitude(entity.getLatitude());
        item.setLongitude(entity.getLongitude());
        return item;
    }
}
