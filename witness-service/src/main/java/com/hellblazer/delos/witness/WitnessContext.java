/*
 * Copyright (c) 2024, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.SequencedSet;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * Witness subset context with dynamic committee selection using ring iterator pattern.
 * <p>
 * Provides deterministic committee selection for witness network operations:
 * - Committee selection via ring iterator: committee = context.bftSubset(H(eventCoordinates))
 * - Per-event variation prevents predictable targeting attacks
 * - Epoch synchronization with Fireflies view changes
 * - Drain period for graceful view transitions
 * </p>
 * <p>
 * Thread-safe: Committee selection and view changes use read-write locks.
 * </p>
 */
public class WitnessContext {

    private final Context<?> firefliesContext;
    private final WitnessParameters parameters;
    private final DigestAlgorithm digestAlgorithm;
    private final ReadWriteLock lock;

    private volatile long currentEpoch;
    private volatile Set<Identifier> currentMembers;

    /**
     * Create witness context wrapping Fireflies context.
     *
     * @param firefliesContext Parent Fireflies context (provides ring iterator)
     * @param parameters       Witness configuration (k, threshold, epoch, drain period)
     */
    public WitnessContext(Context<?> firefliesContext, WitnessParameters parameters) {
        this(firefliesContext, parameters, DigestAlgorithm.DEFAULT);
    }

    /**
     * Create witness context with specific digest algorithm.
     *
     * @param firefliesContext Parent Fireflies context
     * @param parameters       Witness configuration
     * @param digestAlgorithm  Algorithm for event hashing
     */
    public WitnessContext(Context<?> firefliesContext, WitnessParameters parameters,
                          DigestAlgorithm digestAlgorithm) {
        this.firefliesContext = firefliesContext;
        this.parameters = parameters;
        this.digestAlgorithm = digestAlgorithm;
        this.lock = new ReentrantReadWriteLock();
        this.currentEpoch = parameters.epoch();

        // Initialize current members from Fireflies context
        this.currentMembers = firefliesContext.allMembers()
            .map(Member::getId)
            .map(this::toIdentifier)
            .collect(Collectors.toSet());
    }

    /**
     * Select witness committee for specific event using ring iterator.
     * <p>
     * CRITICAL: Deterministic - same event always yields same committee.
     * Algorithm:
     * 1. Hash event coordinates deterministically
     * 2. Use hash as point on rings: context.bftSubset(hash)
     * 3. Return k unique successors from rings
     * </p>
     *
     * @param eventCoordinates The event being witnessed
     * @return k-member committee selected via context.bftSubset(hash)
     */
    public Set<Identifier> selectCommittee(EventCoordinates eventCoordinates) {
        lock.readLock().lock();
        try {
            // Compute deterministic hash from event coordinates
            var eventHash = hashEventCoordinates(eventCoordinates);

            // Select committee via proven ring iterator pattern
            var bftSubset = firefliesContext.bftSubset(eventHash);

            // Convert Member to Identifier
            return bftSubset.stream()
                .map(Member::getId)
                .map(this::toIdentifier)
                .limit(parameters.k())
                .collect(Collectors.toSet());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Validate that witness is selected for event's committee.
     * Used by receipt generation to determine if witness should sign.
     *
     * @param witness          Witness identifier to check
     * @param eventCoordinates Event coordinates
     * @return true if witness is in committee for this event
     */
    public boolean isCommitteeMember(Identifier witness, EventCoordinates eventCoordinates) {
        var committee = selectCommittee(eventCoordinates);
        return committee.contains(witness);
    }

    /**
     * Get current fault tolerance parameter.
     * f = (k-1)/3, computed from parameters.
     *
     * @return Maximum Byzantine failures tolerated
     */
    public int getFaultTolerance() {
        return parameters.toleranceLevel();
    }

    /**
     * Get current threshold (M) for receipt validity.
     * M > (2*k)/3 per KERI requirement.
     *
     * @return Signature threshold
     */
    public int getThreshold() {
        return parameters.threshold();
    }

    /**
     * Get current epoch (synchronized with Fireflies view changes).
     *
     * @return Current epoch number
     */
    public long getEpoch() {
        return currentEpoch;
    }

    /**
     * Handle Fireflies view change: update epoch and drain period.
     * Called when Fireflies detects view membership change.
     *
     * @param newMembers Updated Fireflies view members
     * @param newEpoch   New epoch number
     * @return drain period duration (500ms) for completing in-flight collections
     */
    public Duration handleViewChange(Set<Identifier> newMembers, long newEpoch) {
        lock.writeLock().lock();
        try {
            currentMembers = Set.copyOf(newMembers);
            currentEpoch = newEpoch;
            return parameters.drainPeriod();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Validate all committee members are current Fireflies members.
     * Used during receipt validation.
     *
     * @param committee Committee to validate
     * @return true if all members are current
     */
    public boolean validateCommittee(Set<Identifier> committee) {
        lock.readLock().lock();
        try {
            return currentMembers.containsAll(committee);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Hash event coordinates deterministically for committee selection.
     * <p>
     * Combines:
     * - Event identifier
     * - Sequence number
     * - Event digest
     * - Event ilk (inception/rotation/interaction)
     * </p>
     *
     * @param eventCoordinates Event to hash
     * @return Deterministic hash for ring iterator
     */
    private Digest hashEventCoordinates(EventCoordinates eventCoordinates) {
        // Extract components
        var identifierDigest = eventCoordinates.getIdentifier().getDigest(digestAlgorithm);
        var identifierBytes = identifierDigest.getBytes();
        var sequenceNumber = eventCoordinates.getSequenceNumber().longValue();
        var digestBytes = eventCoordinates.getDigest().getBytes();
        var ilkBytes = eventCoordinates.getIlk().getBytes();

        // Combine into deterministic hash
        var buffer = ByteBuffer.allocate(
            identifierBytes.length + 8 + digestBytes.length + ilkBytes.length
        );
        buffer.put(identifierBytes);
        buffer.putLong(sequenceNumber);
        buffer.put(digestBytes);
        buffer.put(ilkBytes);

        return digestAlgorithm.digest(buffer.array());
    }

    /**
     * Convert Digest to Identifier.
     * Helper for compatibility between membership and stereotomy types.
     *
     * @param digest Digest to convert
     * @return Identifier wrapping digest
     */
    private Identifier toIdentifier(Digest digest) {
        return new SelfAddressingIdentifier(digest);
    }
}
