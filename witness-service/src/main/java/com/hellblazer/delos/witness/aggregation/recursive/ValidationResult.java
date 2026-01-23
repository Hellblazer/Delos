/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation.recursive;

import java.util.Optional;

/**
 * Validation result for chain and integrity checks.
 * <p>
 * Sealed interface representing the outcome of validation operations on
 * recursive aggregation chains and epoch links. Used to verify:
 * - Chain integrity (sequential epochs, cryptographic linking)
 * - Structural consistency (epoch ranges, signer counts)
 * - Cryptographic validity (signature hashes, previous root hashes)
 * <p>
 * Thread-safe: Immutable sealed interface with immutable implementations.
 *
 * @author hal.hildebrand
 * @since Phase 1C-2-B
 */
public sealed interface ValidationResult
    permits ValidationResult.Valid, ValidationResult.Invalid {

    /**
     * Validation succeeded - no errors detected.
     */
    record Valid() implements ValidationResult {}

    /**
     * Validation failed with specific reason.
     *
     * @param reason Description of validation failure
     */
    record Invalid(String reason) implements ValidationResult {
        public Invalid {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("Reason cannot be null or blank");
            }
        }
    }

    /**
     * Create a valid result.
     *
     * @return Valid validation result
     */
    static ValidationResult valid() {
        return new Valid();
    }

    /**
     * Create an invalid result with formatted reason.
     *
     * @param format Format string (printf-style)
     * @param args   Format arguments
     * @return Invalid validation result
     */
    static ValidationResult invalid(String format, Object... args) {
        return new Invalid(format.formatted(args));
    }

    /**
     * Check if validation succeeded.
     *
     * @return true if valid, false if invalid
     */
    default boolean isValid() {
        return this instanceof Valid;
    }

    /**
     * Get failure reason if invalid.
     *
     * @return Optional reason string (empty if valid)
     */
    default Optional<String> getFailureReason() {
        return this instanceof Invalid i ? Optional.of(i.reason()) : Optional.empty();
    }
}
