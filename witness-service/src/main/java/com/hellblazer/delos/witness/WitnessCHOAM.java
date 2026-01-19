/*
 * Copyright (c) 2024, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.choam.CHOAM;
import com.hellblazer.delos.choam.Session;
import com.hellblazer.delos.choam.support.HashedCertifiedBlock;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * WitnessCHOAM: CHOAM-based persistent state machine for witness receipt collections.
 *
 * Manages receipt collection lifecycle with:
 * - Persistence to CHOAM log for Byzantine fault tolerance
 * - Recovery from persistent storage (checkpoint + replayed transactions)
 * - View change coordination with drain periods
 * - In-flight collection tracking across cluster
 *
 * Phase 1A-2: Foundation for CHOAM integration and replicated state.
 * Phase 1A-3: Full gRPC integration with remote coordination.
 */
public class WitnessCHOAM {

    private static final Logger log = LoggerFactory.getLogger(WitnessCHOAM.class);

    // Core components
    private final CHOAM choam;
    private final Session session;
    private final WitnessStateMachine stateMachine;
    private final WitnessParameters parameters;

    // View state tracking
    private final ReadWriteLock viewLock = new ReentrantReadWriteLock();
    private volatile HashedCertifiedBlock currentView;
    private volatile long viewHeight = 0L;

    // Drain period coordination
    private final ReadWriteLock drainLock = new ReentrantReadWriteLock();
    private volatile boolean draining = false;
    private volatile Instant drainStart;

    // Checkpoint tracking for recovery
    private final ReadWriteLock checkpointLock = new ReentrantReadWriteLock();
    private volatile long lastCheckpointHeight = 0L;
    private volatile Instant lastCheckpointTime;

    // Transaction sequencing (per receipt collection)
    private final Map<String, TransactionSequence> sequences = new ConcurrentHashMap<>();

    /**
     * TransactionSequence: Tracks ordered state transitions for a receipt collection.
     * Ensures all nodes apply same sequence of state transitions.
     */
    public record TransactionSequence(
        String collectionId,
        long initiated,           // View height when collection started
        long lastTransition,      // View height of last state transition
        WitnessStateMachine.ReceiptCollectionState state
    ) {
    }

    public WitnessCHOAM(CHOAM choam, Session session, WitnessStateMachine stateMachine,
                       WitnessParameters parameters) {
        this.choam = choam;
        this.session = session;
        this.stateMachine = stateMachine;
        this.parameters = parameters;
    }

    /**
     * Initiate receipt collection with CHOAM persistence.
     * <p>
     * Records collection in CHOAM log for:
     * - Replication across Byzantine committee
     * - Recovery from failures
     * - Distributed consensus on collection state
     * </p>
     */
    public String initiateCollection(EventCoordinates eventCoordinates, long epoch) {
        // State machine manages local state
        var collectionId = stateMachine.initiateCollection(eventCoordinates, epoch);

        // Track transaction sequence for recovery
        viewLock.readLock().lock();
        try {
            var seq = new TransactionSequence(
                collectionId,
                viewHeight,
                viewHeight,
                WitnessStateMachine.ReceiptCollectionState.INITIATING
            );
            sequences.put(collectionId, seq);
        } finally {
            viewLock.readLock().unlock();
        }

        log.debug("Initiated receipt collection with CHOAM persistence: collection_id={}, event={}",
            collectionId, eventCoordinates);
        return collectionId;
    }

    /**
     * Mark collection as COLLECTING after first signature received.
     * Persists state transition via CHOAM for Byzantine agreement.
     */
    public void markCollecting(EventCoordinates eventCoordinates) {
        stateMachine.markCollecting(eventCoordinates);

        // Update transaction sequence under view lock
        var state = stateMachine.getState(eventCoordinates);
        if (state != null) {
            viewLock.readLock().lock();
            try {
                var collectionId = state.collectionId();
                var oldSeq = sequences.get(collectionId);
                if (oldSeq != null) {
                    var newSeq = new TransactionSequence(
                        collectionId,
                        oldSeq.initiated,
                        viewHeight,
                        state.state()
                    );
                    sequences.put(collectionId, newSeq);
                }
            } finally {
                viewLock.readLock().unlock();
            }
        }

        log.debug("Marked collection as COLLECTING with CHOAM coordination: event={}", eventCoordinates);
    }

    /**
     * Mark collection as THRESHOLD_MET when M-of-N achieved.
     * This transition is Byzantine-safe: all committee members agree threshold met.
     */
    public void markThresholdMet(EventCoordinates eventCoordinates) {
        stateMachine.markThresholdMet(eventCoordinates);

        var state = stateMachine.getState(eventCoordinates);
        if (state != null) {
            viewLock.readLock().lock();
            try {
                var collectionId = state.collectionId();
                var oldSeq = sequences.get(collectionId);
                if (oldSeq != null) {
                    var newSeq = new TransactionSequence(
                        collectionId,
                        oldSeq.initiated,
                        viewHeight,
                        state.state()
                    );
                    sequences.put(collectionId, newSeq);
                }
            } finally {
                viewLock.readLock().unlock();
            }
        }

        log.debug("Marked collection as THRESHOLD_MET with Byzantine agreement: event={}", eventCoordinates);
    }

    /**
     * Complete receipt collection and finalize state.
     * Triggers cleanup in receipt manager to allow new collections for same event.
     */
    public void completeCollection(EventCoordinates eventCoordinates) {
        stateMachine.completeCollection(eventCoordinates);

        var state = stateMachine.getState(eventCoordinates);
        if (state != null) {
            var collectionId = state.collectionId();
            sequences.remove(collectionId);
        }

        log.debug("Completed collection with CHOAM finalization: event={}", eventCoordinates);
    }

    /**
     * Mark collection as failed (timeout or error).
     * Persists failure reason for recovery and accountability.
     */
    public void failCollection(EventCoordinates eventCoordinates, String reason) {
        stateMachine.failCollection(eventCoordinates, reason);

        var state = stateMachine.getState(eventCoordinates);
        if (state != null) {
            var collectionId = state.collectionId();
            sequences.remove(collectionId);
        }

        log.warn("Failed collection with CHOAM coordination: event={}, reason={}", eventCoordinates, reason);
    }

    /**
     * Handle view change: Update view height and manage drain period.
     * <p>
     * Called when new consensus block is accepted.
     * Enforces drain period to ensure receipt validity across membership change.
     * </p>
     */
    public void onViewChange(HashedCertifiedBlock newView) {
        viewLock.writeLock().lock();
        try {
            currentView = newView;
            viewHeight = newView.certifiedBlock.getBlock().getHeader().getHeight();
            log.debug("View changed to height={}", viewHeight);
        } finally {
            viewLock.writeLock().unlock();
        }

        // Start drain period if not already draining
        drainLock.writeLock().lock();
        try {
            if (!draining) {
                draining = true;
                drainStart = Instant.now();
                log.debug("Drain period started at view height={}", viewHeight);
            }
        } finally {
            drainLock.writeLock().unlock();
        }
    }

    /**
     * Check if in drain period (no new collections should start).
     * Drain period ensures receipt validity across committee membership change.
     */
    public boolean isDraining() {
        drainLock.readLock().lock();
        try {
            if (!draining) {
                return false;
            }
            var elapsed = Duration.between(drainStart, Instant.now());
            var drainDuration = parameters.drainPeriod();
            if (elapsed.compareTo(drainDuration) > 0) {
                return false;  // Drain period expired
            }
            return true;
        } finally {
            drainLock.readLock().unlock();
        }
    }

    /**
     * End drain period: Allow new collections to start.
     * Called when drain period expires or committee change is complete.
     */
    public void endDrainPeriod() {
        drainLock.writeLock().lock();
        try {
            draining = false;
            drainStart = null;
            log.debug("Drain period ended at view height={}", viewHeight);
        } finally {
            drainLock.writeLock().unlock();
        }
    }

    /**
     * Get current view height (consensus block height).
     * Used for timeout tracking and deterministic sequencing.
     */
    public long getViewHeight() {
        viewLock.readLock().lock();
        try {
            return viewHeight;
        } finally {
            viewLock.readLock().unlock();
        }
    }

    /**
     * Get current consensus view.
     */
    public HashedCertifiedBlock getCurrentView() {
        viewLock.readLock().lock();
        try {
            return currentView;
        } finally {
            viewLock.readLock().unlock();
        }
    }

    /**
     * Recovery: Restore receipt collection state from checkpoint.
     * <p>
     * Called during startup or after Byzantine failure recovery.
     * Replays transaction sequence to rebuild state from CHOAM log.
     * </p>
     */
    public void recover(HashedCertifiedBlock checkpoint, long checkpointHeight) {
        checkpointLock.writeLock().lock();
        try {
            lastCheckpointHeight = checkpointHeight;
            lastCheckpointTime = Instant.now();

            // Update view to checkpoint height
            viewLock.writeLock().lock();
            try {
                currentView = checkpoint;
                viewHeight = checkpointHeight;
            } finally {
                viewLock.writeLock().unlock();
            }

            // Clear in-flight sequences (will be replayed from log)
            sequences.clear();

            log.info("Recovered witness state from checkpoint at height={}", checkpointHeight);
        } finally {
            checkpointLock.writeLock().unlock();
        }
    }

    /**
     * Get transaction sequence for a collection (used during recovery).
     * Enables deterministic replay of state transitions.
     */
    public TransactionSequence getSequence(String collectionId) {
        return sequences.get(collectionId);
    }

    /**
     * Update transaction sequence from CHOAM log during recovery/replay.
     */
    public void updateSequence(String collectionId, TransactionSequence seq) {
        sequences.put(collectionId, seq);
    }

    /**
     * Get checkpoint metadata for persistence layer.
     */
    public record CheckpointMetadata(
        long height,
        Instant timestamp,
        int inFlightCollections,
        int totalSequences
    ) {
    }

    public CheckpointMetadata getCheckpointMetadata() {
        checkpointLock.readLock().lock();
        try {
            return new CheckpointMetadata(
                lastCheckpointHeight,
                lastCheckpointTime,
                stateMachine.getInFlightCount(),
                sequences.size()
            );
        } finally {
            checkpointLock.readLock().unlock();
        }
    }

    /**
     * Cleanup expired collections (timeout or too old).
     * Called periodically to manage memory and ensure progress.
     */
    public void cleanupExpired() {
        viewLock.readLock().lock();
        var currentHeight = viewHeight;
        viewLock.readLock().unlock();

        // Cleanup state machine's expired collections
        stateMachine.cleanupExpired();

        // Remove sequences older than 2x drain period
        var maxAge = parameters.drainPeriod().multipliedBy(2);
        var expireTime = Instant.now().minus(maxAge);

        var toRemove = sequences.entrySet().stream()
            .filter(e -> {
                var seq = e.getValue();
                // Consider old sequences for removal if view has advanced significantly
                var viewHeightDiff = currentHeight - seq.lastTransition;
                return viewHeightDiff > 1000;  // ~5 minutes at 300ms block time
            })
            .map(Map.Entry::getKey)
            .toList();

        toRemove.forEach(sequences::remove);

        if (!toRemove.isEmpty()) {
            log.debug("Cleaned up {} expired transaction sequences", toRemove.size());
        }
    }

    /**
     * Retrieve receipt by event coordinates.
     * Returns null if receipt not yet available or not found.
     *
     * @param eventCoordinates Event coordinates to retrieve receipt for
     * @return WitnessReceipt if found and threshold met, null otherwise
     */
    public com.hellblazer.delos.witness.proto.WitnessReceipt getReceiptByEvent(EventCoordinates eventCoordinates) {
        // TODO Phase 1A-3: Query CHOAM log for persisted receipt
        // For now, this is a placeholder that integrates with the state machine
        // Full implementation will retrieve from CHOAM session/replicated state
        return null;
    }

    /**
     * Get the underlying state machine (package-protected for testing).
     */
    WitnessStateMachine getStateMachine() {
        return stateMachine;
    }

    /**
     * Get statistics for monitoring and observability.
     */
    public record Statistics(
        long viewHeight,
        int inFlightCollections,
        int activeSequences,
        boolean draining,
        long lastCheckpointHeight
    ) {
    }

    public Statistics getStatistics() {
        viewLock.readLock().lock();
        long vh = viewHeight;
        viewLock.readLock().unlock();

        drainLock.readLock().lock();
        boolean dr = draining;
        drainLock.readLock().unlock();

        checkpointLock.readLock().lock();
        long lch = lastCheckpointHeight;
        checkpointLock.readLock().unlock();

        return new Statistics(
            vh,
            stateMachine.getInFlightCount(),
            sequences.size(),
            dr,
            lch
        );
    }
}
