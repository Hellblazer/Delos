/*
 * Copyright (c) 2024, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies;

import com.hellblazer.delos.bloomFilters.BloomFilter;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.fireflies.View.Participant;
import com.hellblazer.delos.fireflies.proto.AccusationGossip;

/**
 * Manages accusation lifecycle - issuing, rebuttals, and shunning. Implements the Fireflies accusation protocol.
 * <p>
 * The accusation protocol works as follows:
 * <ol>
 *   <li>When gossip fails with a successor, the predecessor issues an accusation</li>
 *   <li>A rebuttal timer is started (2 * TTL rounds by default)</li>
 *   <li>The accused member can rebut by publishing a new note with higher epoch</li>
 *   <li>If rebuttal fails, the member is garbage collected (shunned)</li>
 *   <li>Shunned members must rejoin through the full join protocol</li>
 * </ol>
 * <p>
 * <b>Thread-Safety:</b> All methods are thread-safe.
 * <p>
 * <b>Locking:</b> Uses {@link ViewContext#stable(Runnable)} for queries. pendingRebuttals is a ConcurrentSkipListMap.
 *
 * @author hal.hildebrand
 * @see ViewContext
 */
public interface AccusationTracker {

    // === Accusation Lifecycle ===

    /**
     * Issue an accusation against a member on a specific ring.
     * <p>
     * Accusations are only issued when:
     * <ul>
     *   <li>The member is not already accused on this ring</li>
     *   <li>The member is not disabled on this ring (mask bit not set)</li>
     *   <li>This node is the predecessor on the ring</li>
     * </ul>
     * <p>
     * When issued:
     * <ol>
     *   <li>Accusation is signed by local node</li>
     *   <li>Added to the member's accusation set</li>
     *   <li>Rebuttal timer started if not already running</li>
     * </ol>
     *
     * @param member the member to accuse
     * @param ring   the ring index where gossip failed
     * @param cause  the reason for accusation (for logging)
     */
    void accuse(Participant member, int ring, Throwable cause);

    /**
     * Process an inbound accusation from gossip.
     * <p>
     * Validates the accusation:
     * <ul>
     *   <li>Accuser and accused exist in context</li>
     *   <li>Ring number is valid</li>
     *   <li>Epoch matches accused member's current epoch</li>
     *   <li>Accused is not disabled on the ring</li>
     *   <li>Accuser is the predecessor (or closer than current accuser)</li>
     * </ul>
     *
     * @param accusation the accusation to process
     * @return true if the accusation was accepted
     */
    boolean processAccusation(AccusationWrapper accusation);

    /**
     * Amplify accusations for a target across all monitored rings.
     * <p>
     * Called when a member fails to rebut within timeout. For each ring where this node monitors the target (is the
     * predecessor), issue an accusation if not already issued.
     *
     * @param target the member to amplify accusations for
     */
    void amplify(Participant target);

    // === Rebuttal Management ===

    /**
     * Stop the rebuttal timer for a member (successful rebuttal).
     * <p>
     * Called when a member publishes a new note with higher epoch. Clears all accusations for the member.
     *
     * @param member the member whose timer to stop
     */
    void stopRebuttalTimer(Participant member);

    /**
     * Check and propagate accusation invalidations.
     * <p>
     * When a member successfully rebuts, other accusations may become invalid. This implements the invalidation
     * algorithm from the Fireflies paper:
     * <pre>
     * For member M that rebutted:
     *   For each ring R:
     *     For each member Q between M and M's first live successor:
     *       If Q is accused and accuser is between Q and M:
     *         Invalidate the accusation for Q on ring R
     * </pre>
     *
     * @param member the member who rebutted
     */
    void checkInvalidations(Participant member);

    /**
     * @return true if there are pending rebuttal timers
     */
    boolean hasPendingRebuttals();

    // === Garbage Collection ===

    /**
     * Garbage collect a member (shun after failed rebuttal).
     * <p>
     * Called when rebuttal timer expires:
     * <ol>
     *   <li>Cancel the pending rebuttal timer</li>
     *   <li>Amplify accusations to all monitored rings</li>
     *   <li>Mark member as offline in context</li>
     *   <li>Add to shunned set (permanent exclusion)</li>
     *   <li>Notify ViewManagement for view change consideration</li>
     * </ol>
     *
     * @param member the member to garbage collect
     */
    void garbageCollect(Participant member);

    // === Gossip Support ===

    /**
     * Process accusations for gossip reconciliation.
     * <p>
     * Compares the inbound bloom filter against known accusations and returns accusations that the sender is missing.
     *
     * @param bff inbound bloom filter of sender's known accusations
     * @param fpr false positive rate for response bloom filter
     * @return gossip structure with accusations to send and response bloom filter
     */
    AccusationGossip processAccusations(BloomFilter<Digest> bff, double fpr);

    /**
     * Build bloom filter of known accusations for gossip initiation.
     *
     * @param seed random seed for bloom filter
     * @param p    false positive rate
     * @return bloom filter containing hashes of all known accusations
     */
    BloomFilter<Digest> getAccusationsBff(long seed, double p);
}
