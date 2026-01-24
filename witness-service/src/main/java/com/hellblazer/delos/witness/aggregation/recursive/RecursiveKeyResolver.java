/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation.recursive;

import com.hellblazer.delos.cryptography.bls.BLSPublicKey;

import java.util.List;

/**
 * Abstraction for accessing committee BLS public keys at different epochs for recursive proof verification.
 * <p>
 * Provides key resolution for verifying:
 * <ul>
 *   <li><strong>Receipt aggregates</strong>: Committee keys at receipt's epoch</li>
 *   <li><strong>Epoch links</strong>: Committee keys across sequential epochs in chain</li>
 *   <li><strong>Path nodes</strong>: Committee keys for intermediate aggregation nodes</li>
 * </ul>
 * <p>
 * <strong>Key Rotation Grace Period</strong>:
 * During key rotation, both active (new) and deprecated (old) keys must be accepted
 * for a grace period to enable zero-downtime transitions. Use {@link #getDeprecatedKeys}
 * to retrieve keys that are still valid but no longer primary.
 * <p>
 * <strong>Usage Pattern</strong>:
 * <pre>{@code
 * // Verify receipt with active committee keys
 * var keys = resolver.getCommitteeKeys(epochNumber, committeeIndex);
 * var verified = BLSOperations.verifyAggregate(keys, message, signature);
 *
 * // During grace period, try deprecated keys if active verification fails
 * if (!verified && inGracePeriod) {
 *     var deprecatedKeys = resolver.getDeprecatedKeys(epochNumber, committeeIndex);
 *     verified = BLSOperations.verifyAggregate(deprecatedKeys, message, signature);
 * }
 *
 * // Check key validity
 * if (!resolver.isKeyValid(candidateKey, epochNumber)) {
 *     throw new IllegalStateException("Key expired or not yet active");
 * }
 * }</pre>
 * <p>
 * <strong>Implementation Requirements</strong>:
 * <ul>
 *   <li>Implementations must be stateless or thread-safe (called from concurrent verification threads)</li>
 *   <li>Key lookups should be efficient (consider caching if backed by storage)</li>
 *   <li>Return empty list (not null) if no keys available for epoch/committee</li>
 *   <li>Deprecated keys must be returned only if still within grace period</li>
 * </ul>
 * <p>
 * <strong>Typical Implementations</strong>:
 * <ul>
 *   <li><strong>Cached resolver</strong>: Pre-computed key sets for recent epochs</li>
 *   <li><strong>On-demand resolver</strong>: Queries committee state from storage</li>
 *   <li><strong>Static resolver</strong>: Fixed key mapping for testing</li>
 * </ul>
 *
 * @see RecursiveProofValidator
 * @see GracePeriodKeyLookup
 */
public interface RecursiveKeyResolver {

    /**
     * Retrieve active committee BLS public keys for a specific epoch and committee.
     * <p>
     * Returns the current (active) keys that should be used for verifying signatures
     * at the specified epoch. During key rotation grace periods, these are the NEW keys
     * that are now authoritative.
     * <p>
     * This method is used for:
     * <ul>
     *   <li>Verifying receipt aggregates at receipt epoch</li>
     *   <li>Validating epoch link signatures</li>
     *   <li>Checking tree node aggregates in recursive proofs</li>
     * </ul>
     *
     * @param epochNumber    The epoch number for key resolution (monotonically increasing)
     * @param committeeIndex The committee index within the epoch (0-based)
     * @return List of active BLS public keys for the committee, or empty list if none available
     * @throws IllegalArgumentException if epochNumber < 0 or committeeIndex < 0
     * @implSpec Implementation must be thread-safe and return consistent results for same inputs
     * @implNote For efficiency, implementations should cache recent epochs
     */
    List<BLSPublicKey> getCommitteeKeys(long epochNumber, int committeeIndex);

    /**
     * Retrieve deprecated BLS public keys that are still valid during grace period.
     * <p>
     * During key rotation, old keys must remain accepted for a grace period to enable
     * zero-downtime transitions. This method returns keys that were recently rotated
     * out but are still considered valid for the specified epoch.
     * <p>
     * Returns empty list if:
     * <ul>
     *   <li>No key rotation occurred recently</li>
     *   <li>Grace period has expired</li>
     *   <li>Committee never had deprecated keys</li>
     * </ul>
     * <p>
     * <strong>Verification Pattern</strong>:
     * <pre>{@code
     * // Try active keys first
     * if (!verifyWithActiveKeys(...)) {
     *     // Fall back to deprecated keys during grace period
     *     var deprecated = resolver.getDeprecatedKeys(epoch, committee);
     *     if (!deprecated.isEmpty()) {
     *         verifyWithDeprecatedKeys(deprecated, ...);
     *     }
     * }
     * }</pre>
     *
     * @param epochNumber    The epoch number for grace period check
     * @param committeeIndex The committee index within the epoch (0-based)
     * @return List of deprecated BLS public keys still valid, or empty list if none
     * @throws IllegalArgumentException if epochNumber < 0 or committeeIndex < 0
     * @implSpec Implementation must enforce grace period expiration and return empty after expiry
     * @see GracePeriodKeyLookup
     */
    List<BLSPublicKey> getDeprecatedKeys(long epochNumber, int committeeIndex);

    /**
     * Check if a BLS public key is valid (active or in grace period) at the specified epoch.
     * <p>
     * A key is considered valid if it is either:
     * <ul>
     *   <li>An active key for any committee at this epoch</li>
     *   <li>A deprecated key still within grace period</li>
     * </ul>
     * <p>
     * This method is useful for:
     * <ul>
     *   <li>Pre-filtering candidate keys before expensive signature verification</li>
     *   <li>Validating key membership in committee sets</li>
     *   <li>Checking if received keys are plausibly authentic</li>
     * </ul>
     * <p>
     * <strong>Usage Pattern</strong>:
     * <pre>{@code
     * // Filter valid keys before verification
     * var candidateKeys = extractKeysFromReceipt(receipt);
     * var validKeys = candidateKeys.stream()
     *     .filter(key -> resolver.isKeyValid(key, epochNumber))
     *     .toList();
     *
     * if (validKeys.isEmpty()) {
     *     throw new InvalidReceiptException("No valid keys found");
     * }
     * }</pre>
     *
     * @param key         The BLS public key to check
     * @param epochNumber The epoch number for validity check
     * @return true if key is active or in grace period at this epoch, false otherwise
     * @throws NullPointerException     if key is null
     * @throws IllegalArgumentException if epochNumber < 0
     * @implSpec Implementation must check both active and deprecated key sets
     */
    boolean isKeyValid(BLSPublicKey key, long epochNumber);
}
