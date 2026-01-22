/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import com.hellblazer.delos.stereotomy.identifier.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Escalation handler for key rotation requests.
 * <p>
 * When a member exhibits persistent Byzantine behavior (score >= 0.85),
 * the orchestrator requests key rotation to replace potentially compromised keys.
 * </p>
 * <p>
 * This class:
 * - Tracks in-flight rotation requests (prevents duplicates)
 * - Provides interface for future integration with BLSKeyRotationManager
 * - Updates member state: QUARANTINED → KEY_ROTATING → NORMAL/SHUNNED
 * </p>
 * <p>
 * Thread-safe: uses concurrent collections for request tracking.
 * </p>
 *
 * @author hal.hildebrand
 */
public class KeyRotationEscalation {

    private static final Logger log = LoggerFactory.getLogger(KeyRotationEscalation.class);

    /**
     * Key rotation result.
     *
     * @param memberId Member whose keys were rotated
     * @param success  Whether rotation succeeded
     * @param reason   Reason for success/failure
     */
    public record KeyRotationResult(
        Identifier memberId,
        boolean success,
        String reason
    ) {
        public KeyRotationResult {
            Objects.requireNonNull(memberId, "memberId cannot be null");
        }
    }

    /**
     * Interface for triggering key rotation (to be implemented by BLSKeyRotationManager).
     */
    public interface KeyRotationTrigger {
        /**
         * Request key rotation for member.
         *
         * @param memberId Member whose keys should be rotated
         * @param reason   Reason for rotation request
         * @return Future completing with rotation result
         */
        CompletableFuture<KeyRotationResult> requestRotation(Identifier memberId, String reason);
    }

    private final Set<Identifier> inFlightRequests;
    private final KeyRotationTrigger rotationTrigger;

    public KeyRotationEscalation(KeyRotationTrigger rotationTrigger) {
        this.rotationTrigger = rotationTrigger;
        this.inFlightRequests = ConcurrentHashMap.newKeySet();
    }

    /**
     * Request key rotation for member.
     * <p>
     * Prevents duplicate requests for the same member.
     * Returns existing in-flight request if one exists.
     * </p>
     *
     * @param memberId Member whose keys should be rotated
     * @param reason   Reason for rotation
     * @return Future completing with rotation result
     */
    public CompletableFuture<KeyRotationResult> requestRotation(Identifier memberId, String reason) {
        Objects.requireNonNull(memberId, "memberId cannot be null");
        Objects.requireNonNull(reason, "reason cannot be null");

        // Check for duplicate request
        if (!inFlightRequests.add(memberId)) {
            log.info("Key rotation already in progress for {}, skipping duplicate request", memberId);
            return CompletableFuture.completedFuture(
                new KeyRotationResult(memberId, false, "Duplicate request (rotation already in progress)")
            );
        }

        log.info("Requesting key rotation for {}: {}", memberId, reason);

        // Delegate to rotation trigger
        if (rotationTrigger == null) {
            // No rotation trigger configured (testing or future integration)
            log.warn("No KeyRotationTrigger configured, rotation request for {} will be logged only", memberId);
            inFlightRequests.remove(memberId);
            return CompletableFuture.completedFuture(
                new KeyRotationResult(memberId, false, "No rotation trigger configured")
            );
        }

        return rotationTrigger.requestRotation(memberId, reason)
            .whenComplete((result, ex) -> {
                // Remove from in-flight after completion
                inFlightRequests.remove(memberId);

                if (ex != null) {
                    log.error("Key rotation failed for {}: {}", memberId, ex.getMessage(), ex);
                } else if (result.success()) {
                    log.info("Key rotation succeeded for {}: {}", memberId, result.reason());
                } else {
                    log.warn("Key rotation failed for {}: {}", memberId, result.reason());
                }
            });
    }

    /**
     * Check if rotation request is in flight for member.
     *
     * @param memberId Member to check
     * @return true if rotation in progress
     */
    public boolean isRotationInProgress(Identifier memberId) {
        return inFlightRequests.contains(memberId);
    }

    /**
     * Get count of in-flight rotation requests.
     *
     * @return Number of in-flight requests
     */
    public int getInFlightCount() {
        return inFlightRequests.size();
    }

    /**
     * Clear all in-flight requests (for testing or reset).
     */
    public void reset() {
        inFlightRequests.clear();
    }
}
