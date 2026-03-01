package com.smartgallery.service;

import com.smartgallery.config.AppConfig;
import com.smartgallery.entity.WatchedFolderEntity;
import com.smartgallery.repository.WatchedFolderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;

/**
 * Watches configured image directories for new or modified image files using
 * Java WatchService.
 *
 * Features:
 * - Registers all watched folders (from DB + application.properties)
 * recursively
 * - Debounces events by DEBOUNCE_MS before submitting to indexer (avoids
 * duplicate triggers)
 * - Handles ENTRY_CREATE and ENTRY_MODIFY events
 * - ENTRY_DELETE: removes image from DB and vector store
 * - On startup, registers all active watched folders from the DB
 * - Watcher thread runs as a daemon thread
 */
@Service
public class FileSystemWatcherService {

    private static final Logger log = LoggerFactory.getLogger(FileSystemWatcherService.class);

    private final AppConfig appConfig;
    private final WatchedFolderRepository watchedFolderRepository;
    private final ImageIndexerService imageIndexerService;
    private final ThumbnailService thumbnailService;
    private final SettingsService settingsService;

    private WatchService watchService;
    private Thread watcherThread;
    private final Map<WatchKey, Path> keyToPath = new ConcurrentHashMap<>();
    // Debounce: map from path to the last-seen event time
    private final Map<Path, Long> pendingEvents = new ConcurrentHashMap<>();
    private ScheduledExecutorService debounceExecutor;
    private volatile boolean running = false;

    public FileSystemWatcherService(AppConfig appConfig,
            WatchedFolderRepository watchedFolderRepository,
            ImageIndexerService imageIndexerService,
            ThumbnailService thumbnailService,
            SettingsService settingsService) {
        this.appConfig = appConfig;
        this.watchedFolderRepository = watchedFolderRepository;
        this.imageIndexerService = imageIndexerService;
        this.thumbnailService = thumbnailService;
        this.settingsService = settingsService;
    }

    @PostConstruct
    public void start() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
            debounceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "watcher-debounce");
                t.setDaemon(true);
                return t;
            });

            // Register default dirs from application.properties
            for (String dirPath : appConfig.getImageDirList()) {
                ensureFolderRegistered(dirPath);
            }

            // Register all active watched folders from DB
            List<WatchedFolderEntity> dbFolders = watchedFolderRepository.findByActiveTrue();
            for (WatchedFolderEntity folder : dbFolders) {
                try {
                    registerDirectoryTree(Paths.get(folder.getFolderPath()));
                } catch (IOException e) {
                    log.warn("Could not register watcher for {}: {}", folder.getFolderPath(), e.getMessage());
                }
            }

            // Start watcher thread
            running = true;
            watcherThread = new Thread(this::runWatchLoop, "file-watcher");
            watcherThread.setDaemon(true);
            watcherThread.start();
            log.info("File system watcher started, monitoring {} directories", keyToPath.size());

        } catch (IOException e) {
            log.error("Failed to start file watcher: {}", e.getMessage());
        }
    }

    /**
     * Registers a new folder for watching (persists to DB and starts watching).
     */
    public void addWatchFolder(String folderPath) throws IOException {
        Path dir = Paths.get(folderPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Path is not a directory: " + dir);
        }
        // Persist to DB
        if (!watchedFolderRepository.existsByFolderPath(dir.toString())) {
            watchedFolderRepository.save(new WatchedFolderEntity(dir.toString()));
        }
        registerDirectoryTree(dir);
        log.info("Added watched folder: {}", dir);
    }

    /**
     * Stops watching a folder and marks it inactive in DB.
     */
    public void removeWatchFolder(String folderPath) {
        String normalized = Paths.get(folderPath).toAbsolutePath().normalize().toString();
        watchedFolderRepository.findByFolderPath(normalized).ifPresent(entity -> {
            entity.setActive(false);
            watchedFolderRepository.save(entity);
        });
        // Remove watch keys for this path and subdirs
        keyToPath.entrySet().removeIf(e -> {
            if (e.getValue().toString().startsWith(normalized)) {
                e.getKey().cancel();
                return true;
            }
            return false;
        });
        log.info("Removed watched folder: {}", normalized);
    }

    /**
     * Ensures a folder path exists in DB and is being watched. Creates folder if
     * missing.
     */
    private void ensureFolderRegistered(String folderPath) {
        try {
            Path dir = Paths.get(folderPath).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            if (!watchedFolderRepository.existsByFolderPath(dir.toString())) {
                watchedFolderRepository.save(new WatchedFolderEntity(dir.toString()));
            }
            registerDirectoryTree(dir);
        } catch (IOException e) {
            log.warn("Could not register default folder {}: {}", folderPath, e.getMessage());
        }
    }

    /**
     * Recursively registers all subdirectories of a root directory with the
     * WatchService.
     */
    private void registerDirectoryTree(Path root) throws IOException {
        if (!Files.isDirectory(root))
            return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                try {
                    WatchKey key = dir.register(watchService,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            StandardWatchEventKinds.ENTRY_DELETE);
                    keyToPath.put(key, dir);
                } catch (IOException e) {
                    log.warn("Cannot watch {}: {}", dir, e.getMessage());
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Main watch loop — polls for file system events and debounces before indexing.
     */
    private void runWatchLoop() {
        log.info("File watcher loop started");
        while (running) {
            WatchKey key;
            try {
                key = watchService.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                break;
            }

            if (key == null) {
                // Process any debounced events that have expired
                processDebouncedEvents();
                continue;
            }

            Path parentDir = keyToPath.get(key);
            if (parentDir == null) {
                key.cancel();
                continue;
            }

            boolean autoIndex = settingsService.getSetting(SettingsService.KEY_AUTO_INDEXING_ENABLED)
                    .map(Boolean::parseBoolean).orElse(true);

            for (WatchEvent<?> event : key.pollEvents()) {
                if (!autoIndex)
                    continue; // Skip event processing if auto-indexing is disabled

                WatchEvent.Kind<?> kind = event.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW)
                    continue;

                @SuppressWarnings("unchecked")
                Path filename = ((WatchEvent<Path>) event).context();
                Path fullPath = parentDir.resolve(filename).toAbsolutePath().normalize();

                if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(fullPath)) {
                    // New subdirectory created — register it too
                    try {
                        registerDirectoryTree(fullPath);
                    } catch (IOException e) {
                        log.warn("Cannot register new subdir {}: {}", fullPath, e.getMessage());
                    }
                } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                    // File deleted — remove from index
                    if (thumbnailService.isSupportedImage(fullPath)) {
                        imageIndexerService.removeDeletedImage(fullPath);
                    }
                } else if (thumbnailService.isSupportedImage(fullPath)) {
                    // CREATE or MODIFY on image file — debounce
                    pendingEvents.put(fullPath, System.currentTimeMillis());
                }
            }

            if (!key.reset()) {
                keyToPath.remove(key);
            }

            processDebouncedEvents();
        }
        log.info("File watcher loop stopped");
    }

    /**
     * Submits indexing for any debounced events older than the configured debounce
     * delay.
     */
    private void processDebouncedEvents() {
        long now = System.currentTimeMillis();
        long debounce = appConfig.getWatcherDebounceMs();
        Iterator<Map.Entry<Path, Long>> iter = pendingEvents.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<Path, Long> entry = iter.next();
            if (now - entry.getValue() >= debounce) {
                Path path = entry.getKey();
                iter.remove();
                if (Files.isRegularFile(path)) {
                    log.debug("Auto-indexing changed file: {}", path.getFileName());
                    imageIndexerService.indexSingleFile(path);
                }
            }
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (watcherThread != null) {
            watcherThread.interrupt();
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {
            }
        }
        if (debounceExecutor != null) {
            debounceExecutor.shutdownNow();
        }
        log.info("File watcher stopped");
    }
}
