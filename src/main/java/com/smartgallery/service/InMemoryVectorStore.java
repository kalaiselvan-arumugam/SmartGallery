package com.smartgallery.service;

import com.smartgallery.util.EmbeddingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Thread-safe in-memory vector store for fast cosine similarity search over
 * CLIP embeddings.
 *
 * All embeddings are assumed to be L2-normalized (unit vectors), so cosine
 * similarity
 * equals the dot product of two vectors, computed efficiently here.
 *
 * For datasets up to ~100k images this runs entirely in memory. At 512 floats *
 * 4 bytes
 * per embedding, 100k images require ~200 MB of heap.
 *
 * Thread safety: Uses a ReentrantReadWriteLock — many concurrent readers,
 * exclusive writers.
 */
@Component
public class InMemoryVectorStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryVectorStore.class);

    // Parallel arrays for fast iteration (better cache locality than List of
    // objects)
    private long[] ids; // image IDs
    private float[][] vectors; // L2-normalized embedding vectors [imageIdx][dim]
    private int size; // current number of entries

    private static final int INITIAL_CAPACITY = 1024;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public InMemoryVectorStore() {
        ids = new long[INITIAL_CAPACITY];
        vectors = new float[INITIAL_CAPACITY][];
        size = 0;
    }

    /**
     * Loads the entire embedding set from the database (called at startup and after
     * bulk reindex).
     *
     * @param embeddingData list of [Long imageId, byte[] embeddingBytes] pairs
     */
    public void loadAll(List<Object[]> embeddingData) {
        lock.writeLock().lock();
        try {
            ids = new long[Math.max(embeddingData.size(), INITIAL_CAPACITY)];
            vectors = new float[Math.max(embeddingData.size(), INITIAL_CAPACITY)][];
            size = 0;

            for (Object[] row : embeddingData) {
                try {
                    Long id = ((Number) row[0]).longValue();
                    byte[] embBytes = null;
                    if (row[1] instanceof byte[]) {
                        embBytes = (byte[]) row[1];
                    } else if (row[1] instanceof java.sql.Blob) {
                        java.sql.Blob blob = (java.sql.Blob) row[1];
                        embBytes = blob.getBytes(1, (int) blob.length());
                    }
                    if (id != null && embBytes != null) {
                        float[] vec = EmbeddingUtils.fromBytes(embBytes);
                        if (vec != null && vec.length > 0) {
                            ids[size] = id;
                            vectors[size] = vec;
                            size++;
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to load vector row: {}", e.getMessage());
                }
            }
            log.info("InMemoryVectorStore loaded {} embeddings", size);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Adds or updates a single embedding entry.
     *
     * @param imageId the image database ID
     * @param vector  the L2-normalized float embedding
     */
    public void addOrUpdate(long imageId, float[] vector) {
        lock.writeLock().lock();
        try {
            // Check if ID already exists (update path)
            for (int i = 0; i < size; i++) {
                if (ids[i] == imageId) {
                    vectors[i] = vector;
                    return;
                }
            }
            // New entry — grow arrays if needed
            if (size >= ids.length) {
                int newCapacity = ids.length * 2;
                ids = Arrays.copyOf(ids, newCapacity);
                vectors = Arrays.copyOf(vectors, newCapacity);
            }
            ids[size] = imageId;
            vectors[size] = vector;
            size++;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Removes a single embedding by image ID.
     */
    public void remove(long imageId) {
        lock.writeLock().lock();
        try {
            for (int i = 0; i < size; i++) {
                if (ids[i] == imageId) {
                    // Swap with last entry and shrink
                    ids[i] = ids[size - 1];
                    vectors[i] = vectors[size - 1];
                    vectors[size - 1] = null;
                    size--;
                    return;
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Finds the top-K most similar images to the query vector using dot-product
     * similarity.
     * Both query and stored vectors must be L2-normalized.
     *
     * @param query  L2-normalized query embedding (512-d)
     * @param topK   number of results to return
     * @param offset number of results to skip for pagination
     * @return list of (imageId, score) ordered by descending similarity
     */
    public List<SearchHit> findTopK(float[] query, int topK, int offset) {
        lock.readLock().lock();
        try {
            int totalNeeded = offset + topK;
            int count = Math.min(size, totalNeeded);
            if (count == 0 || offset >= size)
                return Collections.emptyList();

            // Score all vectors using dot product (= cosine similarity for unit vectors)
            // We keep a running min-heap of size totalNeeded for efficiency
            PriorityQueue<SearchHit> minHeap = new PriorityQueue<>(
                    count + 1, Comparator.comparingDouble(h -> h.score));

            for (int i = 0; i < size; i++) {
                double score = dotProduct(query, vectors[i]);
                if (minHeap.size() < totalNeeded) {
                    minHeap.offer(new SearchHit(ids[i], score));
                } else if (score > minHeap.peek().score) {
                    minHeap.poll();
                    minHeap.offer(new SearchHit(ids[i], score));
                }
            }

            // Sort by descending score
            List<SearchHit> results = new ArrayList<>(minHeap);
            results.sort((a, b) -> Double.compare(b.score, a.score));

            // Return the paginated slice
            int endIdx = Math.min(results.size(), offset + topK);
            return results.subList(offset, endIdx);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns the current number of embeddings in the store.
     */
    public int getSize() {
        lock.readLock().lock();
        try {
            return size;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Computes dot product of two float vectors (SIMD-friendly flat loop).
     */
    private double dotProduct(float[] a, float[] b) {
        double sum = 0.0;
        int len = Math.min(a.length, b.length);
        for (int i = 0; i < len; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }

    /** Represents a search result from the vector store. */
    public static class SearchHit {
        public final long imageId;
        public final double score;

        public SearchHit(long imageId, double score) {
            this.imageId = imageId;
            this.score = score;
        }
    }
}
