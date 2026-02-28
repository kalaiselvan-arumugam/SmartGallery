package com.smartgallery.util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Utility methods for converting between float[] and byte[] for embedding
 * storage in H2 BLOB.
 * Uses little-endian byte order (IEEE 754 float32).
 */
public final class EmbeddingUtils {

    private EmbeddingUtils() {
    }

    /**
     * Converts a float array to a byte array (4 bytes per float, little-endian).
     *
     * @param floats the float array (e.g. 512-dimensional CLIP embedding)
     * @return byte[] suitable for storing as a BLOB in H2
     */
    public static byte[] toBytes(float[] floats) {
        if (floats == null)
            return null;
        ByteBuffer buffer = ByteBuffer.allocate(floats.length * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float f : floats) {
            buffer.putFloat(f);
        }
        return buffer.array();
    }

    /**
     * Converts a byte array back to a float array.
     *
     * @param bytes the raw byte blob from H2
     * @return float[] CLIP embedding vector
     */
    public static float[] fromBytes(byte[] bytes) {
        if (bytes == null)
            return null;
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] floats = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < floats.length; i++) {
            floats[i] = buffer.getFloat();
        }
        return floats;
    }

    /**
     * L2-normalizes a float vector in-place so its magnitude is 1.0.
     * CLIP embeddings are normalized before storage to allow pure dot-product
     * similarity.
     *
     * @param vector the input vector (modified in place)
     * @return the normalized vector (same reference)
     */
    public static float[] l2Normalize(float[] vector) {
        double sumOfSquares = 0.0;
        for (float v : vector) {
            sumOfSquares += (double) v * v;
        }
        double magnitude = Math.sqrt(sumOfSquares);
        if (magnitude < 1e-10) {
            // Zero vector — return as-is to avoid division by zero
            return vector;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / magnitude);
        }
        return vector;
    }

    /**
     * Computes the dot product between two L2-normalized vectors.
     * For normalized vectors this equals the cosine similarity, in range [-1, 1].
     *
     * @param a first L2-normalized vector
     * @param b second L2-normalized vector
     * @return cosine similarity score
     */
    public static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vectors must have the same length: " + a.length + " vs " + b.length);
        }
        double dot = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        return dot;
    }
}
