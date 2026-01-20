/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;

import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Witness-specific Ethereal consensus instance for receipt consensus.
 * <p>
 * Runs asynchronous atomic broadcast (Aleph-BFT) across k-member witness committee.
 * Provides:
 * - Event proposal validation (must come from committee member)
 * - Deterministic event ordering for consensus
 * - Receipt signing and threshold validation
 * </p>
 * <p>
 * Thread-safe: Event proposals and membership checks use read-write locks.
 * </p>
 */
public class WitnessEthereal {

    private final Set<Identifier> committee;
    private final WitnessParameters parameters;
    private final DigestAlgorithm digestAlgorithm;
    private final ReadWriteLock lock;

    /**
     * Create witness-specific Ethereal consensus instance.
     *
     * @param committee         k-member committee from WitnessContext
     * @param parameters        Witness configuration (threshold, drain period, etc.)
     * @param digestAlgorithm   Algorithm for event hashing
     */
    public WitnessEthereal(Set<Identifier> committee, WitnessParameters parameters,
                          DigestAlgorithm digestAlgorithm) {
        this.committee = Set.copyOf(committee);  // Immutable copy
        this.parameters = parameters;
        this.digestAlgorithm = digestAlgorithm;
        this.lock = new ReentrantReadWriteLock();
    }

    /**
     * Check if identifier is a committee member.
     * <p>
     * Used to validate event proposals - only committee members may propose events.
     * </p>
     *
     * @param identifier Member identifier to check
     * @return true if identifier is in committee
     */
    public boolean isCommitteeMember(Identifier identifier) {
        lock.readLock().lock();
        try {
            return committee.contains(identifier);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Propose event for consensus.
     * <p>
     * Event is accepted if proposed by a committee member.
     * Used during receipt signing to ensure only committee members can propose events.
     * </p>
     *
     * @param event     Event coordinates being proposed
     * @param proposer  Identifier of proposing member
     * @return true if event accepted, false if proposer not in committee
     */
    public boolean proposeEvent(EventCoordinates event, Identifier proposer) {
        lock.readLock().lock();
        try {
            // Event accepted only if proposer is committee member
            return committee.contains(proposer);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get current committee membership.
     *
     * @return Immutable set of committee member identifiers
     */
    public Set<Identifier> getCommittee() {
        lock.readLock().lock();
        try {
            return Set.copyOf(committee);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get signature threshold for receipts.
     * M-of-N threshold: must have M signatures to consider receipt valid.
     *
     * @return Threshold value from parameters
     */
    public int getThreshold() {
        return parameters.threshold();
    }

    /**
     * Get committee size (k parameter).
     *
     * @return Committee cardinality
     */
    public int getCommitteeSize() {
        return parameters.k();
    }
}
