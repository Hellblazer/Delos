/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.choam.proto.Reconfigure;
import com.hellblazer.delos.cryptography.Digest;

/**
 * ViewCoordinator - Manages view reconfiguration with two-phase pattern.
 *
 * CRITICAL DESIGN: Separates deterministic computation (locked) from callback
 * execution (unlocked) to eliminate callback reentrancy violations.
 *
 * TWO-PHASE RECONFIGURE PATTERN:
 *
 * Phase 1 (Lock-free): `snapshot = viewState.snapshot()`
 *   - Capture consistent view state without locks
 *   - Safe to call concurrently
 *
 * Phase 2 (Locked): `prepared = coordinator.prepareReconfigure(snapshot, reconfigure)`
 *   - Compute new state deterministically while holding viewStateLock
 *   - No side effects, no callbacks
 *   - Store callbacks for execution in Phase 3
 *
 * Phase 3 (Unlocked): `coordinator.completeReconfigure(prepared)`
 *   - Apply state transition atomically (no lock)
 *   - Execute stored callbacks (AFTER releasing lock)
 *   - Safe for callbacks to acquire other locks
 *
 * INVARIANTS:
 * 1. prepareReconfigure() is deterministic (same inputs → same outputs)
 * 2. completeReconfigure() is called WITHOUT viewStateLock
 * 3. Callbacks are executed WITHOUT viewStateLock
 * 4. State transition is atomic (concurrent readers see old or new, never partial)
 *
 * @author hal.hildebrand
 */
public interface ViewCoordinator {

    /**
     * Prepare for view reconfiguration (Phase 2: LOCKED).
     *
     * This method is called while viewStateLock is held. It computes the new view
     * state deterministically without executing callbacks or side effects.
     *
     * CALLER MUST HOLD viewStateLock for the entire duration of this call.
     *
     * @param snapshot Current view state snapshot (from viewState.snapshot())
     * @param hash View reconfiguration hash
     * @param reconfigure Reconfiguration message with new validators
     * @return Prepared state including callbacks to execute in Phase 3
     */
    ReconfigurationPrepared prepareReconfigure(ViewState.Snapshot snapshot, Digest hash, Reconfigure reconfigure);

    /**
     * Complete view reconfiguration (Phase 3: UNLOCKED).
     *
     * This method applies the state transition and executes stored callbacks.
     * CALLER MUST NOT HOLD viewStateLock.
     *
     * CRITICAL: Callbacks are executed AFTER state transition completes.
     * This ensures:
     * - No reentrancy violations
     * - Callbacks may safely acquire other locks
     * - Deterministic order of callback execution
     *
     * @param prepared State from prepareReconfigure()
     */
    void completeReconfigure(ReconfigurationPrepared prepared);

    /**
     * Prepared reconfiguration state with callbacks.
     *
     * Encapsulates both state transition data and callbacks to execute.
     * State transition must be atomic; callbacks execute after transition.
     */
    interface ReconfigurationPrepared {
        /**
         * Execute callbacks (to be called in Phase 3 without locks).
         *
         * These callbacks are deterministically computed in prepareReconfigure()
         * and executed in a deterministic order in completeReconfigure().
         */
        void executeCallbacks();

        /**
         * Get the prepared snapshot for validation/testing.
         */
        ViewState.Snapshot getPreparedSnapshot();
    }
}
