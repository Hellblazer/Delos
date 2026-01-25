/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.witness.aggregation.compression;

import com.google.protobuf.InvalidProtocolBufferException;
import com.hellblazer.delos.witness.proto.WitnessReceipt;
import com.hellblazer.delos.witness.receipt.AggregateWitnessReceipt;
import com.hellblazer.delos.witness.aggregation.recursive.compression.CompressionConfig;
import com.hellblazer.delos.witness.aggregation.recursive.compression.CompressionException;
import com.hellblazer.delos.witness.aggregation.recursive.compression.ProofCompressionCodec;

import java.util.Objects;

/**
 * Compressor for AggregateWitnessReceipt serialization and compression.
 * Integrates Protobuf serialization with ProofCompressionCodec.
 * <p>
 * Thread-safe: All operations are stateless.
 * Virtual thread compatible: No blocking operations.
 * <p>
 * Features:
 * - Serialize AggregateWitnessReceipt to Protobuf bytes
 * - Compress with configured strategy (LZ4, ZSTD)
 * - Automatic codec detection during decompression
 * - Backward compatible with uncompressed data (codec=NONE)
 * - Zero-copy where possible
 * - Automatic fallback to NONE if compression increases size
 * <p>
 * Wire format:
 * [1-byte codec header][compressed protobuf bytes]
 * <p>
 * Typical compression ratios:
 * - LZ4: 10-15% reduction on receipt data
 * - ZSTD: 15-20% reduction on receipt data
 * - Best on repeated structures (stable committees, repeated signatures)
 * <p>
 * Usage:
 * <pre>
 * var compressor = new AggregateWitnessReceiptCompressor();
 * var compressed = compressor.toCompressedBytes(receipt, CompressionConfig.BEST);
 * var decompressed = compressor.fromCompressedBytes(compressed);
 * </pre>
 *
 * @author hal.hildebrand
 * @since Phase 3.4.4
 */
public class AggregateWitnessReceiptCompressor {

    private final ProofCompressionCodec codec;

    /**
     * Create compressor with default codec.
     */
    public AggregateWitnessReceiptCompressor() {
        this.codec = new ProofCompressionCodec();
    }

    /**
     * Create compressor with custom codec (for testing).
     */
    AggregateWitnessReceiptCompressor(ProofCompressionCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec cannot be null");
    }

    /**
     * Serialize and compress receipt.
     * If compression is ineffective (compressed size >= original), automatically
     * falls back to NONE codec.
     *
     * @param receipt AggregateWitnessReceipt to compress
     * @param config  Compression configuration
     * @return Compressed bytes with codec header
     * @throws CompressionException if compression fails
     */
    public byte[] toCompressedBytes(AggregateWitnessReceipt receipt, CompressionConfig config) {
        Objects.requireNonNull(receipt, "receipt cannot be null");
        Objects.requireNonNull(config, "config cannot be null");

        try {
            // Serialize to protobuf
            var protoBytes = receipt.toProto().toByteArray();

            // Compress with codec (includes automatic fallback logic)
            return codec.compress(protoBytes, config);

        } catch (Exception e) {
            throw new CompressionException("Failed to compress AggregateWitnessReceipt", e);
        }
    }

    /**
     * Decompress and deserialize receipt.
     * Auto-detects codec from header.
     *
     * @param compressed Compressed bytes with codec header
     * @return Deserialized AggregateWitnessReceipt
     * @throws CompressionException if decompression or deserialization fails
     */
    public AggregateWitnessReceipt fromCompressedBytes(byte[] compressed) {
        Objects.requireNonNull(compressed, "compressed cannot be null");

        try {
            // Decompress with automatic codec detection
            var protoBytes = codec.decompress(compressed);

            // Deserialize from protobuf
            var proto = WitnessReceipt.parseFrom(protoBytes);
            return AggregateWitnessReceipt.fromProto(proto);

        } catch (InvalidProtocolBufferException e) {
            throw new CompressionException("Failed to deserialize receipt: corrupted protobuf data", e);
        } catch (Exception e) {
            throw new CompressionException("Failed to decompress AggregateWitnessReceipt", e);
        }
    }

    /**
     * Get compression metrics for a receipt.
     * Useful for monitoring and tuning compression effectiveness.
     *
     * @param receipt AggregateWitnessReceipt to analyze
     * @param config  Compression configuration
     * @return Metrics showing compression effectiveness
     */
    public com.hellblazer.delos.witness.aggregation.recursive.compression.CompressionMetrics getMetrics(
        AggregateWitnessReceipt receipt, CompressionConfig config) {

        Objects.requireNonNull(receipt, "receipt cannot be null");
        Objects.requireNonNull(config, "config cannot be null");

        var original = receipt.toProto().toByteArray();

        var start = System.nanoTime();
        var compressed = toCompressedBytes(receipt, config);
        var elapsed = System.nanoTime() - start;

        // Check if fallback was applied (codec header shows NONE despite requesting compression)
        var fallbackApplied = config.codec() != com.hellblazer.delos.witness.proto.CompressionCodec.NONE &&
                              compressed[0] == (byte) com.hellblazer.delos.witness.proto.CompressionCodec.NONE.getNumber();

        return new com.hellblazer.delos.witness.aggregation.recursive.compression.CompressionMetrics(
            original.length,
            compressed.length,
            config.codec(),
            elapsed,
            fallbackApplied
        );
    }
}
