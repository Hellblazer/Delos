/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.witness.aggregation.recursive.compression;

import com.hellblazer.delos.witness.proto.CompressionCodec;

import java.util.Map;
import java.util.Objects;

/**
 * Main compression codec for RecursiveAggregateReceipt proofs.
 * Provides unified API for multiple compression strategies with automatic fallback.
 * <p>
 * Thread-safe: Strategy registry is immutable, strategies are stateless.
 * Virtual thread compatible: No blocking operations.
 * <p>
 * Features:
 * - Multiple compression strategies (LZ4, ZSTD)
 * - Automatic codec detection during decompression (1-byte header)
 * - Fallback to uncompressed if compression increases size
 * - Decompression bomb protection (dynamic amplification limit)
 * - Zero-copy where possible
 * <p>
 * Wire format:
 * [1-byte codec header][compressed data with strategy-specific header]
 * <p>
 * Fallback logic:
 * - If compressed size >= original size, store uncompressed with NONE codec
 * - Prevents negative compression (storage overhead)
 * - Transparent to caller (decompress works regardless)
 * <p>
 * Security:
 * - Validates codec byte before decompression
 * - Enforces expansion limits via strategy configs
 * - Detects malformed data early
 *
 * @author hal.hildebrand
 * @since Phase 3.3.4
 */
public class ProofCompressionCodec {

    private final Map<CompressionCodec, CompressionStrategy> strategies;

    /**
     * Create codec with default strategies (LZ4, ZSTD, RUN_LENGTH, DELTA_BITMAP).
     */
    public ProofCompressionCodec() {
        this.strategies = Map.of(
            CompressionCodec.LZ4, new LZ4CompressionStrategy(),
            CompressionCodec.ZSTD, new ZSTDCompressionStrategy(),
            CompressionCodec.RUN_LENGTH, new RunLengthStrategy(),
            CompressionCodec.DELTA_BITMAP, new DeltaBitmapStrategy()
        );
    }

    /**
     * Compress data with specified config.
     * Adds 1-byte codec header.
     * Falls back to NONE if compression increases size (when config.enableFallback).
     *
     * @param input  Data to compress
     * @param config Compression configuration
     * @return Compressed data with codec header
     * @throws CompressionException if compression fails
     */
    public byte[] compress(byte[] input, CompressionConfig config) {
        Objects.requireNonNull(input, "input cannot be null");
        Objects.requireNonNull(config, "config cannot be null");

        if (input.length == 0) {
            throw new CompressionException("Cannot compress empty input");
        }

        // Handle NONE codec (no compression)
        if (config.codec() == CompressionCodec.NONE) {
            return encodeWithHeader(CompressionCodec.NONE, input);
        }

        // Get strategy for requested codec
        var strategy = strategies.get(config.codec());
        if (strategy == null) {
            throw new CompressionException("Unsupported codec: " + config.codec());
        }

        try {
            // Compress with strategy
            var compressed = strategy.encode(input, config);

            // Check if fallback is needed (compressed size >= original)
            if (config.enableFallback() && compressed.length >= input.length) {
                // Fallback to NONE (uncompressed)
                return encodeWithHeader(CompressionCodec.NONE, input);
            }

            // Return compressed data with codec header
            return encodeWithHeader(config.codec(), compressed);

        } catch (Exception e) {
            throw new CompressionException("Compression failed with codec " + config.codec(), e);
        }
    }

    /**
     * Decompress data with automatic codec detection.
     * Reads 1-byte codec header to determine strategy.
     *
     * @param compressed Compressed data with codec header
     * @return Original uncompressed data
     * @throws CompressionException if decompression fails
     */
    public byte[] decompress(byte[] compressed) {
        Objects.requireNonNull(compressed, "compressed cannot be null");

        if (compressed.length < 1) {
            throw new CompressionException("Cannot decompress: missing codec header");
        }

        try {
            // Read codec from first byte
            var codecByte = compressed[0];
            var codec = decodeCodec(codecByte);

            // Handle NONE codec (uncompressed)
            if (codec == CompressionCodec.NONE) {
                return decodeWithoutHeader(compressed);
            }

            // Get strategy for detected codec
            var strategy = strategies.get(codec);
            if (strategy == null) {
                throw new CompressionException("Unsupported codec: " + codec);
            }

            // Extract compressed data (skip 1-byte header)
            var compressedData = new byte[compressed.length - 1];
            System.arraycopy(compressed, 1, compressedData, 0, compressedData.length);

            // Decompress with strategy
            // Use a default config with safe expansion limit
            var config = new CompressionConfig(codec, 100, true, 0);
            return strategy.decode(compressedData, config);

        } catch (CompressionException e) {
            throw e;
        } catch (Exception e) {
            throw new CompressionException("Decompression failed", e);
        }
    }

    /**
     * Encode data with 1-byte codec header.
     */
    private byte[] encodeWithHeader(CompressionCodec codec, byte[] data) {
        var result = new byte[1 + data.length];
        result[0] = (byte) codec.getNumber();
        System.arraycopy(data, 0, result, 1, data.length);
        return result;
    }

    /**
     * Decode data by removing 1-byte codec header.
     */
    private byte[] decodeWithoutHeader(byte[] data) {
        var result = new byte[data.length - 1];
        System.arraycopy(data, 1, result, 0, result.length);
        return result;
    }

    /**
     * Decode codec from byte value.
     */
    private CompressionCodec decodeCodec(byte codecByte) {
        return switch (codecByte) {
            case 0 -> CompressionCodec.NONE;
            case 1 -> CompressionCodec.LZ4;
            case 2 -> CompressionCodec.ZSTD;
            case 3 -> CompressionCodec.RUN_LENGTH;
            case 4 -> CompressionCodec.DELTA_BITMAP;
            default -> throw new CompressionException("Invalid codec byte: " + codecByte);
        };
    }
}
