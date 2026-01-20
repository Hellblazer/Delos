/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.delos.witness.aggregation;

import com.hellblazer.delos.stereotomy.identifier.Identifier;

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
            AccumulationResult.InvalidSignature {

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

    /** Check if accumulation was successful (Accumulated or ThresholdMet). */
    default boolean isSuccess() {
        return this instanceof Accumulated || this instanceof ThresholdMet;
    }

    /** Check if threshold was reached. */
    default boolean isThresholdMet() {
        return this instanceof ThresholdMet;
    }
}
