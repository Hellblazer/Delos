/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.witness.metrics.BLSMetrics;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe accumulator for BLS signatures during receipt collection.
 * <p>
 * Design:
 * - Lock-free accumulation using ConcurrentHashMap
 * - Atomic threshold detection with single snapshot creation
 * - Immutable state snapshots for safe observation
 * - Virtual thread compatible (no blocking locks)
 * <p>
 * Lifecycle: Create per-event, accumulate signatures, snapshot when threshold met, discard.
 * <p>
 * Performance:
 * - Accumulation: O(1) per signature
 * - Threshold check: O(1)
 * - Snapshot creation: O(n) where n = signer count
 * - Memory: ~200 bytes per accumulated entry
 *
 * @author hal.hildebrand
 */
public final class SignatureAccumulator {

    /**
     * Immutable snapshot of accumulator state.
     * Thread-safe for observation and aggregation.
     */
    public record Snapshot(
        EventCoordinates event,
        Map<Identifier, SignatureEntry> signatures,
        int threshold,
        long epoch,
        Digest viewRef,
        Instant createdAt,
        boolean thresholdMet
    ) {
        public Snapshot {
            Objects.requireNonNull(event, "event cannot be null");
            Objects.requireNonNull(signatures, "signatures cannot be null");
            Objects.requireNonNull(createdAt, "createdAt cannot be null");
            if (threshold < 1) throw new IllegalArgumentException("threshold must be >= 1");
            if (epoch < 0) throw new IllegalArgumentException("epoch must be >= 0");
            // viewRef may be null (backward compatibility for Fireflies startup)
            // Defensive copy to ensure immutability
            signatures = Map.copyOf(signatures);
        }

        /** Number of signatures accumulated. */
        public int signerCount() {
            return signatures.size();
        }

        /** Committee indices of signers, sorted ascending. */
        public List<Integer> signerIndices() {
            return signatures.values().stream()
                .sorted()
                .map(SignatureEntry::committeeIndex)
                .toList();
        }

        /** BLS signatures ordered by committee index. */
        public List<BLSSignature> signatureList() {
            return signatures.values().stream()
                .sorted()
                .map(SignatureEntry::signature)
                .toList();
        }

        /** Identifiers of all signers. */
        public Set<Identifier> signerIds() {
            return signatures.keySet();
        }
    }

    /**
     * Individual signature entry with metadata.
     */
    public record SignatureEntry(
        Identifier member,
        int committeeIndex,
        BLSSignature signature,
        Instant receivedAt
    ) implements Comparable<SignatureEntry> {

        public SignatureEntry {
            Objects.requireNonNull(member, "member cannot be null");
            Objects.requireNonNull(signature, "signature cannot be null");
            Objects.requireNonNull(receivedAt, "receivedAt cannot be null");
            if (committeeIndex < 0) throw new IllegalArgumentException("committeeIndex must be >= 0");
        }

        @Override
        public int compareTo(SignatureEntry other) {
            return Integer.compare(this.committeeIndex, other.committeeIndex);
        }
    }

    /**
     * Immutable metrics snapshot from accumulator.
     * Tracks validation failures and state at any point in time.
     */
    public record Metrics(
        long epochMismatches,
        long viewRefMismatches,
        long lateSigners,
        int currentSignerCount,
        boolean thresholdMet
    ) {}

    // Configuration (immutable after construction)
    private final EventCoordinates event;
    private final int threshold;
    private final long epoch;
    private final Digest viewRef;
    private final Instant createdAt;
    private final BLSMetrics metrics;  // nullable for backward compatibility

    // Thread-safe state
    private final ConcurrentHashMap<Identifier, SignatureEntry> signatures = new ConcurrentHashMap<>();
    private final AtomicBoolean thresholdReached = new AtomicBoolean(false);
    private final AtomicReference<Instant> thresholdReachedAt = new AtomicReference<>();
    private final AtomicReference<Snapshot> thresholdSnapshot = new AtomicReference<>();

    // Validation metrics
    private final LongAdder epochMismatchCount = new LongAdder();
    private final LongAdder viewRefMismatchCount = new LongAdder();
    private final LongAdder lateSignerCount = new LongAdder();

    /**
     * Create accumulator for an event with epoch, viewRef validation, and metrics.
     *
     * @param event Event coordinates being witnessed
     * @param threshold Required signature count (M)
     * @param epoch Fireflies epoch
     * @param viewRef View reference for validation (may be null for backward compatibility)
     * @param metrics BLS metrics collector (may be null)
     * @throws NullPointerException if event is null
     * @throws IllegalArgumentException if threshold < 1 or epoch < 0
     */
    public SignatureAccumulator(EventCoordinates event, int threshold, long epoch, Digest viewRef, BLSMetrics metrics) {
        this.event = Objects.requireNonNull(event, "event cannot be null");
        if (threshold < 1) {
            throw new IllegalArgumentException("threshold must be >= 1, got: " + threshold);
        }
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch must be >= 0, got: " + epoch);
        }
        this.threshold = threshold;
        this.epoch = epoch;
        this.viewRef = viewRef; // May be null
        this.createdAt = Instant.now();
        this.metrics = metrics; // May be null

        // Track accumulator creation
        if (metrics != null) {
            metrics.incrementAccumulatorCreated();
        }
    }

    /**
     * Create accumulator for an event with epoch and viewRef validation (backward compatibility).
     *
     * @param event Event coordinates being witnessed
     * @param threshold Required signature count (M)
     * @param epoch Fireflies epoch
     * @param viewRef View reference for validation (may be null for backward compatibility)
     * @throws NullPointerException if event is null
     * @throws IllegalArgumentException if threshold < 1 or epoch < 0
     */
    public SignatureAccumulator(EventCoordinates event, int threshold, long epoch, Digest viewRef) {
        this(event, threshold, epoch, viewRef, null);
    }

    /**
     * Create accumulator for an event (backward compatibility).
     * ViewRef is set to null.
     *
     * @param event Event coordinates being witnessed
     * @param threshold Required signature count (M)
     * @param epoch Fireflies epoch
     * @throws NullPointerException if event is null
     * @throws IllegalArgumentException if threshold < 1 or epoch < 0
     * @deprecated Use {@link #SignatureAccumulator(EventCoordinates, int, long, Digest)} instead
     */
    @Deprecated(since = "1.0", forRemoval = true)
    public SignatureAccumulator(EventCoordinates event, int threshold, long epoch) {
        this(event, threshold, epoch, null);
    }

    /**
     * Accumulate a BLS signature from a committee member with epoch and viewRef validation.
     * <p>
     * Thread-safe and idempotent per member.
     * First signature from each member is accepted; duplicates are ignored.
     * <p>
     * Validation order (fail-fast optimization):
     * 1. Late signer check (O(1) atomic read - cheapest)
     * 2. Epoch validation (O(1) comparison)
     * 3. ViewRef validation (O(1) Digest.equals if both non-null)
     * 4. Duplicate check (O(1) putIfAbsent)
     *
     * @param member Committee member identifier
     * @param committeeIndex Member's index in committee (for bitmap)
     * @param signature BLS signature from member
     * @param epoch Fireflies epoch for this signature
     * @param viewRef View reference for this signature (may be null)
     * @return AccumulationResult indicating outcome
     * @throws NullPointerException if member, signature, or epoch is null
     * @throws IllegalArgumentException if committeeIndex < 0
     */
    public AccumulationResult accumulate(Identifier member, int committeeIndex, BLSSignature signature,
                                         long epoch, Digest viewRef) {
        Objects.requireNonNull(member, "member cannot be null");
        Objects.requireNonNull(signature, "signature cannot be null");
        if (committeeIndex < 0) {
            throw new IllegalArgumentException("committeeIndex must be >= 0, got: " + committeeIndex);
        }

        // STEP 1: Reject late signers (O(1) atomic read - cheapest check first)
        if (thresholdReached.get()) {
            lateSignerCount.increment();
            var reachedAt = thresholdReachedAt.get();
            if (reachedAt == null) {
                reachedAt = Instant.now(); // Fallback if timing issue
            }
            return new AccumulationResult.LateSigner(member, threshold, reachedAt);
        }

        // STEP 2: Validate epoch (O(1) primitive comparison)
        if (epoch != this.epoch) {
            epochMismatchCount.increment();
            return new AccumulationResult.EpochMismatch(member, this.epoch, epoch);
        }

        // STEP 3: Validate viewRef if both non-null (O(1) Digest.equals)
        if (this.viewRef != null && viewRef != null && !this.viewRef.equals(viewRef)) {
            viewRefMismatchCount.increment();
            return new AccumulationResult.ViewRefMismatch(member, this.viewRef, viewRef);
        }

        // STEP 4: Check for duplicates
        var entry = new SignatureEntry(member, committeeIndex, signature, Instant.now());
        var existing = signatures.putIfAbsent(member, entry);
        if (existing != null) {
            return new AccumulationResult.AlreadyPresent(member);
        }

        // Check threshold with atomic compareAndSet to ensure single snapshot
        var count = signatures.size();
        if (count >= threshold && thresholdReached.compareAndSet(false, true)) {
            // First thread to reach threshold: record timestamp and create snapshot
            var now = Instant.now();
            thresholdReachedAt.set(now);
            var snapshot = createSnapshot();
            thresholdSnapshot.set(snapshot);

            // Track metrics for threshold achievement
            if (metrics != null) {
                // Record time to threshold (microseconds)
                var durationMicros = Duration.between(createdAt, now).toNanos() / 1000;
                metrics.recordTimeToThreshold(durationMicros);

                // Record threshold percentage (should be 1.0 or slightly above)
                var percentage = (double) count / threshold;
                metrics.recordThresholdPercentage(Math.min(1.0, percentage));
            }

            return new AccumulationResult.ThresholdMet(count, snapshot);
        }

        return new AccumulationResult.Accumulated(count, threshold);
    }

    /**
     * Accumulate a BLS signature from a committee member (backward compatibility).
     * ViewRef is assumed to be null, epoch is from constructor.
     * <p>
     * Thread-safe and idempotent per member.
     *
     * @param member Committee member identifier
     * @param committeeIndex Member's index in committee (for bitmap)
     * @param signature BLS signature from member
     * @return AccumulationResult indicating outcome
     * @throws NullPointerException if any parameter is null
     * @throws IllegalArgumentException if committeeIndex < 0
     * @deprecated Use {@link #accumulate(Identifier, int, BLSSignature, long, Digest)} instead
     */
    @Deprecated(since = "1.0", forRemoval = true)
    public AccumulationResult accumulate(Identifier member, int committeeIndex, BLSSignature signature) {
        return accumulate(member, committeeIndex, signature, this.epoch, this.viewRef);
    }

    /**
     * Create immutable snapshot of current state.
     *
     * @return Snapshot of accumulated signatures
     */
    public Snapshot snapshot() {
        return createSnapshot();
    }

    /**
     * Get the threshold snapshot if threshold was met.
     *
     * @return Optional containing snapshot if threshold met, empty otherwise
     */
    public Optional<Snapshot> getThresholdSnapshot() {
        return Optional.ofNullable(thresholdSnapshot.get());
    }

    /** Current signature count. */
    public int signerCount() {
        return signatures.size();
    }

    /** Check if threshold has been reached. */
    public boolean isThresholdMet() {
        return thresholdReached.get();
    }

    /** Get the event being accumulated. */
    public EventCoordinates getEvent() {
        return event;
    }

    /** Get the required threshold. */
    public int getThreshold() {
        return threshold;
    }

    /** Get the epoch. */
    public long getEpoch() {
        return epoch;
    }

    /** Get creation timestamp. */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Get validation metrics.
     *
     * @return Current metrics snapshot
     */
    public Metrics getMetrics() {
        return new Metrics(
            epochMismatchCount.sum(),
            viewRefMismatchCount.sum(),
            lateSignerCount.sum(),
            signatures.size(),
            thresholdReached.get()
        );
    }

    /** Get the view reference (may be null). */
    public Digest getViewRef() {
        return viewRef;
    }

    // Internal helper to create snapshot
    private Snapshot createSnapshot() {
        return new Snapshot(
            event,
            new HashMap<>(signatures), // Copy for immutability
            threshold,
            epoch,
            viewRef,
            createdAt,
            thresholdReached.get()
        );
    }
}
