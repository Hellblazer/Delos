/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.membership.Member;

/**
 * ViewState interface - Manages view state without holding locks during callbacks.
 *
 * DESIGN PRINCIPLE: Lock-free snapshots, atomic state transitions.
 *
 * This interface separates view state management from view coordination. The contract ensures:
 *
 * 1. snapshot() is lock-free (can be called without holding viewStateLock)
 * 2. prepareReconfigure() computes new state atomically (deterministic)
 * 3. completeReconfigure() applies state transition atomically (deterministic)
 * 4. addPendingView() is lock-free (no internal synchronization)
 * 5. Concurrent reads during reconfigure see consistent state
 *
 * USAGE PATTERN (Two-Phase Reconfigure):
 * <pre>
 * // Phase 1 (lock-free): Capture snapshot
 * var snapshot = viewState.snapshot();
 *
 * // Phase 2 (locked): Compute new state
 * viewStateLock.lock();
 * try {
 *     var prepared = viewState.prepareReconfigure(snapshot, newDiadem, newContext);
 * } finally {
 *     viewStateLock.unlock();
 * }
 *
 * // Phase 3 (lock-free): Apply transition and callbacks
 * viewState.completeReconfigure(prepared);
 * callbacks.forEach(cb -> cb.run());  // No lock held during callbacks
 * </pre>
 *
 * @author hal.hildebrand
 */
public interface ViewState {

    /**
     * Capture current view state atomically without holding locks.
     *
     * CRITICAL: This method must NOT acquire any locks. It must be safe to call
     * concurrently with other operations. Uses immutable data structures and atomic
     * references to provide consistent snapshots.
     *
     * @return Immutable snapshot of current view state
     */
    Snapshot snapshot();

    /**
     * Compute new view state from a snapshot (deterministic, no side effects).
     *
     * This method is called while viewStateLock is held. It must:
     * - Be deterministic (same inputs → same outputs)
     * - Have no side effects
     * - Return prepared state for later application
     *
     * @param snapshot Current state snapshot (from snapshot())
     * @param newDiadem New view diadem (view ID)
     * @param newContext New context with new members
     * @return Prepared state to be applied in completeReconfigure()
     */
    Prepared prepareReconfigure(Snapshot snapshot, Digest newDiadem, Context<Member> newContext);

    /**
     * Apply prepared state atomically without holding locks.
     *
     * CRITICAL: This method must NOT acquire viewStateLock. The state transition
     * must be atomic so that concurrent snapshot() calls see either old or new state,
     * but never partial/inconsistent state.
     *
     * @param prepared State from prepareReconfigure()
     */
    void completeReconfigure(Prepared prepared);

    /**
     * Add a pending view without internal synchronization (lock-free).
     *
     * Uses copy-on-write or atomic swap semantics to ensure thread-safety
     * without holding internal locks.
     *
     * @param diadem View diadem (view ID)
     * @param context Context with members
     */
    void addPendingView(Digest diadem, Context<Member> context);

    /**
     * Get current view ID (query method).
     *
     * @return Current view ID
     */
    Digest getViewId();

    /**
     * Lifecycle: Initialize view state.
     */
    void start();

    /**
     * Lifecycle: Cleanup and shutdown.
     */
    void stop();

    /**
     * Immutable snapshot of view state captured at a point in time.
     *
     * This is used as input to prepareReconfigure() to ensure deterministic
     * view transition computation.
     */
    interface Snapshot {
        Digest getViewId();
        int getMemberCount();
    }

    /**
     * Prepared view state ready to be applied.
     *
     * This is returned from prepareReconfigure() and passed to completeReconfigure()
     * to apply the state transition.
     */
    interface Prepared {
        Digest getNewDiadem();
        Context<Member> getNewContext();
    }
}
