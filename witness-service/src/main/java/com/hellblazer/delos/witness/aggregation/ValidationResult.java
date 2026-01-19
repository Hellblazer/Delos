/*
 * Copyright (c) 2024 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.delos.witness.aggregation;

import com.hellblazer.delos.cryptography.bls.BLSAggregate;

/**
 * Result of aggregate signature validation. Sealed type represents validation outcomes:
 * valid aggregate, signature errors, bitmap errors, threshold mismatches, or general validation failures.
 *
 * @author hal.hildebrand
 */
public sealed interface ValidationResult {

    /**
     * Valid aggregate signature that passed all validation checks.
     *
     * @param aggregate The validated BLS aggregate
     */
    record Valid(BLSAggregate aggregate) implements ValidationResult {
        public Valid {
            if (aggregate == null) {
                throw new IllegalArgumentException("Aggregate cannot be null");
            }
        }
    }

    /**
     * Invalid signature detected for a specific member.
     *
     * @param member Member identifier that produced invalid signature
     * @param reason Description of why signature is invalid
     */
    record InvalidSignature(String member, String reason) implements ValidationResult {
        public InvalidSignature {
            if (member == null || member.isBlank()) {
                throw new IllegalArgumentException("Member cannot be null or blank");
            }
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("Reason cannot be null or blank");
            }
        }
    }

    /**
     * Invalid bitmap encoding detected.
     *
     * @param reason Description of why bitmap is invalid
     */
    record InvalidBitmap(String reason) implements ValidationResult {
        public InvalidBitmap {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("Reason cannot be null or blank");
            }
        }
    }

    /**
     * Threshold mismatch - wrong number of signers.
     *
     * @param expected Expected number of signers based on threshold
     * @param actual   Actual number of signers in aggregate
     */
    record InvalidThreshold(int expected, int actual) implements ValidationResult {
        public InvalidThreshold {
            if (expected <= 0) {
                throw new IllegalArgumentException("Expected threshold must be positive: " + expected);
            }
            if (actual < 0) {
                throw new IllegalArgumentException("Actual count cannot be negative: " + actual);
            }
            if (expected == actual) {
                throw new IllegalArgumentException(
                "Expected and actual should differ for invalid threshold: " + expected);
            }
        }
    }

    /**
     * General validation failure.
     *
     * @param reason Description of validation failure
     */
    record ValidationFailed(String reason) implements ValidationResult {
        public ValidationFailed {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("Reason cannot be null or blank");
            }
        }
    }
}
