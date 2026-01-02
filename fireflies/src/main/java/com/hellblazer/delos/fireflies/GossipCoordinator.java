/*
 * Copyright (c) 2024, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies;

import com.hellblazer.delos.fireflies.View.Participant;
import com.hellblazer.delos.fireflies.comm.gossip.Fireflies;
import com.hellblazer.delos.fireflies.proto.Digests;
import com.hellblazer.delos.fireflies.proto.Gossip;
import com.hellblazer.delos.fireflies.proto.Update;

import java.time.Duration;

/**
 * Orchestrates gossip rounds and ring-based communication. Manages round timing and bloom filter reconciliation.
 * <p>
 * This component is responsible for:
 * <ul>
 *   <li>Scheduling and executing gossip rounds</li>
 *   <li>Managing ring-based partner selection</li>
 *   <li>Bloom filter construction for set reconciliation</li>
 *   <li>Processing gossip responses and updates</li>
 *   <li>Handling redirects when gossip partner changes</li>
 *   <li>Managing named timers for scheduled operations</li>
 * </ul>
 * <p>
 * <b>Thread-Safety:</b> All methods are thread-safe.
 * <p>
 * <b>Locking:</b> Uses {@link ViewContext#enterOperation()}/{@link ViewContext#exitOperation()} for lifecycle safety
 * and {@link ViewContext#stable(Runnable)} for state reads.
 *
 * @author hal.hildebrand
 * @see ViewContext
 */
public interface GossipCoordinator {

    // === Round Management ===

    /**
     * Start the gossip scheduler with the given interval.
     * <p>
     * Begins the gossip loop that:
     * <ol>
     *   <li>Selects successors on each ring</li>
     *   <li>Executes gossip with each successor</li>
     *   <li>Processes responses and updates</li>
     *   <li>Schedules the next round</li>
     * </ol>
     *
     * @param gossipInterval interval between gossip rounds
     */
    void start(Duration gossipInterval);

    /**
     * Stop gossip scheduling and cancel pending rounds.
     * <p>
     * Cancels the scheduled gossip future and clears all named timers.
     */
    void stop();

    /**
     * Advance round timers by one tick.
     * <p>
     * Called after each gossip round to progress scheduled operations. Timers that reach zero execute their actions.
     */
    void tick();

    // === Gossip Execution ===

    /**
     * Execute gossip with a partner on a specific ring.
     * <p>
     * Sends a SayWhat message containing:
     * <ul>
     *   <li>Current view digest</li>
     *   <li>Local node's note</li>
     *   <li>Ring index</li>
     *   <li>Common digests (bloom filters for notes, accusations, observations, joins)</li>
     * </ul>
     *
     * @param link the communication link to the partner
     * @param ring the ring index for this gossip
     * @return the gossip response, or null on failure (triggers accusation)
     */
    Gossip executeGossip(Fireflies link, int ring);

    /**
     * Process a gossip response from a partner.
     * <p>
     * Handles three cases:
     * <ol>
     *   <li>Redirect: Partner is not the correct successor, follow redirect</li>
     *   <li>Joined: Process updates and send missing state back</li>
     *   <li>Joining: Process updates only (during join protocol)</li>
     * </ol>
     *
     * @param gossip the response from the partner
     * @param member the partner participant
     * @param link   the communication link
     * @param ring   the ring index
     */
    void processGossipResponse(Gossip gossip, Participant member, Fireflies link, int ring);

    /**
     * Generate update response for gossip.
     * <p>
     * Called to prepare the third message in the anti-entropy protocol. Compares inbound bloom filters and generates
     * updates the partner is missing.
     *
     * @param gossip the inbound gossip containing bloom filters
     * @return updates to send to the partner
     */
    Update generateUpdate(Gossip gossip);

    /**
     * Handle redirect during gossip.
     * <p>
     * When the partner returns a redirect (they are not our successor), process the redirect member's note and any
     * updates included in the response.
     *
     * @param member the redirecting member
     * @param gossip the gossip containing the redirect
     * @param ring   the ring index
     * @return true if the redirect was handled successfully
     */
    boolean handleRedirect(Participant member, Gossip gossip, int ring);

    // === Timer Management ===

    /**
     * Schedule a named timer.
     * <p>
     * Timers are tracked by name and execute after the specified number of gossip rounds. If a timer with the same
     * name exists, it is replaced.
     *
     * @param name   unique timer name
     * @param action action to execute when timer fires
     * @param rounds number of gossip rounds to wait
     */
    void scheduleTimer(String name, Runnable action, int rounds);

    /**
     * Remove a named timer without executing.
     *
     * @param name the timer name
     */
    void removeTimer(String name);

    // === Bloom Filter Support ===

    /**
     * Build common digests for gossip initiation.
     * <p>
     * Creates a Digests structure containing bloom filters for:
     * <ul>
     *   <li>Notes (member state)</li>
     *   <li>Accusations</li>
     *   <li>Observations (view change votes)</li>
     *   <li>Joins (pending join requests)</li>
     * </ul>
     *
     * @return the digests structure for gossip initiation
     */
    Digests buildCommonDigests();
}
