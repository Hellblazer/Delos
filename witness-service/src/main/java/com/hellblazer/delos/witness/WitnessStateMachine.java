/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * WitnessStateMachine: State machine for receipt collection lifecycle.
 *
 * Manages receipt collection state across CHOAM replication:
 * - Tracks in-flight receipt collections by event
 * - Coordinates state transitions (INITIATING -> COLLECTING -> THRESHOLD_MET -> COMPLETE)
 * - Persists receipt state to CHOAM log for replay
 * - Handles timeouts and cleanup
 *
 * Phase 1A-2: Foundation for CHOAM integration and persistence.
 * Phase 1A-3: Full gRPC integration with Fireflies coordination.
 */
public class WitnessStateMachine {

    private static final Logger log = LoggerFactory.getLogger(WitnessStateMachine.class);

    // Receipt collection state enum
    public enum ReceiptCollectionState {
        INITIATING,      // Collection started, awaiting first signatures
        COLLECTING,      // Signatures being collected
        THRESHOLD_MET,   // M-of-N threshold achieved
        COMPLETE,        // Collection finalized and stored
        TIMEOUT,         // Collection timed out
        FAILED           // Collection failed
    }

    // Sealed receipt state record
    public record ReceiptState(
        EventCoordinates eventCoordinates,
        ReceiptCollectionState state,
        int signatureCount,
        int requiredThreshold,
        Instant createdAt,
        Instant updatedAt,
        long epoch,
        String collectionId
    ) {}

    private final WitnessReceiptManager receiptManager;
    private final WitnessParameters parameters;
    private final DigestAlgorithm algorithm;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    // Map: event key -> ReceiptState for tracking
    private final Map<String, ReceiptState> states = new ConcurrentHashMap<>();

    // Statistics
    private long totalInitiated = 0;
    private long totalCompleted = 0;
    private long totalFailed = 0;
    private long totalTimeoutCount = 0;

    public WitnessStateMachine(WitnessReceiptManager receiptManager,
                              WitnessParameters parameters,
                              DigestAlgorithm algorithm) {
        this.receiptManager = receiptManager;
        this.parameters = parameters;
        this.algorithm = algorithm;
    }

    /**
     * Initiate receipt collection for an event.
     * <p>
     * Creates initial INITIATING state for receipt collection.
     * Returns unique collection ID for async tracking.
     * </p>
     */
    public String initiateCollection(EventCoordinates eventCoordinates, long epoch) {
        lock.writeLock().lock();
        try {
            var key = eventKey(eventCoordinates);
            var collectionId = generateCollectionId(eventCoordinates);

            var state = new ReceiptState(
                eventCoordinates,
                ReceiptCollectionState.INITIATING,
                0,
                parameters.threshold(),
                Instant.now(),
                Instant.now(),
                epoch,
                collectionId
            );

            states.put(key, state);
            totalInitiated++;

            log.debug("Initiated receipt collection for event {}: collection_id={}", key, collectionId);
            return collectionId;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Mark collection as COLLECTING after first signature received.
     */
    public void markCollecting(EventCoordinates eventCoordinates) {
        lock.writeLock().lock();
        try {
            var key = eventKey(eventCoordinates);
            var current = states.get(key);
            if (current != null && current.state == ReceiptCollectionState.INITIATING) {
                var collectionState = receiptManager.getCollectionState(eventCoordinates);
                var updated = new ReceiptState(
                    current.eventCoordinates,
                    ReceiptCollectionState.COLLECTING,
                    collectionState.signatureCount(),
                    current.requiredThreshold,
                    current.createdAt,
                    Instant.now(),
                    current.epoch,
                    current.collectionId
                );
                states.put(key, updated);
                log.debug("Marked collection as COLLECTING: event={}", key);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Mark collection as THRESHOLD_MET when M-of-N achieved.
     */
    public void markThresholdMet(EventCoordinates eventCoordinates) {
        lock.writeLock().lock();
        try {
            var key = eventKey(eventCoordinates);
            var current = states.get(key);
            if (current != null && current.state == ReceiptCollectionState.COLLECTING) {
                var collectionState = receiptManager.getCollectionState(eventCoordinates);
                var updated = new ReceiptState(
                    current.eventCoordinates,
                    ReceiptCollectionState.THRESHOLD_MET,
                    collectionState.signatureCount(),
                    current.requiredThreshold,
                    current.createdAt,
                    Instant.now(),
                    current.epoch,
                    current.collectionId
                );
                states.put(key, updated);
                log.debug("Marked collection as THRESHOLD_MET: event={}, signatures={}/{}",
                    key, updated.signatureCount, updated.requiredThreshold);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Complete receipt collection (finalize state).
     */
    public void completeCollection(EventCoordinates eventCoordinates) {
        lock.writeLock().lock();
        try {
            var key = eventKey(eventCoordinates);
            var current = states.get(key);
            if (current != null &&
                (current.state == ReceiptCollectionState.THRESHOLD_MET ||
                 current.state == ReceiptCollectionState.COLLECTING)) {
                var collectionState = receiptManager.getCollectionState(eventCoordinates);
                var updated = new ReceiptState(
                    current.eventCoordinates,
                    ReceiptCollectionState.COMPLETE,
                    collectionState.signatureCount(),
                    current.requiredThreshold,
                    current.createdAt,
                    Instant.now(),
                    current.epoch,
                    current.collectionId
                );
                states.put(key, updated);
                totalCompleted++;

                // Remove from receipt manager's in-flight tracking
                receiptManager.completeCollection(eventCoordinates);
                log.debug("Completed collection: event={}", key);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Mark collection as failed (timeout or error).
     */
    public void failCollection(EventCoordinates eventCoordinates, String reason) {
        lock.writeLock().lock();
        try {
            var key = eventKey(eventCoordinates);
            var current = states.get(key);
            if (current != null && current.state != ReceiptCollectionState.COMPLETE) {
                var collectionState = receiptManager.getCollectionState(eventCoordinates);
                var newState = reason.equals("timeout") ?
                    ReceiptCollectionState.TIMEOUT : ReceiptCollectionState.FAILED;
                var updated = new ReceiptState(
                    current.eventCoordinates,
                    newState,
                    collectionState.signatureCount(),
                    current.requiredThreshold,
                    current.createdAt,
                    Instant.now(),
                    current.epoch,
                    current.collectionId
                );
                states.put(key, updated);
                totalFailed++;
                if (newState == ReceiptCollectionState.TIMEOUT) {
                    totalTimeoutCount++;
                }

                // Clean up in receipt manager
                receiptManager.completeCollection(eventCoordinates);
                log.warn("Failed collection: event={}, reason={}", key, reason);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Get current state for an event.
     */
    public ReceiptState getState(EventCoordinates eventCoordinates) {
        lock.readLock().lock();
        try {
            var key = eventKey(eventCoordinates);
            return states.get(key);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get all in-flight collection states.
     */
    public Map<String, ReceiptState> getInFlightStates() {
        lock.readLock().lock();
        try {
            var inFlight = new HashMap<String, ReceiptState>();
            for (var entry : states.entrySet()) {
                var state = entry.getValue();
                if (state.state != ReceiptCollectionState.COMPLETE &&
                    state.state != ReceiptCollectionState.FAILED &&
                    state.state != ReceiptCollectionState.TIMEOUT) {
                    inFlight.put(entry.getKey(), state);
                }
            }
            return inFlight;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Cleanup expired collections based on timeout.
     */
    public void cleanupExpired() {
        lock.writeLock().lock();
        try {
            var now = Instant.now();
            var timeout = Duration.ofMillis(parameters.drainPeriod().toMillis() * 2);
            var toRemove = new ArrayList<String>();

            for (var entry : states.entrySet()) {
                var state = entry.getValue();
                if (state.state == ReceiptCollectionState.COMPLETE ||
                    state.state == ReceiptCollectionState.FAILED ||
                    state.state == ReceiptCollectionState.TIMEOUT) {
                    if (Duration.between(state.updatedAt, now).compareTo(timeout) > 0) {
                        toRemove.add(entry.getKey());
                    }
                }
            }

            toRemove.forEach(states::remove);
            if (!toRemove.isEmpty()) {
                log.debug("Cleaned up {} expired collections", toRemove.size());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Get in-flight collection count.
     */
    public int getInFlightCount() {
        lock.readLock().lock();
        try {
            return (int) states.values().stream()
                .filter(s -> s.state != ReceiptCollectionState.COMPLETE &&
                           s.state != ReceiptCollectionState.FAILED &&
                           s.state != ReceiptCollectionState.TIMEOUT)
                .count();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get the underlying receipt manager (package-protected for testing).
     */
    WitnessReceiptManager getReceiptManager() {
        return receiptManager;
    }

    /**
     * Get statistics for monitoring.
     */
    public record Statistics(
        long totalInitiated,
        long totalCompleted,
        long totalFailed,
        long totalTimeouts,
        int currentInFlight
    ) {}

    public Statistics getStatistics() {
        lock.readLock().lock();
        try {
            return new Statistics(
                totalInitiated,
                totalCompleted,
                totalFailed,
                totalTimeoutCount,
                getInFlightCount()
            );
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Generate unique event key for tracking.
     */
    private String eventKey(EventCoordinates eventCoordinates) {
        return eventCoordinates.getDigest().toString() + ":" + eventCoordinates.getSequenceNumber();
    }

    /**
     * Generate unique collection ID.
     */
    private String generateCollectionId(EventCoordinates eventCoordinates) {
        return "witness-" + eventCoordinates.getDigest().toString().substring(0, 8) +
               "-" + System.nanoTime();
    }
}
