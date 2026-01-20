/*
 * Copyright (c) 2024, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.witness.proto.ValidationStatus;
import com.hellblazer.delos.witness.proto.WitnessReceipt;

import java.time.Instant;
import java.util.Set;

/**
 * WitnessTransaction: Sealed interface for witness state machine transactions.
 *
 * Represents all state transitions in witness receipt collection lifecycle.
 * Used by CHOAM state machine for ordering and recovery.
 *
 * **Design Rationale**:
 * - Sealed: Exhaustive pattern matching for safety
 * - Record subtypes: Immutable, serializable data structures
 * - Deterministic ordering: Via CHOAM log ordering
 * - Recovery: All transactions replayed on startup
 *
 * **Transaction Lifecycle**:
 * 1. CollectionStart - Receipt collection initiated
 * 2. CollectionSignature - Witness signature received (not persisted yet)
 * 3. CollectionThreshold - M-of-N signatures achieved
 * 4. ReceiptPersist - Threshold receipt persisted
 * 5. CollectionComplete - Collection finalized (cleanup phase)
 *
 * **View Changes**:
 * - EpochChange - Fireflies epoch changed, new committee
 * - DrainStart - Drain period initiated
 * - DrainComplete - Drain period ended, safe to accept new collections
 *
 * **Cleanup**:
 * - ReceiptExpire - Receipt removed after timeout or age limit
 * - CollectionExpire - Expired collection removed from tracking
 */
public sealed interface WitnessTransaction permits
    WitnessTransaction.CollectionStart,
    WitnessTransaction.CollectionThreshold,
    WitnessTransaction.ReceiptPersist,
    WitnessTransaction.CollectionComplete,
    WitnessTransaction.EpochChange,
    WitnessTransaction.DrainStart,
    WitnessTransaction.DrainComplete,
    WitnessTransaction.ReceiptExpire,
    WitnessTransaction.CollectionExpire {

    /**
     * CollectionStart: Initiate receipt collection.
     * Emitted when SignEvent RPC is called.
     *
     * Records:
     * - collectionId: Unique ID for this collection
     * - eventCoordinates: Which KERI event to receipt
     * - threshold: M-of-N threshold (5 of 7)
     * - committeeSize: Committee cardinality (k=7)
     * - epoch: Current Fireflies epoch
     * - startTimeMs: Collection start time (unix millis)
     */
    record CollectionStart(
        String collectionId,
        EventCoordinates eventCoordinates,
        int threshold,
        int committeeSize,
        long epoch,
        long startTimeMs
    ) implements WitnessTransaction {
        @Override
        public String toString() {
            return String.format(
                "CollectionStart{id=%s, event=%s, threshold=%d/%d, epoch=%d, time=%d}",
                collectionId, eventCoordinates, threshold, committeeSize, epoch, startTimeMs
            );
        }
    }

    /**
     * CollectionThreshold: M-of-N signatures achieved.
     * Emitted when receipt collection reaches threshold.
     *
     * Records:
     * - collectionId: Which collection reached threshold
     * - eventCoordinates: Associated event
     * - receipt: Threshold receipt (immutable, ready to persist)
     * - signatureCount: Actual signatures received (>= threshold)
     * - thresholdTimeMs: Time when threshold achieved
     */
    record CollectionThreshold(
        String collectionId,
        EventCoordinates eventCoordinates,
        WitnessReceipt receipt,
        int signatureCount,
        long thresholdTimeMs
    ) implements WitnessTransaction {
        @Override
        public String toString() {
            return String.format(
                "CollectionThreshold{id=%s, event=%s, signatures=%d, time=%d}",
                collectionId, eventCoordinates, signatureCount, thresholdTimeMs
            );
        }
    }

    /**
     * ReceiptPersist: Persist receipt to CHOAM log.
     * Emitted when receipt persisted to durable storage.
     *
     * Records:
     * - eventCoordinates: Associated event
     * - receipt: The persisted receipt
     * - collectionId: Which collection produced this receipt
     * - persistTimeMs: Time when persisted
     * - epoch: Epoch during which persisted
     */
    record ReceiptPersist(
        EventCoordinates eventCoordinates,
        WitnessReceipt receipt,
        String collectionId,
        long persistTimeMs,
        long epoch
    ) implements WitnessTransaction {
        @Override
        public String toString() {
            return String.format(
                "ReceiptPersist{id=%s, event=%s, time=%d, epoch=%d}",
                collectionId, eventCoordinates, persistTimeMs, epoch
            );
        }
    }

    /**
     * CollectionComplete: Finalize collection and cleanup.
     * Emitted when collection reaches terminal state.
     *
     * Records:
     * - collectionId: Which collection is completing
     * - eventCoordinates: Associated event
     * - status: Final status (THRESHOLD_MET, TIMEOUT, INVALID)
     * - completeTimeMs: Time when completed
     */
    record CollectionComplete(
        String collectionId,
        EventCoordinates eventCoordinates,
        ValidationStatus status,
        long completeTimeMs
    ) implements WitnessTransaction {
        @Override
        public String toString() {
            return String.format(
                "CollectionComplete{id=%s, event=%s, status=%s, time=%d}",
                collectionId, eventCoordinates, status, completeTimeMs
            );
        }
    }

    /**
     * EpochChange: Fireflies epoch changed, new committee.
     * Emitted when view change received from Fireflies.
     *
     * Records:
     * - newEpoch: New Fireflies epoch
     * - oldEpoch: Previous epoch (for debugging)
     * - newCommittee: New committee member identifiers
     * - removedMembers: Members leaving committee
     * - drainPeriodMs: Drain period duration
     * - changeTimeMs: Time of epoch change
     */
    record EpochChange(
        long newEpoch,
        long oldEpoch,
        Set<Identifier> newCommittee,
        Set<Identifier> removedMembers,
        long drainPeriodMs,
        long changeTimeMs
    ) implements WitnessTransaction {
        @Override
        public String toString() {
            return String.format(
                "EpochChange{epoch=%d→%d, members=%d, drain=%dms, time=%d}",
                oldEpoch, newEpoch, newCommittee.size(), drainPeriodMs, changeTimeMs
            );
        }
    }

    /**
     * DrainStart: Begin drain period for in-flight collections.
     * Emitted when view change initiates drain.
     *
     * Records:
     * - epoch: Epoch during which drain started
     * - drainPeriodMs: Duration of drain period
     * - inFlightCount: Number of active collections at drain start
     * - startTimeMs: Drain period start time
     */
    record DrainStart(
        long epoch,
        long drainPeriodMs,
        int inFlightCount,
        long startTimeMs
    ) implements WitnessTransaction {
        @Override
        public String toString() {
            return String.format(
                "DrainStart{epoch=%d, drain=%dms, inflight=%d, time=%d}",
                epoch, drainPeriodMs, inFlightCount, startTimeMs
            );
        }
    }

    /**
     * DrainComplete: End drain period, safe to accept new collections.
     * Emitted when drain period expires.
     *
     * Records:
     * - epoch: Epoch during which drain completed
     * - completedCollections: Collections completed during drain
     * - timedOutCollections: Collections that timed out
     * - completeTimeMs: Drain period end time
     */
    record DrainComplete(
        long epoch,
        int completedCollections,
        int timedOutCollections,
        long completeTimeMs
    ) implements WitnessTransaction {
        @Override
        public String toString() {
            return String.format(
                "DrainComplete{epoch=%d, completed=%d, timedout=%d, time=%d}",
                epoch, completedCollections, timedOutCollections, completeTimeMs
            );
        }
    }

    /**
     * ReceiptExpire: Remove receipt from cache after timeout.
     * Emitted during periodic cleanup.
     *
     * Records:
     * - eventCoordinates: Associated event
     * - expireTimeMs: Time when removed
     * - ageMs: Receipt age in milliseconds
     * - reason: Expiration reason ("timeout", "aged", "orphaned")
     */
    record ReceiptExpire(
        EventCoordinates eventCoordinates,
        long expireTimeMs,
        long ageMs,
        String reason
    ) implements WitnessTransaction {
        @Override
        public String toString() {
            return String.format(
                "ReceiptExpire{event=%s, age=%dms, reason=%s, time=%d}",
                eventCoordinates, ageMs, reason, expireTimeMs
            );
        }
    }

    /**
     * CollectionExpire: Remove collection from tracking after timeout.
     * Emitted during periodic cleanup when collection exceeds age limit.
     *
     * Records:
     * - collectionId: Which collection is expiring
     * - eventCoordinates: Associated event
     * - expireTimeMs: Time when removed
     * - ageMs: Collection age in milliseconds
     */
    record CollectionExpire(
        String collectionId,
        EventCoordinates eventCoordinates,
        long expireTimeMs,
        long ageMs
    ) implements WitnessTransaction {
        @Override
        public String toString() {
            return String.format(
                "CollectionExpire{id=%s, event=%s, age=%dms, time=%d}",
                collectionId, eventCoordinates, ageMs, expireTimeMs
            );
        }
    }

    /**
     * Get human-readable description of this transaction.
     * Used for logging and debugging.
     */
    @Override
    String toString();
}
