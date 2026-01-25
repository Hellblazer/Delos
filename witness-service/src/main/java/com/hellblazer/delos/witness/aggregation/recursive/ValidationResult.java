/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation.recursive;

import com.hellblazer.delos.cryptography.bls.BLSSignature;

import java.util.Optional;

/**
 * Validation result for chain and integrity checks.
 * <p>
 * Sealed interface representing the outcome of validation operations on
 * recursive aggregation chains and epoch links. Used to verify:
 * - Chain integrity (sequential epochs, cryptographic linking)
 * - Structural consistency (epoch ranges, signer counts)
 * - Cryptographic validity (signature hashes, previous root hashes)
 * - BLS signature verification (format, cryptographic correctness, key resolution)
 * <p>
 * Thread-safe: Immutable sealed interface with immutable implementations.
 *
 * @author hal.hildebrand
 * @since Phase 1C-2-B
 */
public sealed interface ValidationResult
    permits ValidationResult.Valid,
            ValidationResult.Invalid,
            ValidationResult.InvalidSignatureFormat,
            ValidationResult.VerificationFailure,
            ValidationResult.KeyResolutionFailure {

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
            if (reason == null) {
                throw new NullPointerException("reason cannot be null");
            }
            if (reason.isBlank()) {
                throw new IllegalArgumentException("reason cannot be blank");
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

    /**
     * BLS signature format or structure validation failure.
     * <p>
     * Represents failures in signature format validation before cryptographic verification:
     * - Invalid signature length (expected 96 bytes for BLS-12-381 G2 compressed)
     * - Null or malformed signature data
     * - Signature deserialization failures
     * - Invalid point encoding on curve
     * <p>
     * Example usage:
     * <pre>{@code
     * BLSSignature signature = // ... malformed signature with wrong length
     * return new InvalidSignatureFormat(
     *     "Signature length invalid: expected 96, got " + signature.getBytes().length,
     *     signature,
     *     epochNumber
     * );
     * }</pre>
     *
     * @param message      Error description explaining the format violation
     * @param signature    The problematic signature that failed format validation
     * @param epochNumber  Epoch context for debugging and logging
     */
    record InvalidSignatureFormat(
        String message,
        BLSSignature signature,
        long epochNumber
    ) implements ValidationResult {
        public InvalidSignatureFormat {
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("message cannot be null or blank");
            }
            if (signature == null) {
                throw new NullPointerException("signature cannot be null");
            }
        }
    }

    /**
     * BLS cryptographic verification failure.
     * <p>
     * Represents failures during cryptographic signature verification:
     * - Aggregate signature does not match message and committee keys
     * - Grace period for deprecated keys has expired
     * - Key resolution succeeded but verification still failed
     * - Other cryptographic verification failures
     * <p>
     * Example usage:
     * <pre>{@code
     * boolean verified = BLSOperations.verifyAggregate(signature, message, committeeKeys);
     * if (!verified) {
     *     return new VerificationFailure(
     *         "Aggregate signature verification failed for epoch " + epochNumber,
     *         committeeIndex,
     *         epochNumber,
     *         BLSVerificationReason.AGGREGATE_MISMATCH
     *     );
     * }
     * }</pre>
     *
     * @param message         Error description explaining the verification failure
     * @param committeeIndex  Committee identifier for debugging (0-based)
     * @param epochNumber     Epoch context for debugging and logging
     * @param reason          Specific reason for verification failure
     */
    record VerificationFailure(
        String message,
        int committeeIndex,
        long epochNumber,
        BLSVerificationReason reason
    ) implements ValidationResult {
        public VerificationFailure {
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("message cannot be null or blank");
            }
            if (committeeIndex < 0) {
                throw new IllegalArgumentException("committeeIndex must be >= 0");
            }
            if (reason == null) {
                throw new NullPointerException("reason cannot be null");
            }
        }
    }

    /**
     * Committee key resolution failure during BLS verification.
     * <p>
     * Represents failures when resolving committee public keys for signature verification:
     * - Committee keys not found for the specified epoch
     * - Deprecated keys attempted but grace period has expired
     * - RecursiveKeyResolver threw exception or returned null
     * - Grace period window closed before verification could complete
     * <p>
     * Example usage:
     * <pre>{@code
     * Optional<List<BLSPublicKey>> keys = keyResolver.resolveCommitteeKeys(epochNumber, committeeIndex);
     * if (keys.isEmpty()) {
     *     return new KeyResolutionFailure(
     *         "Keys not found for epoch " + epochNumber + ", committee " + committeeIndex,
     *         epochNumber,
     *         committeeIndex,
     *         KeyResolutionReason.KEYS_NOT_FOUND
     *     );
     * }
     * }</pre>
     *
     * @param message         Error description explaining the key resolution failure
     * @param epochNumber     Epoch context for debugging and logging
     * @param committeeIndex  Committee identifier for debugging (0-based)
     * @param reason          Specific reason for key resolution failure
     */
    record KeyResolutionFailure(
        String message,
        long epochNumber,
        int committeeIndex,
        KeyResolutionReason reason
    ) implements ValidationResult {
        public KeyResolutionFailure {
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("message cannot be null or blank");
            }
            if (committeeIndex < 0) {
                throw new IllegalArgumentException("committeeIndex must be >= 0");
            }
            if (reason == null) {
                throw new NullPointerException("reason cannot be null");
            }
        }
    }
}
