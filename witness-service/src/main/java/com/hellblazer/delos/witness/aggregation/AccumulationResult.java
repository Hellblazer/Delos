/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.stereotomy.identifier.Identifier;

import java.time.Instant;
import java.util.Objects;

/**
 * Result types from signature accumulation.
 * Enables type-safe handling of all accumulation outcomes.
 *
 * @author hal.hildebrand
 */
public sealed interface AccumulationResult
    permits AccumulationResult.Accumulated,
            AccumulationResult.ThresholdMet,
            AccumulationResult.AlreadyPresent,
            AccumulationResult.InvalidSignature,
            AccumulationResult.EpochMismatch,
            AccumulationResult.ViewRefMismatch,
            AccumulationResult.LateSigner,
            AccumulationResult.Buffered {

    /**
     * Signature successfully accumulated, threshold not yet met.
     *
     * @param currentCount Current number of accumulated signatures
     * @param threshold Required threshold for completion
     */
    record Accumulated(int currentCount, int threshold) implements AccumulationResult {
        public Accumulated {
            if (currentCount < 0) throw new IllegalArgumentException("currentCount must be >= 0");
            if (threshold < 1) throw new IllegalArgumentException("threshold must be >= 1");
        }

        /** Progress as percentage (0.0 to 1.0). */
        public double progress() {
            return (double) currentCount / threshold;
        }

        /** Signatures still needed to reach threshold. */
        public int remaining() {
            return Math.max(0, threshold - currentCount);
        }
    }

    /**
     * Threshold reached with this signature.
     * Includes snapshot for immediate aggregation.
     *
     * @param count Final signature count
     * @param snapshot Accumulator snapshot for aggregation
     */
    record ThresholdMet(
        int count,
        SignatureAccumulator.Snapshot snapshot
    ) implements AccumulationResult {
        public ThresholdMet {
            if (count < 1) throw new IllegalArgumentException("count must be >= 1");
            Objects.requireNonNull(snapshot, "snapshot cannot be null");
        }
    }

    /**
     * Member has already contributed a signature.
     * Duplicate signatures are ignored (idempotent).
     *
     * @param member The member who already signed
     */
    record AlreadyPresent(Identifier member) implements AccumulationResult {
        public AlreadyPresent {
            Objects.requireNonNull(member, "member cannot be null");
        }
    }

    /**
     * Signature validation failed.
     *
     * @param member The member whose signature was invalid
     * @param reason Description of validation failure
     */
    record InvalidSignature(
        Identifier member,
        String reason
    ) implements AccumulationResult {
        public InvalidSignature {
            Objects.requireNonNull(member, "member cannot be null");
            Objects.requireNonNull(reason, "reason cannot be null");
        }
    }

    /**
     * Epoch mismatch detected.
     * Signature provides a different epoch than the accumulator.
     *
     * @param member The member whose signature had epoch mismatch
     * @param expectedEpoch Epoch expected by accumulator
     * @param providedEpoch Epoch provided by member
     */
    record EpochMismatch(
        Identifier member,
        long expectedEpoch,
        long providedEpoch
    ) implements AccumulationResult {
        public EpochMismatch {
            Objects.requireNonNull(member, "member cannot be null");
            if (expectedEpoch < 0 || providedEpoch < 0) {
                throw new IllegalArgumentException("epochs must be >= 0");
            }
        }
    }

    /**
     * ViewRef mismatch detected.
     * Signature provides a different view reference than the accumulator.
     *
     * @param member The member whose signature had viewRef mismatch
     * @param expectedViewRef ViewRef expected by accumulator
     * @param providedViewRef ViewRef provided by member
     */
    record ViewRefMismatch(
        Identifier member,
        Digest expectedViewRef,
        Digest providedViewRef
    ) implements AccumulationResult {
        public ViewRefMismatch {
            Objects.requireNonNull(member, "member cannot be null");
            Objects.requireNonNull(expectedViewRef, "expectedViewRef cannot be null");
            Objects.requireNonNull(providedViewRef, "providedViewRef cannot be null");
        }
    }

    /**
     * Late signer detected.
     * Signature arrived after threshold was already met.
     *
     * @param member The member whose signature arrived late
     * @param thresholdReached Threshold value that was already met
     * @param thresholdReachedAt When the threshold was met
     */
    record LateSigner(
        Identifier member,
        int thresholdReached,
        Instant thresholdReachedAt
    ) implements AccumulationResult {
        public LateSigner {
            Objects.requireNonNull(member, "member cannot be null");
            Objects.requireNonNull(thresholdReachedAt, "thresholdReachedAt cannot be null");
            if (thresholdReached < 1) {
                throw new IllegalArgumentException("threshold must be >= 1");
            }
        }
    }

    /**
     * Signature buffered during view transition.
     * Will be replayed when new committee is active.
     *
     * @param member The member whose signature was buffered
     * @param bufferPosition Position in buffer queue
     * @param expectedReplayEpoch Epoch when signature will be replayed
     */
    record Buffered(
        Identifier member,
        int bufferPosition,
        long expectedReplayEpoch
    ) implements AccumulationResult {
        public Buffered {
            Objects.requireNonNull(member, "member cannot be null");
            if (bufferPosition < 0) {
                throw new IllegalArgumentException("bufferPosition must be >= 0");
            }
        }
    }

    /** Check if accumulation was successful (Accumulated or ThresholdMet). */
    default boolean isSuccess() {
        return this instanceof Accumulated || this instanceof ThresholdMet;
    }

    /** Check if threshold was reached. */
    default boolean isThresholdMet() {
        return this instanceof ThresholdMet;
    }

    /** Check if signature was buffered during view transition. */
    default boolean isBuffered() {
        return this instanceof Buffered;
    }
}
