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
 * Phase 2 implementation will add DELTA_BITMAP, RUN_LENGTH, HYBRID codecs.
 * <p>
 * Storage characteristics (10 epochs, 3 with changes):
 * - NONE: ~2.3KB baseline (no compression)
 * - DELTA_BITMAP: ~1.5KB (stores only changed committees) - Phase 2
 * - RUN_LENGTH: ~1.2KB (encodes consecutive unchanged epochs) - Phase 2
 * - HYBRID: ~1.0KB (combines both approaches optimally) - Phase 2
 * <p>
 * Thread-safe: Enum, inherently thread-safe.
 *
 * @author hal.hildebrand
 * @since Phase 1C-2-B
 */
public enum CompressionCodec {
    /**
     * No compression - baseline storage (~2.3KB for 10 epochs).
     * Used in Phase 1 for straightforward serialization.
     */
    NONE(0),

    /**
     * Store only changed committees between epochs (Phase 2).
     * Reduces storage for scenarios with infrequent committee changes.
     * Expected reduction: ~35% for typical workloads.
     */
    DELTA_BITMAP(1),

    /**
     * Encode consecutive unchanged epochs with run-length encoding (Phase 2).
     * Optimal for scenarios with long stability periods.
     * Expected reduction: ~50% for stable membership.
     */
    RUN_LENGTH(2),

    /**
     * Combine DELTA_BITMAP and RUN_LENGTH for optimal compression (Phase 2).
     * Adapts to workload characteristics dynamically.
     * Expected reduction: ~55-60% for mixed workloads.
     */
    HYBRID(3);

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
     * Check if this codec is implemented (Phase 1 vs Phase 2).
     *
     * @return true if implemented, false if Phase 2 placeholder
     */
    public boolean isImplemented() {
        return this == NONE;  // Phase 1: only NONE is implemented
    }
}
