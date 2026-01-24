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
 * Functional interface for retrieving deprecated BLS public keys during key rotation grace period.
 * <p>
 * During key rotation, both old (deprecated) and new (active) keys must be accepted for a
 * configurable grace period to enable zero-downtime transitions. This interface provides
 * access to keys that have been rotated out but remain valid.
 * <p>
 * <strong>Grace Period Semantics</strong>:
 * <ul>
 *   <li><strong>Active keys</strong>: New keys after rotation (primary verification target)</li>
 *   <li><strong>Deprecated keys</strong>: Old keys within grace period (fallback verification)</li>
 *   <li><strong>Expired keys</strong>: Old keys beyond grace period (rejected)</li>
 * </ul>
 * <p>
 * <strong>Usage Pattern</strong>:
 * <pre>{@code
 * // Define grace period lookup (e.g., from BLSKeyRotationManager)
 * GracePeriodKeyLookup graceLookup = (epoch, committee) -> {
 *     var now = Instant.now();
 *     return rotationManager.getValidKeys(committee, now)
 *         .stream()
 *         .filter(k -> k.status() == KeyStatus.DEPRECATED)
 *         .map(KeyEntry::publicKey)
 *         .toList();
 * };
 *
 * // Use in recursive proof validator
 * var validator = new RecursiveProofValidator(keyResolver, graceLookup);
 *
 * // Verification flow:
 * // 1. Try active keys from keyResolver.getCommitteeKeys(epoch, committee)
 * // 2. If verification fails, try graceLookup.getDeprecatedKeys(epoch, committee)
 * // 3. Accept signature if either set validates
 * }</pre>
 * <p>
 * <strong>Key Rotation Timeline</strong>:
 * <pre>
 * Time:        T0              T1                    T2
 *              |               |                     |
 * Old Key:     ACTIVE -------> DEPRECATED ---------> EXPIRED
 * New Key:     -               ACTIVE --------------> ACTIVE
 *              |               |                     |
 *              Key Rotation    Grace Period Ends
 *              Event           (T1 + grace_duration)
 * </pre>
 * <p>
 * <strong>Implementation Requirements</strong>:
 * <ul>
 *   <li>Return empty list if no deprecated keys exist or grace period expired</li>
 *   <li>Must be thread-safe if used in concurrent verification contexts</li>
 *   <li>Should cache grace period state to avoid repeated timestamp checks</li>
 *   <li>Grace period duration typically configured (e.g., 1 hour, 24 hours)</li>
 * </ul>
 * <p>
 * <strong>Typical Implementations</strong>:
 * <ul>
 *   <li><strong>BLSKeyRotationManager</strong>: Production grace period tracking</li>
 *   <li><strong>Static test lookup</strong>: Fixed deprecated keys for testing</li>
 *   <li><strong>No-op lookup</strong>: Empty list (disables grace period)</li>
 * </ul>
 *
 * @see RecursiveKeyResolver
 * @see RecursiveProofValidator
 */
@FunctionalInterface
public interface GracePeriodKeyLookup {

    /**
     * Retrieve deprecated BLS public keys that are still valid during grace period.
     * <p>
     * Returns keys that were rotated out but remain acceptable for verification
     * because they are within the configured grace period window. After the grace
     * period expires, implementations must return an empty list.
     * <p>
     * <strong>Return Value Semantics</strong>:
     * <ul>
     *   <li><strong>Non-empty list</strong>: Deprecated keys within grace period</li>
     *   <li><strong>Empty list</strong>: No rotation occurred, or grace period expired</li>
     *   <li><strong>Never null</strong>: Always return a list (possibly empty)</li>
     * </ul>
     * <p>
     * <strong>Example Implementation</strong>:
     * <pre>{@code
     * // Using BLSKeyRotationManager
     * GracePeriodKeyLookup lookup = (epoch, committee) -> {
     *     var now = Instant.now();
     *     var committeeId = CommitteeId.of(epoch, committee);
     *     return rotationManager.getValidKeys(committeeId, now).stream()
     *         .filter(entry -> entry.status() == KeyStatus.DEPRECATED)
     *         .map(KeyEntry::publicKey)
     *         .toList();
     * };
     * }</pre>
     *
     * @param epochNumber    The epoch number for grace period check
     * @param committeeIndex The committee index within the epoch (0-based)
     * @return List of deprecated BLS public keys still valid, or empty list if none
     * @throws IllegalArgumentException if epochNumber < 0 or committeeIndex < 0
     * @implSpec Implementation must enforce grace period expiration and never return null
     */
    List<BLSPublicKey> getDeprecatedKeys(long epochNumber, int committeeIndex);
}
