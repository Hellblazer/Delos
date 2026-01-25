/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.witness.aggregation.recursive.compression;

/**
 * Strategy interface for compression algorithms.
 * Implementations must be thread-safe.
 */
public interface CompressionStrategy {
    /**
     * Compress input bytes.
     * @param input Data to compress
     * @param config Compression configuration
     * @return Compressed bytes (may be same size or larger if compression ineffective)
     * @throws CompressionException if compression fails
     */
    byte[] encode(byte[] input, CompressionConfig config);

    /**
     * Decompress input bytes.
     * @param compressed Compressed data
     * @param config Decompression configuration (includes maxExpansionRatio)
     * @return Original uncompressed bytes
     * @throws CompressionException if decompression fails or expansion limit exceeded
     */
    byte[] decode(byte[] compressed, CompressionConfig config);

    /**
     * Get codec identifier for this strategy.
     * @return CompressionCodec enum value
     */
    com.hellblazer.delos.witness.proto.CompressionCodec codec();
}
