/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.consensus;

import com.hellblazer.delos.ethereal.Ethereal;
import com.hellblazer.delos.ethereal.memberships.ChRbcGossip;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Adapter wrapping Ethereal consensus engine and ChRbcGossip coordinator.
 * Encapsulates the lifecycle of both components and provides a unified interface
 * for consensus operations.
 * <p>
 * Thread Safety: This class is thread-safe. Lifecycle operations (start/stop/completeIt)
 * use atomic state transitions for idempotency. The underlying Ethereal and ChRbcGossip
 * components provide their own thread safety.
 * <p>
 * Lifecycle: start() must be called before the oracle is operational. stop() gracefully
 * shuts down both gossip and consensus, waiting for in-flight operations to complete.
 * State transitions: INITIAL → STARTING → STARTED → STOPPING → STOPPED
 *
 * @author hal.hildebrand
 */
public class EtherealConsensusOracle implements ConsensusOracle {
    /**
     * Lifecycle states for the consensus oracle.
     */
    private enum State {
        INITIAL,   // Not yet started
        STARTING,  // Start in progress
        STARTED,   // Fully started and operational
        STOPPING,  // Stop in progress
        STOPPED    // Fully stopped
    }

    private final Ethereal                ethereal;
    private final ChRbcGossip             gossip;
    private final AtomicReference<State> state = new AtomicReference<>(State.INITIAL);

    /**
     * Constructs an EtherealConsensusOracle wrapping the given components.
     *
     * @param ethereal the consensus engine (must not be null)
     * @param gossip   the gossip coordinator (must not be null)
     * @throws NullPointerException if either parameter is null
     */
    public EtherealConsensusOracle(Ethereal ethereal, ChRbcGossip gossip) {
        this.ethereal = Objects.requireNonNull(ethereal, "ethereal cannot be null");
        this.gossip = Objects.requireNonNull(gossip, "gossip cannot be null");
    }

    @Override
    public void start(Duration gossipDuration) {
        if (!state.compareAndSet(State.INITIAL, State.STARTING)) {
            return;  // Not in INITIAL state (already started or starting)
        }

        try {
            ethereal.start();
            gossip.start(gossipDuration);
            state.set(State.STARTED);
        } catch (Exception e) {
            // Reset to INITIAL on failure and attempt cleanup
            state.set(State.INITIAL);
            try {
                ethereal.stop();
            } catch (Exception cleanupEx) {
                e.addSuppressed(cleanupEx);
            }
            throw e;
        }
    }

    @Override
    public void stop() {
        if (!state.compareAndSet(State.STARTED, State.STOPPING)) {
            return;  // Not in STARTED state (already stopped, stopping, or never started)
        }

        try {
            // Stop in reverse order of start: gossip first to prevent new messages
            // being processed during ethereal shutdown
            gossip.stop();
            ethereal.stop();
            state.set(State.STOPPED);
        } catch (Exception e) {
            // Even if stop fails, mark as stopped (can't retry stop operation)
            state.set(State.STOPPED);
            throw e;
        }
    }

    @Override
    public void completeIt() {
        ethereal.completeIt();
    }

    @Override
    public Object processor() {
        return ethereal.processor();
    }
}
