/*
 * Copyright (c) 2024, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies;

import com.hellblazer.delos.bloomFilters.BloomFilter;
import com.hellblazer.delos.context.ViewChange;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.fireflies.proto.SignedViewChange;
import com.hellblazer.delos.fireflies.proto.ViewChangeGossip;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;

import java.util.List;
import java.util.function.Consumer;

/**
 * Manages view change lifecycle - observations, voting, consensus. Implements the Rapid-style view consensus protocol.
 * <p>
 * The view change protocol works as follows:
 * <ol>
 *   <li>Observers (BFT subset) propose view changes when membership changes</li>
 *   <li>Observations (votes) are gossiped to all members</li>
 *   <li>When majority observations received, finalization is scheduled</li>
 *   <li>Finalization tallies ballots and requires 3/4 supermajority</li>
 *   <li>On success, new view is installed and listeners notified</li>
 * </ol>
 * <p>
 * <b>Thread-Safety:</b> All methods are thread-safe.
 * <p>
 * <b>Locking:</b> Uses {@link ViewContext#viewChange(Runnable)} for installation (CRITICAL for BFT safety).
 * Observations stored in ConcurrentSkipListMap. Listener notifications ordered by viewSerialization semaphore.
 *
 * @author hal.hildebrand
 * @see ViewContext
 */
public interface ViewChangeCoordinator {

    // === Observation Management ===

    /**
     * Add a view change observation from an observer.
     * <p>
     * Validates the observation:
     * <ul>
     *   <li>Observer is in the current observer set</li>
     *   <li>View matches current view</li>
     *   <li>Attempt number is higher than previous from this observer</li>
     *   <li>Signature is valid</li>
     * </ul>
     *
     * @param observation the signed observation
     * @return true if observation was accepted
     */
    boolean addObservation(SignedViewChange observation);

    /**
     * Initiate a view change with local observation.
     * <p>
     * Called by ViewManagement when local node (as observer) decides to propose a view change.
     *
     * @param viewChange the signed view change proposal
     */
    void initiateViewChange(SignedViewChange viewChange);

    /**
     * Check if majority observations have been received.
     * <p>
     * In bootstrap mode with single member, returns true. Otherwise requires context.majority() observations.
     *
     * @param bootstrap true if in bootstrap mode (single member)
     * @return true if majority observations received
     */
    boolean hasMajorityObservations(boolean bootstrap);

    /**
     * Clear all observations.
     * <p>
     * Called after view change completes or times out to reset for next round.
     */
    void clearObservations();

    // === Consensus ===

    /**
     * Finalize the view change with consensus.
     * <p>
     * <b>CRITICAL:</b> Runs under write lock for BFT safety.
     * <p>
     * Algorithm:
     * <ol>
     *   <li>Collect all observations</li>
     *   <li>Tally ballots (joining + leaving proposals)</li>
     *   <li>Require 3/4 supermajority for consensus</li>
     *   <li>On success: clear vote, install winning ballot, schedule next round</li>
     *   <li>On failure: clear observations, schedule retry</li>
     * </ol>
     * <p>
     * Note: Vote is only cleared on successful consensus (fix for ring election consensus bug).
     */
    void finalizeViewChange();

    // === Scheduling ===

    /**
     * Schedule a view change check.
     * <p>
     * ViewManagement.maybeViewChange() will be called after the specified rounds.
     *
     * @param rounds number of gossip rounds to wait
     */
    void scheduleViewChange(int rounds);

    /**
     * Schedule view change finalization.
     * <p>
     * finalizeViewChange() will be called after the specified rounds.
     *
     * @param rounds number of gossip rounds to wait
     */
    void scheduleFinalizeViewChange(int rounds);

    /**
     * Schedule observation cleanup.
     * <p>
     * Clears observations after 1 round (used after successful view change).
     */
    void scheduleClearObservations();

    // === Listener Management ===

    /**
     * Register a view change listener.
     * <p>
     * Listeners are notified (in registration order via semaphore) when view changes complete.
     *
     * @param key      unique key for the listener
     * @param listener the callback to invoke on view change
     */
    void registerListener(String key, Consumer<ViewChange> listener);

    /**
     * Deregister a view change listener.
     *
     * @param key the listener key to remove
     */
    void deregisterListener(String key);

    /**
     * Notify all listeners of a view change.
     * <p>
     * Notifications are:
     * <ul>
     *   <li>Executed in virtual threads</li>
     *   <li>Ordered by viewSerialization semaphore</li>
     *   <li>Protected by lifecycle check (not notified if view stopped)</li>
     * </ul>
     *
     * @param joining members joining the view
     * @param leaving members leaving the view
     */
    void notifyListeners(List<SelfAddressingIdentifier> joining, List<Digest> leaving);

    // === Gossip Support ===

    /**
     * Process observations for gossip reconciliation.
     * <p>
     * Compares the inbound bloom filter against known observations and returns observations that the sender is
     * missing.
     *
     * @param bff inbound bloom filter of sender's known observations
     * @param fpr false positive rate for response bloom filter
     * @return gossip structure with observations to send and response bloom filter
     */
    ViewChangeGossip processObservations(BloomFilter<Digest> bff, double fpr);

    /**
     * Build bloom filter of known observations for gossip initiation.
     *
     * @param seed random seed for bloom filter
     * @param p    false positive rate
     * @return bloom filter containing hashes of all known observations
     */
    BloomFilter<Digest> getObservationsBff(long seed, double p);
}
