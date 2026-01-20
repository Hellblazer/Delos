/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation;

import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
        Instant createdAt,
        boolean thresholdMet
    ) {
        public Snapshot {
            Objects.requireNonNull(event, "event cannot be null");
            Objects.requireNonNull(signatures, "signatures cannot be null");
            Objects.requireNonNull(createdAt, "createdAt cannot be null");
            if (threshold < 1) throw new IllegalArgumentException("threshold must be >= 1");
            if (epoch < 0) throw new IllegalArgumentException("epoch must be >= 0");
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

    // Configuration (immutable after construction)
    private final EventCoordinates event;
    private final int threshold;
    private final long epoch;
    private final Instant createdAt;

    // Thread-safe state
    private final ConcurrentHashMap<Identifier, SignatureEntry> signatures = new ConcurrentHashMap<>();
    private final AtomicBoolean thresholdReached = new AtomicBoolean(false);
    private final AtomicReference<Snapshot> thresholdSnapshot = new AtomicReference<>();

    /**
     * Create accumulator for an event.
     *
     * @param event Event coordinates being witnessed
     * @param threshold Required signature count (M)
     * @param epoch Fireflies epoch
     * @throws NullPointerException if event is null
     * @throws IllegalArgumentException if threshold < 1 or epoch < 0
     */
    public SignatureAccumulator(EventCoordinates event, int threshold, long epoch) {
        this.event = Objects.requireNonNull(event, "event cannot be null");
        if (threshold < 1) {
            throw new IllegalArgumentException("threshold must be >= 1, got: " + threshold);
        }
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch must be >= 0, got: " + epoch);
        }
        this.threshold = threshold;
        this.epoch = epoch;
        this.createdAt = Instant.now();
    }

    /**
     * Accumulate a BLS signature from a committee member.
     * <p>
     * Thread-safe and idempotent per member.
     * First signature from each member is accepted; duplicates are ignored.
     *
     * @param member Committee member identifier
     * @param committeeIndex Member's index in committee (for bitmap)
     * @param signature BLS signature from member
     * @return AccumulationResult indicating outcome
     * @throws NullPointerException if any parameter is null
     * @throws IllegalArgumentException if committeeIndex < 0
     */
    public AccumulationResult accumulate(Identifier member, int committeeIndex, BLSSignature signature) {
        Objects.requireNonNull(member, "member cannot be null");
        Objects.requireNonNull(signature, "signature cannot be null");
        if (committeeIndex < 0) {
            throw new IllegalArgumentException("committeeIndex must be >= 0, got: " + committeeIndex);
        }

        // Create entry
        var entry = new SignatureEntry(member, committeeIndex, signature, Instant.now());

        // Atomic deduplication - putIfAbsent returns null if inserted
        var existing = signatures.putIfAbsent(member, entry);
        if (existing != null) {
            return new AccumulationResult.AlreadyPresent(member);
        }

        // Check threshold with atomic compareAndSet to ensure single snapshot
        var count = signatures.size();
        if (count >= threshold && thresholdReached.compareAndSet(false, true)) {
            // First thread to reach threshold creates the snapshot
            var snapshot = createSnapshot();
            thresholdSnapshot.set(snapshot);
            return new AccumulationResult.ThresholdMet(count, snapshot);
        }

        return new AccumulationResult.Accumulated(count, threshold);
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

    // Internal helper to create snapshot
    private Snapshot createSnapshot() {
        return new Snapshot(
            event,
            new HashMap<>(signatures), // Copy for immutability
            threshold,
            epoch,
            createdAt,
            thresholdReached.get()
        );
    }
}
