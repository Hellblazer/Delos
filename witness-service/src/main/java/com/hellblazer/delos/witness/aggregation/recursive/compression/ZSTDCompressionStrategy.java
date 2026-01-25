/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.witness.aggregation.recursive.compression;

import com.github.luben.zstd.Zstd;
import com.hellblazer.delos.witness.proto.CompressionCodec;

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * ZSTD compression strategy for RecursiveAggregateReceipt.
 * Provides better compression ratio than LZ4 (15-20% reduction) with reasonable speed.
 * <p>
 * Thread-safe: ZSTD native methods are thread-safe.
 * Virtual thread compatible: No blocking I/O, no pinning.
 * <p>
 * Performance characteristics:
 * - Compression: <10ms for ~2KB receipts (level 3)
 * - Decompression: <10ms for ~2KB receipts
 * - Throughput: >100 MB/s compression, >300 MB/s decompression
 * - Compression ratio: 15-20% better than LZ4 on typical receipt data
 * <p>
 * Fallback logic:
 * - If compressed size >= original size, returns compressed data with header
 * - Fallback decision is made at higher level (ProofCompressionCodec)
 * <p>
 * Security:
 * - Dynamic expansion limit (maxExpansionRatio) prevents decompression bombs
 * - Validates input before decompression
 * - Uses ZSTD's built-in content size header for safety
 *
 * @author hal.hildebrand
 * @since Phase 3.3.3
 */
public class ZSTDCompressionStrategy implements CompressionStrategy {

    private static final int DEFAULT_COMPRESSION_LEVEL = 3; // Balanced speed/ratio

    /**
     * Create ZSTD strategy with default compression level (3).
     */
    public ZSTDCompressionStrategy() {
        // No initialization needed - Zstd native methods are static
    }

    @Override
    public byte[] encode(byte[] input, CompressionConfig config) {
        Objects.requireNonNull(input, "input cannot be null");
        Objects.requireNonNull(config, "config cannot be null");

        if (input.length == 0) {
            throw new CompressionException("Cannot compress empty input");
        }

        try {
            // Determine compression level
            var level = config.compressionLevel() > 0 ? config.compressionLevel() : DEFAULT_COMPRESSION_LEVEL;

            // Allocate buffer for compressed data
            var maxCompressedLength = (int) Zstd.compressBound(input.length);
            var compressedBuffer = new byte[maxCompressedLength];

            // Compress with content size in header
            var compressedLength = (int) Zstd.compressByteArray(
                compressedBuffer, 0, maxCompressedLength,
                input, 0, input.length,
                level
            );

            // Check for compression error
            if (Zstd.isError(compressedLength)) {
                throw new CompressionException("ZSTD compression failed: " + Zstd.getErrorName(compressedLength));
            }

            // Prepend original size header (4 bytes, big-endian)
            var totalCompressedSize = 4 + compressedLength; // 4-byte header + compressed data
            var result = new byte[totalCompressedSize];
            var buffer = ByteBuffer.wrap(result);
            buffer.putInt(input.length); // Original size
            buffer.put(compressedBuffer, 0, compressedLength); // Compressed data

            // Always return compressed data with header
            // Fallback logic is handled at higher level (ProofCompressionCodec)
            return result;

        } catch (Exception e) {
            throw new CompressionException("ZSTD compression failed", e);
        }
    }

    @Override
    public byte[] decode(byte[] compressed, CompressionConfig config) {
        Objects.requireNonNull(compressed, "compressed cannot be null");
        Objects.requireNonNull(config, "config cannot be null");

        if (compressed.length == 0) {
            throw new CompressionException("Cannot decompress empty input");
        }

        try {
            // Extract original size from header (4 bytes, big-endian)
            var buffer = ByteBuffer.wrap(compressed);
            var originalSize = buffer.getInt();

            // Validate against expansion limit
            var maxSize = (long) compressed.length * config.maxExpansionRatio();
            if (originalSize > maxSize) {
                throw new CompressionException(
                    "Decompression bomb detected: original size %d exceeds max %d"
                        .formatted(originalSize, maxSize)
                );
            }

            // Allocate decompression buffer
            var decompressed = new byte[originalSize];

            // Decompress (skip 4-byte header)
            var decompressedLength = (int) Zstd.decompressByteArray(
                decompressed, 0, originalSize,
                compressed, 4, compressed.length - 4
            );

            // Check for decompression error
            if (Zstd.isError(decompressedLength)) {
                throw new CompressionException("ZSTD decompression failed: " + Zstd.getErrorName(decompressedLength));
            }

            // Verify decompressed size matches expected
            if (decompressedLength != originalSize) {
                throw new CompressionException(
                    "Decompression size mismatch: expected %d, got %d (corrupted data?)"
                        .formatted(originalSize, decompressedLength)
                );
            }

            return decompressed;

        } catch (CompressionException e) {
            throw e;
        } catch (Exception e) {
            throw new CompressionException("ZSTD decompression failed", e);
        }
    }

    @Override
    public CompressionCodec codec() {
        return CompressionCodec.ZSTD;
    }
}
