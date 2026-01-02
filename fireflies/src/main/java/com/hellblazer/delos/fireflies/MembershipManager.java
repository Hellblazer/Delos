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
import com.hellblazer.delos.fireflies.proto.NoteGossip;

import java.util.BitSet;
import java.util.stream.Stream;

/**
 * Manages member state in the ring-based context. Handles note updates, member lifecycle, and shunning.
 * <p>
 * This component is responsible for:
 * <ul>
 *   <li>Adding and validating member notes</li>
 *   <li>Removing members from the context</li>
 *   <li>Recovering members from failed state</li>
 *   <li>Managing the shunned set (permanently excluded members)</li>
 *   <li>Gossip support for note reconciliation</li>
 * </ul>
 * <p>
 * <b>Thread-Safety:</b> All methods are thread-safe.
 * <p>
 * <b>Locking:</b> Uses {@link ViewContext#stable(Runnable)} for reads. No direct write lock access - modifications
 * trigger via callbacks to View.
 *
 * @author hal.hildebrand
 * @see ViewContext
 */
public interface MembershipManager {

    // === Member Lifecycle ===

    /**
     * Add a member note to the view. Validates signature and epoch before adding.
     * <p>
     * This method:
     * <ol>
     *   <li>Checks if member is shunned</li>
     *   <li>Validates the note signature</li>
     *   <li>Checks epoch ordering (new note must have higher epoch)</li>
     *   <li>Adds or updates the member in the context</li>
     *   <li>Clears any existing accusations on successful note update</li>
     * </ol>
     *
     * @param note the note to add
     * @return true if the note was added successfully
     */
    boolean addToView(NoteWrapper note);

    /**
     * Add a member note if it matches the current view.
     * <p>
     * This is a filtered version of {@link #addToView(NoteWrapper)} that first validates the note's view matches the
     * current view.
     *
     * @param note the note to add
     * @return true if added successfully
     */
    boolean addToCurrentView(NoteWrapper note);

    /**
     * Remove a member from the context. Cancels any pending rebuttals and cleans up state.
     * <p>
     * This is a permanent removal - the member must rejoin to participate again.
     *
     * @param id the member to remove
     */
    void remove(Digest id);

    /**
     * Recover a member from failed state. Reactivates the member if not shunned.
     * <p>
     * Called when a member successfully rebuts an accusation.
     *
     * @param member the member to recover
     */
    void recover(Participant member);

    // === Shunning ===

    /**
     * Check if a member is shunned (permanently excluded).
     * <p>
     * Shunned members cannot rejoin through normal gossip - they must go through the full join protocol.
     *
     * @param id the member ID
     * @return true if the member is shunned
     */
    boolean isShunned(Digest id);

    /**
     * Shun a member (permanent exclusion from current view).
     * <p>
     * Called by AccusationTracker when a member fails to rebut within the timeout.
     *
     * @param id the member to shun
     */
    void shun(Digest id);

    /**
     * @return stream of all shunned member IDs
     */
    Stream<Digest> streamShunned();

    /**
     * Check if a shunned member can attempt recovery.
     * <p>
     * Recovery is allowed if:
     * <ul>
     *   <li>Member is currently shunned</li>
     *   <li>Configured recovery duration has elapsed since shunning</li>
     *   <li>Sufficient time has passed since last recovery attempt (rate limiting)</li>
     * </ul>
     *
     * @param memberId the member to check
     * @return true if recovery is allowed
     */
    boolean canRecover(Digest memberId);

    /**
     * Attempt recovery of a shunned member.
     * <p>
     * Validates the recovery request and removes from shunned set if eligible.
     * Requires:
     * <ul>
     *   <li>Member must be currently shunned</li>
     *   <li>Recovery period must have elapsed</li>
     *   <li>Note must have valid signature for the member's identity</li>
     *   <li>Rate limiting requirements must be satisfied</li>
     * </ul>
     *
     * @param note the recovery note with updated epoch and valid signature
     * @return true if recovery succeeded
     */
    boolean attemptRecovery(NoteWrapper note);

    // === Initialization ===

    /**
     * Set the accusation tracker after construction to break circular dependency.
     * <p>
     * This is called immediately after AccusationTrackerImpl is constructed.
     *
     * @param accusationTracker the accusation tracker
     */
    void setAccusationTracker(AccusationTracker accusationTracker);

    // === Gossip Support ===

    /**
     * Process notes for gossip reconciliation.
     * <p>
     * Compares the inbound bloom filter against known notes and returns notes that the sender is missing.
     *
     * @param from the gossip source
     * @param bff  inbound bloom filter of sender's known notes
     * @param fpr  false positive rate for response bloom filter
     * @return gossip structure with notes to send and response bloom filter
     */
    NoteGossip processNotes(Digest from, BloomFilter<Digest> bff, double fpr);

    /**
     * Build bloom filter of known notes for gossip initiation.
     *
     * @param seed random seed for bloom filter
     * @param p    false positive rate
     * @return bloom filter containing hashes of all known notes
     */
    BloomFilter<Digest> getNotesBff(long seed, double p);

    // === Validation ===

    /**
     * Validate a member's participation mask.
     * <p>
     * A valid mask must:
     * <ul>
     *   <li>Have cardinality equal to context.majority()</li>
     *   <li>Have length <= context.getRingCount()</li>
     * </ul>
     *
     * @param mask the mask to validate
     * @return true if the mask is valid
     */
    boolean isValidMask(BitSet mask);
}
