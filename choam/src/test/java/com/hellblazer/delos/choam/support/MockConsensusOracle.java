/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.consensus.ConsensusOracle;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mock implementation of ConsensusOracle for unit tests. Provides configurable consensus behavior
 * without requiring Ethereal or gossip infrastructure.
 * <p>
 * Thread Safety: All lifecycle operations are thread-safe using atomic state transitions.
 * Callbacks are tracked but not invoked (tests can configure behavior via setters).
 *
 * @author hal.hildebrand
 */
public class MockConsensusOracle implements ConsensusOracle {
    /**
     * Lifecycle states for the mock oracle.
     */
    public enum State {
        INITIAL,   // Not yet started
        STARTED,   // Currently running
        STOPPED,   // Stopped (terminal state)
        COMPLETED  // Completed (terminal state)
    }

    private final AtomicReference<State> state = new AtomicReference<>(State.INITIAL);
    private final AtomicBoolean          completed = new AtomicBoolean(false);
    private volatile Duration            lastGossipDuration;

    /**
     * Stub processor returned by processor(). Can be configured for tests that need
     * type-specific processor behavior.
     */
    private volatile Object stubProcessor = new Object();

    @Override
    public void start(Duration gossipDuration) {
        if (state.compareAndSet(State.INITIAL, State.STARTED)) {
            this.lastGossipDuration = gossipDuration;
        } else if (state.get() == State.STOPPED || state.get() == State.COMPLETED) {
            throw new IllegalStateException("Cannot restart a stopped oracle");
        }
        // Idempotent - if already STARTED, do nothing
    }

    @Override
    public void stop() {
        // Idempotent stop - only transition from STARTED to STOPPED
        state.compareAndSet(State.STARTED, State.STOPPED);
    }

    @Override
    public void completeIt() {
        completed.set(true);
        state.compareAndSet(State.STARTED, State.COMPLETED);
    }

    @Override
    public Object processor() {
        return stubProcessor;
    }

    /**
     * Get the current lifecycle state.
     *
     * @return the current state
     */
    public State getState() {
        return state.get();
    }

    /**
     * Check if the oracle is currently started.
     *
     * @return true if in STARTED state
     */
    public boolean isStarted() {
        return state.get() == State.STARTED;
    }

    /**
     * Check if the oracle has been stopped.
     *
     * @return true if in STOPPED or COMPLETED state
     */
    public boolean isStopped() {
        var currentState = state.get();
        return currentState == State.STOPPED || currentState == State.COMPLETED;
    }

    /**
     * Check if completeIt() was called.
     *
     * @return true if completed
     */
    public boolean isCompleted() {
        return completed.get();
    }

    /**
     * Get the last gossip duration passed to start().
     *
     * @return the last gossip duration, or null if never started
     */
    public Duration getLastGossipDuration() {
        return lastGossipDuration;
    }

    /**
     * Configure the stub processor returned by processor().
     *
     * @param processor the stub processor object
     */
    public void setStubProcessor(Object processor) {
        this.stubProcessor = processor;
    }

    /**
     * Reset the mock to INITIAL state (useful for test reuse).
     * This violates the "cannot restart" contract but is useful for test cleanup.
     */
    public void reset() {
        state.set(State.INITIAL);
        completed.set(false);
        lastGossipDuration = null;
    }
}
