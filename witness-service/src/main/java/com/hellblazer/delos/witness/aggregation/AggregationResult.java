/*
 * Copyright (c) 2024 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.delos.witness.aggregation;

import com.hellblazer.delos.cryptography.bls.BLSSignature;

/**
 * Result of signature aggregation operation. Sealed type represents three possible outcomes:
 * successful aggregation, insufficient signatures, or aggregation failure.
 *
 * @author hal.hildebrand
 */
public sealed interface AggregationResult {

    /**
     * Successful aggregation with BLS signature and bitmap indicating participating members.
     *
     * @param signature Aggregated BLS signature
     * @param bitmap    Byte array bitmap indicating which members signed (1 = signed, 0 = not signed)
     */
    record Aggregated(BLSSignature signature, byte[] bitmap) implements AggregationResult {
        public Aggregated {
            if (signature == null) {
                throw new IllegalArgumentException("Signature cannot be null");
            }
            if (bitmap == null) {
                throw new IllegalArgumentException("Bitmap cannot be null");
            }
            if (bitmap.length == 0) {
                throw new IllegalArgumentException("Bitmap cannot be empty");
            }
        }
    }

    /**
     * Insufficient signatures collected to meet threshold requirement.
     *
     * @param current  Number of signatures currently collected
     * @param required Number of signatures required to meet threshold
     */
    record InsufficientSignatures(int current, int required) implements AggregationResult {
        public InsufficientSignatures {
            if (current < 0) {
                throw new IllegalArgumentException("Current count cannot be negative: " + current);
            }
            if (required <= 0) {
                throw new IllegalArgumentException("Required count must be positive: " + required);
            }
            if (current >= required) {
                throw new IllegalArgumentException(
                "Current (%d) should be less than required (%d)".formatted(current, required));
            }
        }
    }

    /**
     * Aggregation failed due to error condition.
     *
     * @param reason Description of why aggregation failed
     */
    record AggregationFailed(String reason) implements AggregationResult {
        public AggregationFailed {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("Failure reason cannot be null or blank");
            }
        }
    }
}
