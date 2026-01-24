/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.witness.aggregation.recursive.compression;

import com.hellblazer.delos.witness.proto.CompressionCodec;

/**
 * Metrics for compression operations.
 * Immutable record tracking size, ratio, and timing.
 *
 * @param originalSize     Size before compression (bytes)
 * @param compressedSize   Size after compression (bytes)
 * @param codec            Codec used
 * @param compressionTimeNs Time to compress (nanoseconds)
 * @param fallbackApplied  Whether fallback to NONE was applied
 */
public record CompressionMetrics(
    int originalSize,
    int compressedSize,
    CompressionCodec codec,
    long compressionTimeNs,
    boolean fallbackApplied
) {
    public CompressionMetrics {
        if (originalSize < 0) {
            throw new IllegalArgumentException("originalSize must be >= 0");
        }
        if (compressedSize < 0) {
            throw new IllegalArgumentException("compressedSize must be >= 0");
        }
        if (codec == null) {
            throw new IllegalArgumentException("codec cannot be null");
        }
        if (compressionTimeNs < 0) {
            throw new IllegalArgumentException("compressionTimeNs must be >= 0");
        }
    }

    /**
     * Calculate compression ratio as percentage reduction.
     * @return Percentage reduction (0-100), or 0 if no compression
     */
    public double compressionRatio() {
        if (originalSize == 0) {
            return 0.0;
        }
        return (1.0 - ((double) compressedSize / originalSize)) * 100.0;
    }

    /**
     * Calculate compression rate in MB/s.
     * @return Compression throughput in megabytes per second
     */
    public double compressionRateMBps() {
        if (compressionTimeNs == 0) {
            return 0.0;
        }
        return (originalSize / (1024.0 * 1024.0)) / (compressionTimeNs / 1_000_000_000.0);
    }

    /**
     * Check if compression was effective (reduced size).
     * @return true if compressed size < original size
     */
    public boolean isEffective() {
        return compressedSize < originalSize;
    }
}
