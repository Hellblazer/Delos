/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;

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
    private final ReadWriteLock lock;

    // Map: EventCoordinates -> CollectionState
    private final Map<String, CollectionState> collections = new ConcurrentHashMap<>();

    /**
     * Create receipt manager with parameters.
     *
     * @param parameters Witness configuration (threshold, drain period, etc.)
     */
    public WitnessReceiptManager(WitnessParameters parameters) {
        this.parameters = parameters;
        this.lock = new ReentrantReadWriteLock();
    }

    /**
     * Add witness signature to receipt collection for event.
     * <p>
     * Signatures are deduplicated by member - each member can contribute one signature.
     * </p>
     *
     * @param event  Event coordinates being witnessed
     * @param member Witness member who signed
     * @param signature Signature digest
     */
    public void addSignature(EventCoordinates event, Identifier member, Digest signature) {
        lock.writeLock().lock();
        try {
            var key = eventKey(event);
            var state = collections.computeIfAbsent(key, k ->
                new CollectionState(parameters.threshold())
            );

            // Add signature, deduplicating by member
            state.addSignature(member, signature);
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
                new CollectionState(parameters.threshold())
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
     */
    public static class CollectionState {
        private final int threshold;
        private final Set<Identifier> signers = ConcurrentHashMap.newKeySet();
        private boolean thresholdAchieved = false;

        public CollectionState(int threshold) {
            this.threshold = threshold;
        }

        /**
         * Add signature from member (deduplicated by member).
         */
        public void addSignature(Identifier member, Digest signature) {
            boolean isNew = signers.add(member);
            if (isNew && signers.size() >= threshold) {
                thresholdAchieved = true;
            }
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
    }
}
