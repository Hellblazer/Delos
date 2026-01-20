/*
 * Copyright (c) 2024 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.delos.witness.migration;

import com.hellblazer.delos.witness.aggregation.SignatureFormat;

import java.util.Objects;

/**
 * Sealed result type for compatibility layer validation.
 * Enables exhaustive pattern matching in switch expressions.
 * <p>
 * Represents all possible outcomes of signature format validation during
 * the Ed25519 to BLS migration. The sealed interface ensures compile-time
 * exhaustiveness checking when pattern matching.
 *
 * @author hal.hildebrand
 */
public sealed interface CompatibilityResult {

    /**
     * Receipt validation successful.
     *
     * @param format      Signature format that was validated
     * @param signerCount Number of valid signers
     * @param threshold   Required signature threshold
     */
    record Valid(
        SignatureFormat format,
        int signerCount,
        int threshold
    ) implements CompatibilityResult {
        public Valid {
            Objects.requireNonNull(format, "format cannot be null");
            if (signerCount < 0) {
                throw new IllegalArgumentException("signerCount must be >= 0");
            }
            if (threshold < 1) {
                throw new IllegalArgumentException("threshold must be >= 1");
            }
        }

        /**
         * Check if signature threshold is met.
         *
         * @return true if signerCount >= threshold
         */
        public boolean thresholdMet() {
            return signerCount >= threshold;
        }
    }

    /**
     * BLS validation failed, fallback may be available.
     *
     * @param reason      Failure reason description
     * @param hasFallback True if Ed25519 fallback can be attempted
     */
    record BlsValidationFailed(
        String reason,
        boolean hasFallback
    ) implements CompatibilityResult {
        public BlsValidationFailed {
            Objects.requireNonNull(reason, "reason cannot be null");
        }
    }

    /**
     * Ed25519 validation failed.
     *
     * @param reason Failure reason description
     */
    record Ed25519ValidationFailed(
        String reason
    ) implements CompatibilityResult {
        public Ed25519ValidationFailed {
            Objects.requireNonNull(reason, "reason cannot be null");
        }
    }

    /**
     * Format not supported in current migration phase.
     *
     * @param format       Detected signature format
     * @param currentPhase Current migration phase
     * @param message      Descriptive message
     */
    record FormatNotSupported(
        SignatureFormat format,
        MigrationPhase currentPhase,
        String message
    ) implements CompatibilityResult {
        public FormatNotSupported {
            Objects.requireNonNull(format, "format cannot be null");
            Objects.requireNonNull(currentPhase, "currentPhase cannot be null");
            Objects.requireNonNull(message, "message cannot be null");
        }
    }

    /**
     * Receipt contains both Ed25519 and BLS signatures (invalid).
     *
     * @param reason Description of the mixing error
     */
    record MixedFormatError(
        String reason
    ) implements CompatibilityResult {
        public MixedFormatError {
            Objects.requireNonNull(reason, "reason cannot be null");
        }
    }

    /**
     * Format could not be detected from receipt.
     *
     * @param reason Description of detection failure
     */
    record UnknownFormat(
        String reason
    ) implements CompatibilityResult {
        public UnknownFormat {
            Objects.requireNonNull(reason, "reason cannot be null");
        }
    }
}
