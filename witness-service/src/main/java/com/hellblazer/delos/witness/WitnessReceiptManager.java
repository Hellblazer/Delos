/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.witness.aggregation.AccumulationResult;
import com.hellblazer.delos.witness.aggregation.BLSReceiptAggregator;
import com.hellblazer.delos.witness.aggregation.SignatureFormat;
import com.hellblazer.delos.witness.aggregation.SignatureAccumulator;
import com.hellblazer.delos.witness.migration.MigrationPhase;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Witness receipt manager for M-of-N threshold receipt collection.
 * <p>
 * Coordinates receipt collection across witness committee:
 * - Collects witness signatures for events
 * - Tracks collection state (signatures, threshold achievement)
 * - Manages in-flight collections during view changes
 * - Provides drain period for graceful transitions
 * </p>
 * <p>
 * Thread-safe: Uses concurrent collections and read-write locks.
 * </p>
 */
public class WitnessReceiptManager {

    private final WitnessParameters parameters;
    private final SignatureFormat signatureFormat;
    private final MigrationPhase migrationPhase;
    private final BLSReceiptAggregator blsAggregator;
    private final ReadWriteLock lock;

    // Map: EventCoordinates -> CollectionState
    private final Map<String, CollectionState> collections = new ConcurrentHashMap<>();

    /**
     * Create receipt manager with parameters.
     *
     * @param parameters Witness configuration (threshold, drain period, signature format, etc.)
     */
    public WitnessReceiptManager(WitnessParameters parameters) {
        this.parameters = Objects.requireNonNull(parameters, "parameters required");
        this.signatureFormat = parameters.signatureFormat();
        this.migrationPhase = parameters.migrationPhase();

        // Create BLS aggregator if BLS supported
        this.blsAggregator = (migrationPhase == MigrationPhase.DUAL || migrationPhase == MigrationPhase.BLS_ONLY)
            ? new BLSReceiptAggregator()
            : null;

        this.lock = new ReentrantReadWriteLock();
    }

    /**
     * Add witness Ed25519 signature to receipt collection for event.
     * <p>
     * Signatures are deduplicated by member - each member can contribute one signature.
     * </p>
     *
     * @param event  Event coordinates being witnessed
     * @param member Witness member who signed
     * @param signature Signature digest
     * @throws IllegalStateException if Ed25519 is not supported in current migration phase
     */
    public void addSignature(EventCoordinates event, Identifier member, Digest signature) {
        // Check if Ed25519 is supported in current phase
        if (migrationPhase == MigrationPhase.BLS_ONLY) {
            throw new IllegalStateException("Ed25519 signatures not supported in BLS_ONLY phase");
        }

        Objects.requireNonNull(event, "event required");
        Objects.requireNonNull(member, "member required");
        Objects.requireNonNull(signature, "signature required");

        lock.writeLock().lock();
        try {
            var key = eventKey(event);
            var state = collections.computeIfAbsent(key, k ->
                new CollectionState(SignatureFormat.ED25519, parameters.threshold())
            );

            // Add signature, deduplicating by member
            state.addSignature(member, signature);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Add BLS signature for an event.
     * <p>
     * Used during DUAL and BLS_ONLY phases for signature aggregation.
     * </p>
     *
     * @param event Event coordinates being witnessed
     * @param member Committee member identifier
     * @param committeeIndex Member's index in committee
     * @param signature BLS signature from member
     * @throws IllegalStateException if BLS is not supported in current migration phase
     * @throws NullPointerException if any required parameter is null
     * @throws IllegalArgumentException if committeeIndex < 0
     */
    public void addBLSSignature(
        EventCoordinates event,
        Identifier member,
        int committeeIndex,
        BLSSignature signature
    ) {
        // Check if BLS is supported in current phase (before validation)
        if (migrationPhase == MigrationPhase.INIT) {
            throw new IllegalStateException("BLS signatures not supported in INIT phase");
        }

        Objects.requireNonNull(event, "event required");
        Objects.requireNonNull(member, "member required");
        Objects.requireNonNull(signature, "signature required");

        if (committeeIndex < 0) {
            throw new IllegalArgumentException("committeeIndex must be >= 0, got: " + committeeIndex);
        }

        lock.writeLock().lock();
        try {
            // Get or create collection state for this event
            var key = eventKey(event);
            var state = collections.computeIfAbsent(key, k ->
                new CollectionState(SignatureFormat.BLS_12_381, parameters.threshold())
            );

            // Accumulate in BLS aggregator
            var result = blsAggregator.accumulate(
                event,
                member,
                committeeIndex,
                signature,
                parameters.threshold(),
                parameters.epoch()
            );

            // Handle accumulation result
            switch (result) {
                case AccumulationResult.Accumulated acc -> {
                    state.recordSignature(member);
                }
                case AccumulationResult.ThresholdMet tm -> {
                    state.recordSignature(member);
                    state.markThresholdMet(tm.snapshot());
                }
                case AccumulationResult.AlreadyPresent ap -> {
                    // Duplicate, ignore (idempotent)
                }
                case AccumulationResult.LateSigner ls -> {
                    // Late signer, ignore (already met threshold)
                }
                case AccumulationResult.InvalidSignature is -> {
                    throw new IllegalArgumentException(
                        "Invalid signature from " + member + ": " + is.reason()
                    );
                }
                case AccumulationResult.EpochMismatch em -> {
                    throw new IllegalArgumentException(
                        "Epoch mismatch from " + member +
                        ": expected " + em.expectedEpoch() +
                        ", got " + em.providedEpoch()
                    );
                }
                case AccumulationResult.ViewRefMismatch vm -> {
                    throw new IllegalArgumentException(
                        "ViewRef mismatch from " + member
                    );
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Get current collection state for event.
     *
     * @param event Event coordinates
     * @return Collection state (signature count, threshold achievement)
     */
    public CollectionState getCollectionState(EventCoordinates event) {
        lock.readLock().lock();
        try {
            var key = eventKey(event);
            return collections.getOrDefault(key,
                new CollectionState(signatureFormat, parameters.threshold())
            );
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Mark collection as complete (remove from in-flight tracking).
     *
     * @param event Event coordinates
     */
    public void completeCollection(EventCoordinates event) {
        lock.writeLock().lock();
        try {
            var key = eventKey(event);
            collections.remove(key);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Get count of in-flight collections.
     * Used to track receipt collection progress during view changes.
     *
     * @return Number of active collections
     */
    public int getInFlightCount() {
        lock.readLock().lock();
        try {
            return collections.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get M-of-N threshold for receipt validity.
     *
     * @return Signature threshold
     */
    public int getThreshold() {
        return parameters.threshold();
    }

    /**
     * Get drain period for graceful view changes.
     * Allows in-flight collections to complete during member transitions.
     *
     * @return Drain period duration
     */
    public Duration getDrainPeriod() {
        return parameters.drainPeriod();
    }

    /**
     * Generate unique key for event (for collection tracking).
     */
    private String eventKey(EventCoordinates event) {
        return event.getDigest().toString() + ":" + event.getSequenceNumber();
    }

    /**
     * Collection state tracker for single event.
     * Supports both Ed25519 and BLS12-381 signature formats.
     */
    public static class CollectionState {
        private final SignatureFormat format;
        private final int threshold;
        private final Set<Identifier> signers = ConcurrentHashMap.newKeySet();
        private volatile SignatureAccumulator.Snapshot blsSnapshot;
        private volatile boolean thresholdAchieved = false;

        /**
         * Create collection state for given format.
         *
         * @param format Signature format (ED25519 or BLS_12_381)
         * @param threshold Required signature count
         */
        public CollectionState(SignatureFormat format, int threshold) {
            this.format = Objects.requireNonNull(format, "format required");
            this.threshold = threshold;
        }

        /**
         * Add Ed25519 signature from member (deduplicated by member).
         */
        public void addSignature(Identifier member, Digest signature) {
            boolean isNew = signers.add(member);
            if (isNew && signers.size() >= threshold) {
                thresholdAchieved = true;
            }
        }

        /**
         * Record a signer for BLS mode (signature already validated by aggregator).
         */
        public void recordSignature(Identifier member) {
            signers.add(member);
        }

        /**
         * Mark threshold met with BLS snapshot for aggregation.
         */
        public void markThresholdMet(SignatureAccumulator.Snapshot snapshot) {
            this.blsSnapshot = Objects.requireNonNull(snapshot, "snapshot required");
            this.thresholdAchieved = true;
        }

        /**
         * Get number of signatures collected.
         */
        public int signatureCount() {
            return signers.size();
        }

        /**
         * Check if threshold achieved.
         */
        public boolean isThresholdAchieved() {
            return thresholdAchieved && signers.size() >= threshold;
        }

        /**
         * Get set of signers (member identifiers).
         */
        public Set<Identifier> getSigners() {
            return Set.copyOf(signers);
        }

        /**
         * Get signature format for this collection.
         */
        public SignatureFormat getFormat() {
            return format;
        }

        /**
         * Get BLS snapshot if available.
         * Present only when threshold met in BLS mode.
         */
        public Optional<SignatureAccumulator.Snapshot> getBLSSnapshot() {
            return Optional.ofNullable(blsSnapshot);
        }

        /**
         * Get number of signers (for test compatibility).
         */
        public int signerCount() {
            return signers.size();
        }
    }
}
