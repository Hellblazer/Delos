/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation.recursive;

/**
 * Compression codec for RecursiveAggregateReceipt storage optimization.
 * Phase 3.3 implementation using standard compression libraries.
 * <p>
 * Storage characteristics (10 epochs, 3 with changes, ~2.3KB baseline):
 * - NONE: ~2.3KB (no compression, fallback when compression increases size)
 * - LZ4: ~2.0-2.1KB (10-15% reduction, fast <10ms)
 * - ZSTD: ~1.8-2.0KB (15-20% reduction, balanced speed/ratio)
 * <p>
 * Compression targets are modest (10-20%) because BLS signatures (960 bytes)
 * and SHA-256 hashes (320 bytes) are cryptographic random data that doesn't compress.
 * Only metadata (~1020 bytes) compresses effectively (~30% reduction).
 * <p>
 * Thread-safe: Enum, inherently thread-safe.
 *
 * @author hal.hildebrand
 * @since Phase 3.3
 */
public enum CompressionCodec {
    /**
     * No compression - baseline storage (~2.3KB for 10 epochs).
     * Used when compression would increase size (fallback logic).
     */
    NONE(0),

    /**
     * LZ4 compression (fast, 10-15% reduction).
     * <p>
     * Characteristics:
     * - Performance: <10ms compression/decompression
     * - Compression ratio: 10-15% reduction
     * - Library: org.lz4:lz4-java:1.8.0
     * - Use case: Low-latency scenarios prioritizing speed
     */
    LZ4(1),

    /**
     * ZSTD compression (better compression, 15-20% reduction).
     * <p>
     * Characteristics:
     * - Performance: ~10-20ms compression/decompression
     * - Compression ratio: 15-20% reduction
     * - Library: com.github.luben:zstd-jni:1.5.6-1
     * - Use case: Storage-constrained scenarios prioritizing compression ratio
     */
    ZSTD(2),

    /**
     * RUN_LENGTH compression (optimized for consecutive unchanged epochs).
     * <p>
     * Characteristics:
     * - Target: 10-20% reduction for 7+ consecutive unchanged epochs
     * - Encoding: (count, base_epoch, hash_reference, signer_count, timestamp_delta)
     * - Use case: Long periods of stable committee composition
     * - Phase: 3.3.2
     */
    RUN_LENGTH(3),

    /**
     * DELTA_BITMAP compression (optimized for incremental committee changes).
     * <p>
     * Characteristics:
     * - Target: 5-10% reduction per changed epoch with minimal bitmap differences
     * - Encoding: XOR delta with sparse representation
     * - First bitmap stored in full, subsequent as XOR differences
     * - Use case: Gradual committee membership changes
     * - Phase: 3.3.3
     */
    DELTA_BITMAP(4),

    /**
     * HYBRID compression (adaptive strategy selection).
     * <p>
     * Characteristics:
     * - Combines RUN_LENGTH and DELTA_BITMAP dynamically
     * - Target: 10-30% combined reduction for mixed workloads
     * - Use case: Variable committee change patterns
     * - Phase: 3.3.4
     */
    HYBRID(5);

    private final int protoValue;

    CompressionCodec(int protoValue) {
        this.protoValue = protoValue;
    }

    /**
     * Get the proto enum value for serialization.
     *
     * @return Proto CompressionCodec enum value
     */
    public com.hellblazer.delos.witness.proto.CompressionCodec toProto() {
        return com.hellblazer.delos.witness.proto.CompressionCodec.forNumber(protoValue);
    }

    /**
     * Create from proto enum value.
     *
     * @param proto Proto CompressionCodec enum
     * @return Corresponding Java enum value
     * @throws IllegalArgumentException if proto value is invalid
     */
    public static CompressionCodec from(com.hellblazer.delos.witness.proto.CompressionCodec proto) {
        if (proto == null || proto.getNumber() < 0 || proto.getNumber() >= values().length) {
            throw new IllegalArgumentException("Invalid proto CompressionCodec: " + proto);
        }
        return values()[proto.getNumber()];
    }

    /**
     * Get the proto numeric value.
     *
     * @return Proto enum number
     */
    public int getProtoValue() {
        return protoValue;
    }

    /**
     * Check if this codec is implemented.
     *
     * @return true if implemented
     */
    public boolean isImplemented() {
        return switch (this) {
            case NONE, LZ4, ZSTD, RUN_LENGTH, DELTA_BITMAP -> true;
            case HYBRID -> false; // Phase 3.3.4
        };
    }
}
