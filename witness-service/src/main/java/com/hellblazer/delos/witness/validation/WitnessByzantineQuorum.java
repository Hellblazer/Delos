/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.validation;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;

import java.util.BitSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Byzantine quorum enforcement for M-of-N witness receipts.
 * <p>
 * Implements Byzantine fault-tolerant threshold: M > (2k/3) where k = committee size.
 * This tolerates up to f = (k-1)/3 Byzantine witnesses.
 * </p>
 * <p>
 * Example: k=7 witnesses → M > (14/3) = 5 signatures required → tolerates f=2 Byzantine
 * </p>
 * <p>
 * Features:
 * - Calculates Byzantine threshold from committee size
 * - Tracks signature collection with duplicate detection
 * - Manages state transitions: COLLECTING → THRESHOLD_MET → COMPLETED
 * - Handles timeouts and view changes gracefully
 * </p>
 * <p>
 * Thread-safe: Uses concurrent collections for signature tracking.
 * </p>
 */
public interface WitnessByzantineQuorum {

    /**
     * Calculate Byzantine threshold M for committee size k.
     * <p>
     * Formula: M = floor(2*k/3) + 1
     * </p>
     * <p>
     * Examples:
     * - k=4 → M=3 (tolerates f=1)
     * - k=7 → M=5 (tolerates f=2)
     * - k=10 → M=7 (tolerates f=3)
     * </p>
     *
     * @param committeeSize k (number of witnesses in committee)
     * @return M (minimum signatures for Byzantine threshold)
     * @throws IllegalArgumentException if committeeSize < 1
     */
    static int calculateThreshold(int committeeSize) {
        if (committeeSize < 1) {
            throw new IllegalArgumentException("Committee size must be at least 1, got: " + committeeSize);
        }
        return (2 * committeeSize) / 3 + 1;
    }

    /**
     * Calculate Byzantine fault tolerance level for committee size.
     * <p>
     * Formula: f = floor((k-1)/3)
     * </p>
     *
     * @param committeeSize k (number of witnesses)
     * @return f (maximum Byzantine failures tolerated)
     */
    static int faultToleranceLevel(int committeeSize) {
        return (committeeSize - 1) / 3;
    }

    /**
     * Signature collection tracker for single event.
     * <p>
     * Tracks signatures until Byzantine threshold achieved or timeout occurs.
     * </p>
     */
    final class ThresholdTracker {
        private final EventCoordinates event;
        private final int threshold;
        private final Set<Identifier> signers;
        private final BitSet signerBitmap;
        private ThresholdStatus status;

        /**
         * Create threshold tracker for event.
         *
         * @param event     Event being witnessed
         * @param threshold Byzantine threshold (M)
         */
        public ThresholdTracker(EventCoordinates event, int threshold) {
            this.event = event;
            this.threshold = threshold;
            this.signers = ConcurrentHashMap.newKeySet();
            this.signerBitmap = new BitSet();
            this.status = ThresholdStatus.COLLECTING;
        }

        /**
         * Add verified signature to collection.
         * <p>
         * Returns AddSignatureResult:
         * - Accepted: Signature added successfully
         * - Duplicate: Witness already signed (rejected)
         * - ThresholdMet: This signature completed threshold
         * - Frozen: Collection frozen (view change or timeout)
         * </p>
         *
         * @param witnessId    Witness identifier
         * @param witnessIndex Index in committee (0-based, for bitmap)
         * @param signature    Signature digest
         * @return Result of signature addition
         */
        public synchronized AddSignatureResult addSignature(Identifier witnessId, int witnessIndex, Digest signature) {
            // Check collection state
            if (status == ThresholdStatus.FROZEN || status == ThresholdStatus.COMPLETED) {
                return new Frozen(status);
            }

            // Check for duplicate signer
            if (signers.contains(witnessId)) {
                return new Duplicate(witnessId);
            }

            // Add signature
            signers.add(witnessId);
            signerBitmap.set(witnessIndex);

            // Check if threshold met
            if (signers.size() == threshold) {
                // First time hitting threshold
                status = ThresholdStatus.THRESHOLD_MET;
                return new ThresholdMet(signers.size(), threshold);
            } else if (signers.size() > threshold) {
                // Already exceeded threshold
                return new ThresholdMet(signers.size(), threshold);
            }

            return new Accepted(signers.size(), threshold);
        }

        /**
         * Freeze collection (for timeout or view change).
         * Prevents further signature additions.
         */
        public synchronized void freeze() {
            if (status == ThresholdStatus.COLLECTING) {
                status = ThresholdStatus.FROZEN;
            }
        }

        /**
         * Mark collection as complete (receipt certified).
         */
        public synchronized void complete() {
            status = ThresholdStatus.COMPLETED;
        }

        /**
         * Get current signature count.
         */
        public synchronized int signatureCount() {
            return signers.size();
        }

        /**
         * Check if threshold achieved.
         */
        public synchronized boolean isThresholdMet() {
            return signers.size() >= threshold;
        }

        /**
         * Get current collection status.
         */
        public synchronized ThresholdStatus status() {
            return status;
        }

        /**
         * Get set of signers (witness identifiers).
         */
        public synchronized Set<Identifier> getSigners() {
            return Set.copyOf(signers);
        }

        /**
         * Get signer bitmap (which committee members signed).
         */
        public synchronized BitSet getSignerBitmap() {
            return (BitSet) signerBitmap.clone();
        }

        /**
         * Get event coordinates.
         */
        public EventCoordinates event() {
            return event;
        }

        /**
         * Get threshold (M).
         */
        public int threshold() {
            return threshold;
        }
    }

    /**
     * Collection status enum.
     */
    enum ThresholdStatus {
        /**
         * Actively collecting signatures (M not yet reached)
         */
        COLLECTING,

        /**
         * Byzantine threshold achieved (M signatures collected)
         */
        THRESHOLD_MET,

        /**
         * Collection frozen (timeout or view change)
         */
        FROZEN,

        /**
         * Collection completed (receipt certified)
         */
        COMPLETED
    }

    /**
     * Sealed interface for signature addition result.
     */
    sealed interface AddSignatureResult {
    }

    /**
     * Signature accepted, collection ongoing.
     */
    record Accepted(int currentCount, int threshold) implements AddSignatureResult {
    }

    /**
     * Threshold met with this signature.
     */
    record ThresholdMet(int finalCount, int threshold) implements AddSignatureResult {
    }

    /**
     * Duplicate signature from same witness (rejected).
     */
    record Duplicate(Identifier witnessId) implements AddSignatureResult {
    }

    /**
     * Collection frozen, signature rejected.
     */
    record Frozen(ThresholdStatus status) implements AddSignatureResult {
    }
}
