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
 * Configuration for proof compression strategies.
 * Immutable record for thread-safe configuration sharing.
 *
 * @param codec                 Compression algorithm to use (LZ4, ZSTD, or NONE)
 * @param maxExpansionRatio     Maximum allowed decompression expansion (防decompression bombs)
 * @param enableFallback        Whether to fallback to NONE if compressed size >= original
 * @param compressionLevel      Compression level (codec-specific, 0 = default)
 */
public record CompressionConfig(
    CompressionCodec codec,
    int maxExpansionRatio,
    boolean enableFallback,
    int compressionLevel
) {
    /**
     * Default configuration: LZ4 with safe defaults.
     */
    public static final CompressionConfig DEFAULT = new CompressionConfig(
        CompressionCodec.LZ4,
        100,  // Max 100x expansion
        true, // Enable fallback to NONE
        0     // Default compression level
    );

    /**
     * Fast compression configuration: LZ4 optimized for speed.
     */
    public static final CompressionConfig FAST = new CompressionConfig(
        CompressionCodec.LZ4,
        100,
        true,
        0
    );

    /**
     * Best compression configuration: ZSTD optimized for ratio.
     */
    public static final CompressionConfig BEST = new CompressionConfig(
        CompressionCodec.ZSTD,
        100,
        true,
        3  // ZSTD level 3 for balanced speed/ratio
    );

    /**
     * No compression configuration.
     */
    public static final CompressionConfig NONE = new CompressionConfig(
        CompressionCodec.NONE,
        1,
        false,
        0
    );

    public CompressionConfig {
        if (codec == null) {
            throw new IllegalArgumentException("codec cannot be null");
        }
        if (maxExpansionRatio < 1) {
            throw new IllegalArgumentException("maxExpansionRatio must be >= 1");
        }
        if (compressionLevel < 0) {
            throw new IllegalArgumentException("compressionLevel must be >= 0");
        }
    }
}
