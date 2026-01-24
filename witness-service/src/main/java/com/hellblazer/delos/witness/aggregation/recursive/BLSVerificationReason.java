/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation.recursive;

/**
 * Enumeration of specific BLS cryptographic verification failure reasons.
 * <p>
 * Used in {@link ValidationResult.VerificationFailure} to distinguish between
 * different types of cryptographic verification failures. Enables callers to
 * implement sophisticated retry and error handling logic based on failure type.
 * <p>
 * Example usage:
 * <pre>{@code
 * ValidationResult result = validator.verify(signature, message, epoch);
 * if (result instanceof ValidationResult.VerificationFailure failure) {
 *     switch (failure.reason()) {
 *         case AGGREGATE_MISMATCH -> log.warn("Signature invalid for epoch {}", epoch);
 *         case GRACE_PERIOD_EXPIRED -> log.info("Cannot use deprecated keys, grace period ended");
 *         case KEY_RESOLUTION_FAILED -> log.error("Key resolution succeeded but verification failed");
 *         case OTHER -> log.error("Unknown verification failure");
 *     }
 * }
 * }</pre>
 *
 * @author hal.hildebrand
 * @since Phase 3.1 (Delos-4001)
 */
public enum BLSVerificationReason {
    /**
     * Aggregate signature does not match the message and committee public keys.
     * <p>
     * This indicates the BLS pairing verification failed, meaning:
     * - The signature was not created by the claimed committee keys, OR
     * - The message being verified differs from what was signed, OR
     * - The signature has been corrupted or tampered with
     * <p>
     * This is the most common verification failure and typically indicates
     * a Byzantine fault or data corruption. Should NOT be retried.
     */
    AGGREGATE_MISMATCH,

    /**
     * Attempted verification with deprecated keys but the grace period has expired.
     * <p>
     * During key rotation, there is a grace period where both current and previous
     * keys are accepted for verification. This reason indicates:
     * - Current keys failed verification
     * - Fallback to deprecated keys was attempted
     * - The grace period timestamp has passed
     * <p>
     * Typically occurs when processing old aggregates after rotation completes.
     * May be retryable if keys are updated.
     */
    GRACE_PERIOD_EXPIRED,

    /**
     * Key resolution succeeded but signature verification still failed.
     * <p>
     * This indicates a subtle failure where:
     * - {@link RecursiveKeyResolver} successfully returned committee keys
     * - The keys appeared valid (non-null, correct format)
     * - BLS pairing verification failed anyway
     * <p>
     * May indicate:
     * - Incorrect key-to-committee mapping
     * - Key deserialization succeeded but produced invalid curve points
     * - Race condition during key updates
     * <p>
     * Should be logged for investigation. May be retryable.
     */
    KEY_RESOLUTION_FAILED,

    /**
     * Unknown or unclassified verification failure.
     * <p>
     * Used for verification failures that do not fit other categories:
     * - Unexpected exceptions during verification
     * - Internal BLS library errors
     * - Resource exhaustion during cryptographic operations
     * <p>
     * Should be logged with full context for debugging. Typically not retryable.
     */
    OTHER
}
