/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation.recursive;

/**
 * Enumeration of committee key resolution failure reasons.
 * <p>
 * Used in {@link ValidationResult.KeyResolutionFailure} to distinguish between
 * different types of key resolution failures during BLS signature verification.
 * Enables callers to implement retry logic, fallback strategies, and appropriate
 * error handling based on the specific failure mode.
 * <p>
 * Example usage:
 * <pre>{@code
 * ValidationResult result = validator.verify(signature, message, epoch);
 * if (result instanceof ValidationResult.KeyResolutionFailure failure) {
 *     switch (failure.reason()) {
 *         case KEYS_NOT_FOUND -> {
 *             log.warn("Committee keys missing for epoch {}", failure.epochNumber());
 *             // May retry after key sync
 *         }
 *         case DEPRECATED_KEYS_EXPIRED -> {
 *             log.info("Grace period ended, cannot verify with old keys");
 *             // Should NOT retry, rotation completed
 *         }
 *         case KEY_RESOLVER_ERROR -> {
 *             log.error("Key resolver failed", failure.message());
 *             // May retry after resolver recovery
 *         }
 *         case GRACE_PERIOD_ENDED -> {
 *             log.info("Grace period window closed before verification");
 *             // Should NOT retry
 *         }
 *     }
 * }
 * }</pre>
 *
 * @author hal.hildebrand
 * @since Phase 3.1 (Delos-4001)
 */
public enum KeyResolutionReason {
    /**
     * Committee public keys not found for the requested epoch and committee index.
     * <p>
     * This indicates the {@link RecursiveKeyResolver} has no key mapping for:
     * - The specified epoch number, OR
     * - The specified committee index within that epoch
     * <p>
     * Common causes:
     * - Epoch not yet witnessed or aggregated
     * - Committee index out of bounds for epoch configuration
     * - Key sync lag (local node behind network state)
     * - Invalid epoch number (future epoch or before history start)
     * <p>
     * May be retryable after key synchronization completes.
     */
    KEYS_NOT_FOUND,

    /**
     * Attempted to use deprecated keys but the grace period has expired.
     * <p>
     * During key rotation, there is a grace period where both current and previous
     * epoch keys are accepted. This reason indicates:
     * - Current epoch keys were not found
     * - Fallback to previous epoch keys was attempted
     * - The grace period end timestamp has passed
     * <p>
     * Typically occurs when:
     * - Processing old recursive aggregates after rotation completes
     * - Attempting verification too long after epoch transition
     * - Grace period configured too short for network latency
     * <p>
     * Should NOT be retried - the grace period is intentionally expired.
     */
    DEPRECATED_KEYS_EXPIRED,

    /**
     * The RecursiveKeyResolver encountered an error during key resolution.
     * <p>
     * This indicates an internal failure in the key resolver:
     * - Exception thrown during key lookup
     * - Returned null instead of Optional.empty()
     * - Database/storage access failure
     * - Internal state corruption
     * <p>
     * This is distinct from KEYS_NOT_FOUND - the resolver failed to execute
     * the lookup operation itself, rather than successfully executing and
     * finding no keys.
     * <p>
     * May be retryable after resolver recovery or transient error resolution.
     * Should be logged for investigation.
     */
    KEY_RESOLVER_ERROR,

    /**
     * Grace period window closed before verification could complete.
     * <p>
     * This indicates a race condition where:
     * - Grace period was active when verification started
     * - Grace period expired while verification was in progress
     * - Verification attempted to complete after expiration
     * <p>
     * Common causes:
     * - High verification latency (cryptographic operations slow)
     * - Grace period configured too short
     * - System under heavy load causing delays
     * - Clock skew between validator and key rotation schedule
     * <p>
     * Should NOT be retried - the grace period window has closed.
     * May indicate need for grace period tuning or system optimization.
     */
    GRACE_PERIOD_ENDED
}
