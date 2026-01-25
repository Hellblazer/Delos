/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.bls.BLSAggregate;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.witness.aggregation.AccumulationResult;
import com.hellblazer.delos.witness.aggregation.BLSReceiptAggregator;
import com.hellblazer.delos.witness.aggregation.SignatureFormat;
import com.hellblazer.delos.witness.aggregation.SignatureAccumulator;
import com.hellblazer.delos.witness.aggregation.storage.AggregateReceiptStore;
import com.hellblazer.delos.witness.aggregation.storage.RecursiveReceiptStore;
import com.hellblazer.delos.witness.aggregation.storage.memory.InMemoryAggregateReceiptStore;
import com.hellblazer.delos.witness.aggregation.storage.memory.InMemoryRecursiveReceiptStore;
import com.hellblazer.delos.witness.metrics.BLSMetrics;
import com.hellblazer.delos.witness.migration.MigrationPhase;
import com.hellblazer.delos.witness.receipt.AggregateWitnessReceipt;
import com.hellblazer.delos.witness.validation.graceful.DegradedThresholdCalculator;
import com.hellblazer.delos.witness.validation.graceful.SignatureBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

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

    private static final Logger log = LoggerFactory.getLogger(WitnessReceiptManager.class);

    /**
     * Buffer key for tracking buffered BLS signatures.
     * Used to store committeeIndex for each buffered signature during view transitions.
     */
    private record BufferKey(EventCoordinates event, Identifier member) {}

    private final WitnessParameters parameters;
    private final SignatureFormat signatureFormat;
    private final MigrationPhase migrationPhase;
    private final BLSReceiptAggregator blsAggregator;
    private final ReadWriteLock lock;

    // Graceful degradation support (nullable for backward compatibility)
    private final SignatureBuffer signatureBuffer;
    private final Supplier<Boolean> isViewChangeActive;
    private final DegradedThresholdCalculator degradedCalculator;

    // Metrics support (nullable for backward compatibility)
    private final BLSMetrics metrics;

    // Receipt storage (Phase 3.4.5 - storage integration)
    private final AggregateReceiptStore aggregateReceiptStore;
    private final RecursiveReceiptStore recursiveReceiptStore;

    // Map: EventCoordinates -> CollectionState
    private final Map<String, CollectionState> collections = new ConcurrentHashMap<>();

    // Map: BufferKey -> committeeIndex (for buffered BLS signatures)
    private final Map<BufferKey, Integer> committeeIndexMap = new ConcurrentHashMap<>();

    /**
     * Create receipt manager with parameters (backward compatible constructor).
     *
     * @param parameters Witness configuration (threshold, drain period, signature format, etc.)
     */
    public WitnessReceiptManager(WitnessParameters parameters) {
        this(parameters, null, null, null);
    }

    /**
     * Create receipt manager with graceful degradation support.
     *
     * @param parameters Witness configuration (threshold, drain period, signature format, etc.)
     * @param signatureBuffer Signature buffer for buffering during view changes (nullable)
     * @param isViewChangeActive Supplier to check if view change is active (nullable)
     * @param degradedCalculator Calculator for degraded thresholds during Byzantine detection (nullable)
     */
    public WitnessReceiptManager(
        WitnessParameters parameters,
        SignatureBuffer signatureBuffer,
        Supplier<Boolean> isViewChangeActive,
        DegradedThresholdCalculator degradedCalculator
    ) {
        this(parameters, signatureBuffer, isViewChangeActive, degradedCalculator, null);
    }

    /**
     * Create receipt manager with graceful degradation and metrics support.
     *
     * @param parameters Witness configuration (threshold, drain period, signature format, etc.)
     * @param signatureBuffer Signature buffer for buffering during view changes (nullable)
     * @param isViewChangeActive Supplier to check if view change is active (nullable)
     * @param degradedCalculator Calculator for degraded thresholds during Byzantine detection (nullable)
     * @param metrics BLS metrics collector (nullable)
     */
    public WitnessReceiptManager(
        WitnessParameters parameters,
        SignatureBuffer signatureBuffer,
        Supplier<Boolean> isViewChangeActive,
        DegradedThresholdCalculator degradedCalculator,
        BLSMetrics metrics
    ) {
        this(parameters, signatureBuffer, isViewChangeActive, degradedCalculator, metrics, null, null);
    }

    /**
     * Create receipt manager with storage integration (Phase 3.4.5).
     *
     * @param parameters Witness configuration (threshold, drain period, signature format, etc.)
     * @param signatureBuffer Signature buffer for buffering during view changes (nullable)
     * @param isViewChangeActive Supplier to check if view change is active (nullable)
     * @param degradedCalculator Calculator for degraded thresholds during Byzantine detection (nullable)
     * @param metrics BLS metrics collector (nullable)
     * @param aggregateReceiptStore Storage for aggregate receipts (nullable, defaults to in-memory)
     * @param recursiveReceiptStore Storage for recursive receipts (nullable, defaults to in-memory)
     */
    public WitnessReceiptManager(
        WitnessParameters parameters,
        SignatureBuffer signatureBuffer,
        Supplier<Boolean> isViewChangeActive,
        DegradedThresholdCalculator degradedCalculator,
        BLSMetrics metrics,
        AggregateReceiptStore aggregateReceiptStore,
        RecursiveReceiptStore recursiveReceiptStore
    ) {
        this.parameters = Objects.requireNonNull(parameters, "parameters required");
        this.signatureFormat = parameters.signatureFormat();
        this.migrationPhase = parameters.migrationPhase();

        // Create BLS aggregator if BLS supported (pass metrics for instrumentation)
        this.blsAggregator = (migrationPhase == MigrationPhase.DUAL || migrationPhase == MigrationPhase.BLS_ONLY)
            ? new BLSReceiptAggregator(Duration.ofMinutes(10), metrics)
            : null;

        this.lock = new ReentrantReadWriteLock();

        // Graceful degradation support (nullable for feature flag pattern)
        this.signatureBuffer = signatureBuffer;
        this.isViewChangeActive = isViewChangeActive;
        this.degradedCalculator = degradedCalculator;

        // Metrics support (nullable for backward compatibility)
        this.metrics = metrics;

        // Receipt storage (nullable, fallback to in-memory)
        this.aggregateReceiptStore = aggregateReceiptStore != null
            ? aggregateReceiptStore
            : new InMemoryAggregateReceiptStore();
        this.recursiveReceiptStore = recursiveReceiptStore != null
            ? recursiveReceiptStore
            : new InMemoryRecursiveReceiptStore();
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

        // Start latency measurement
        var startNanos = System.nanoTime();

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
                    // Record successful accumulation latency
                    recordLatency(startNanos);
                    // Update active accumulator count
                    updateActiveAccumulators();
                }
                case AccumulationResult.ThresholdMet tm -> {
                    state.recordSignature(member);
                    state.markThresholdMet(tm.snapshot());
                    // Record successful accumulation latency
                    recordLatency(startNanos);
                    // Record completion
                    if (metrics != null) {
                        metrics.recordCompletedAccumulation();
                    }
                    // Update active accumulator count
                    updateActiveAccumulators();
                }
                case AccumulationResult.AlreadyPresent ap -> {
                    // Duplicate, ignore (idempotent)
                    if (metrics != null) {
                        metrics.incrementRejectedDuplicate();
                    }
                }
                case AccumulationResult.LateSigner ls -> {
                    // Late signer, ignore (already met threshold)
                    if (metrics != null) {
                        metrics.incrementRejectedLate();
                    }
                }
                case AccumulationResult.InvalidSignature is -> {
                    throw new IllegalArgumentException(
                        "Invalid signature from " + member + ": " + is.reason()
                    );
                }
                case AccumulationResult.EpochMismatch em -> {
                    if (metrics != null) {
                        metrics.incrementRejectedEpoch();
                    }
                    throw new IllegalArgumentException(
                        "Epoch mismatch from " + member +
                        ": expected " + em.expectedEpoch() +
                        ", got " + em.providedEpoch()
                    );
                }
                case AccumulationResult.ViewRefMismatch vm -> {
                    if (metrics != null) {
                        metrics.incrementRejectedViewRef();
                    }
                    throw new IllegalArgumentException(
                        "ViewRef mismatch from " + member
                    );
                }
                case AccumulationResult.Buffered buf -> {
                    // Phase 1C-3-C (Delos-3961): Buffer signature during view change
                    if (signatureBuffer != null && isViewChangeActive != null && isViewChangeActive.get()) {
                        // Store signature in buffer
                        var position = signatureBuffer.buffer(
                            member,
                            signature.toBytes(),
                            new byte[0], // message placeholder (not needed for replay)
                            event,
                            parameters.epoch()
                        );

                        // Store committeeIndex for replay
                        var bufferKey = new BufferKey(event, member);
                        committeeIndexMap.put(bufferKey, committeeIndex);

                        log.debug("Signature buffered for member {} at position {} during view change " +
                                  "(epoch {}, expected replay epoch {})",
                                  member, position, parameters.epoch(), buf.expectedReplayEpoch());
                    } else {
                        // Buffer feature not enabled or not during view change - signature will be dropped
                        log.warn("Signature from {} cannot be buffered (buffer: {}, viewChange: {})",
                                 member, signatureBuffer != null,
                                 isViewChangeActive != null && isViewChangeActive.get());
                    }
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Record signature receipt latency in microseconds.
     *
     * @param startNanos Start time in nanoseconds
     */
    private void recordLatency(long startNanos) {
        if (metrics != null) {
            var endNanos = System.nanoTime();
            var latencyMicros = (endNanos - startNanos) / 1000;
            metrics.recordReceiptLatency(latencyMicros);
        }
    }

    /**
     * Update active accumulator count metric.
     */
    private void updateActiveAccumulators() {
        if (metrics != null && blsAggregator != null) {
            var activeCount = blsAggregator.metrics().activeAccumulators();
            metrics.setActiveAccumulators(activeCount);
        }
    }

    /**
     * Get BLS aggregate signature for an event if threshold met.
     * <p>
     * Returns aggregate only when threshold signatures have been accumulated.
     * Returns empty if threshold not met, event unknown, or BLS not supported.
     *
     * @param event Event coordinates
     * @return Optional containing BLS aggregate if available
     * @throws NullPointerException if event is null
     */
    public Optional<BLSAggregate> getBLSAggregate(EventCoordinates event) {
        Objects.requireNonNull(event, "event required");

        // Return empty if BLS not supported in current phase
        if (blsAggregator == null) {
            return Optional.empty();
        }

        return blsAggregator.getAggregate(event);
    }

    /**
     * Get count of in-flight BLS signature accumulations.
     * <p>
     * Returns number of events with active BLS signature accumulation.
     * Distinct from getInFlightCount() which tracks all collections (Ed25519 + BLS).
     *
     * @return Count of active BLS accumulations, 0 if BLS not supported
     */
    public int getBLSInFlightCount() {
        // Return 0 if BLS not supported in current phase
        if (blsAggregator == null) {
            return 0;
        }

        return blsAggregator.metrics().activeAccumulators();
    }

    /**
     * Get aggregate witness receipt for an event if threshold met.
     * <p>
     * Combines BLS aggregate signature with metadata for network transmission.
     * Returns empty if threshold not met, event unknown, or BLS not supported.
     *
     * @param event Event coordinates
     * @return Optional containing aggregate receipt if available
     * @throws NullPointerException if event is null
     */
    public Optional<AggregateWitnessReceipt> getAggregateReceipt(EventCoordinates event) {
        Objects.requireNonNull(event, "event required");

        // Return empty if BLS not supported in current phase
        if (blsAggregator == null) {
            return Optional.empty();
        }

        // Get BLS aggregate
        var aggregateOpt = blsAggregator.getAggregate(event);
        if (aggregateOpt.isEmpty()) {
            return Optional.empty();
        }

        // Get collection state for snapshot
        lock.readLock().lock();
        try {
            var key = eventKey(event);
            var state = collections.get(key);
            if (state == null) {
                return Optional.empty();
            }

            // Get BLS snapshot for signer indices
            var snapshotOpt = state.getBLSSnapshot();
            if (snapshotOpt.isEmpty()) {
                return Optional.empty();
            }

            var snapshot = snapshotOpt.get();

            // Create AggregateWitnessReceipt
            return Optional.of(new AggregateWitnessReceipt(
                event,
                aggregateOpt.get(),
                snapshot.signerIndices(),
                SignatureFormat.BLS_12_381,
                System.currentTimeMillis(),
                (int) parameters.epoch()
            ));
        } finally {
            lock.readLock().unlock();
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
     * Complete BLS collection with proper resource cleanup.
     * <p>
     * Removes both CollectionState and BLSReceiptAggregator entries.
     * Use this instead of completeCollection() for BLS collections to ensure
     * immediate cleanup instead of waiting for time-based expiration.
     *
     * @param event Event coordinates
     */
    public void completeBLSCollection(EventCoordinates event) {
        Objects.requireNonNull(event, "event required");

        // Remove CollectionState (same as completeCollection)
        lock.writeLock().lock();
        try {
            var key = eventKey(event);
            collections.remove(key);
        } finally {
            lock.writeLock().unlock();
        }

        // Remove BLS aggregator resources if aggregator exists
        if (blsAggregator != null) {
            blsAggregator.removeAccumulation(event);
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
     * Get aggregate receipt store (Phase 3.4.5).
     * <p>
     * Provides access to persistent storage for AggregateWitnessReceipt instances.
     * Used for testing and direct storage access if needed.
     *
     * @return Aggregate receipt store instance (never null)
     */
    public AggregateReceiptStore getAggregateReceiptStore() {
        return aggregateReceiptStore;
    }

    /**
     * Get recursive receipt store (Phase 3.4.5).
     * <p>
     * Provides access to persistent storage for RecursiveAggregateReceipt instances.
     * Used for testing and direct storage access if needed.
     *
     * @return Recursive receipt store instance (never null)
     */
    public RecursiveReceiptStore getRecursiveReceiptStore() {
        return recursiveReceiptStore;
    }

    /**
     * Drain buffered signatures after view change completes.
     * <p>
     * Replays buffered signatures from the previous epoch, excluding Byzantine members.
     * Clears both the signature buffer and committee index map for the drained epoch.
     * </p>
     * <p>
     * This method is called after a view change completes to replay signatures that
     * were buffered during the transition period. Byzantine members detected during
     * the view change are excluded from replay.
     * </p>
     *
     * @param newEpoch New epoch number after view change
     * @param byzantineMembers Set of members detected as Byzantine (excluded from replay)
     */
    public void drainBuffer(long newEpoch, Set<Identifier> byzantineMembers) {
        if (signatureBuffer == null) {
            // Buffer feature not enabled - no-op
            return;
        }

        var previousEpoch = newEpoch - 1;
        var bufferedSignatures = signatureBuffer.getForEpoch(previousEpoch);

        if (bufferedSignatures.isEmpty()) {
            log.debug("No buffered signatures to drain for epoch {}", previousEpoch);
            return;
        }

        log.info("Draining {} buffered signatures from epoch {} to epoch {}",
                 bufferedSignatures.size(), previousEpoch, newEpoch);

        var replayedCount = 0;
        var skippedCount = 0;

        lock.writeLock().lock();
        try {
            for (var buffered : bufferedSignatures) {
                var member = buffered.memberId();
                var event = buffered.event();

                // Check if member should be included (exclude Byzantine members)
                if (degradedCalculator != null &&
                    !degradedCalculator.shouldInclude(member, byzantineMembers)) {
                    log.debug("Skipping buffered signature from Byzantine member {} for event {}",
                              member, event);
                    skippedCount++;
                    continue;
                }

                // Retrieve committeeIndex from map
                var bufferKey = new BufferKey(event, member);
                var committeeIndex = committeeIndexMap.get(bufferKey);

                if (committeeIndex == null) {
                    log.warn("Missing committeeIndex for buffered signature from {} for event {} - skipping",
                             member, event);
                    skippedCount++;
                    continue;
                }

                // Reconstruct BLSSignature from bytes
                var signatureBytes = buffered.signature();
                BLSSignature signature;
                try {
                    signature = BLSSignature.fromBytes(signatureBytes);
                } catch (Exception e) {
                    log.error("Failed to reconstruct BLSSignature from buffered bytes for member {} - skipping",
                              member, e);
                    skippedCount++;
                    continue;
                }

                // Replay the buffered signature by re-accumulating
                try {
                    var result = blsAggregator.accumulate(
                        event,
                        member,
                        committeeIndex,
                        signature,
                        parameters.threshold(),
                        newEpoch  // Use new epoch for replay
                    );

                    // Handle replay result
                    switch (result) {
                        case AccumulationResult.Accumulated acc -> {
                            replayedCount++;
                            var state = collections.computeIfAbsent(eventKey(event), k ->
                                new CollectionState(SignatureFormat.BLS_12_381, parameters.threshold())
                            );
                            state.recordSignature(member);
                        }
                        case AccumulationResult.ThresholdMet tm -> {
                            replayedCount++;
                            var state = collections.computeIfAbsent(eventKey(event), k ->
                                new CollectionState(SignatureFormat.BLS_12_381, parameters.threshold())
                            );
                            state.recordSignature(member);
                            state.markThresholdMet(tm.snapshot());
                        }
                        case AccumulationResult.AlreadyPresent ap -> {
                            // Duplicate during replay - acceptable, just skip
                            log.debug("Duplicate signature from {} during drain - already present", member);
                        }
                        default -> {
                            // Other results (InvalidSignature, EpochMismatch, etc.) - log and skip
                            log.warn("Unexpected result during buffered signature replay from {}: {}",
                                     member, result.getClass().getSimpleName());
                            skippedCount++;
                        }
                    }
                } catch (Exception e) {
                    log.error("Failed to replay buffered signature from {} for event {}",
                              member, event, e);
                    skippedCount++;
                }

                // Clean up committeeIndex map entry
                committeeIndexMap.remove(bufferKey);
            }
        } finally {
            lock.writeLock().unlock();
        }

        // Clear buffered signatures for drained epoch
        signatureBuffer.clearEpoch(previousEpoch);

        log.info("Drain complete: {} replayed, {} skipped", replayedCount, skippedCount);
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
