/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.ViewCoordinator;
import com.hellblazer.delos.choam.ViewState;
import com.hellblazer.delos.choam.proto.Reconfigure;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.membership.Member;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ViewCoordinatorImpl - Implements two-phase view reconfiguration pattern.
 *
 * DESIGN: Acts as callback container and executor for two-phase reconfigure pattern.
 * Phase 2 (locked) collects callbacks deterministically.
 * Phase 3 (unlocked) executes collected callbacks safely without locks.
 *
 * CRITICAL: This class is flexible to support CHOAM's specific callback patterns.
 * Callbacks are caller-provided (not auto-generated) to handle complex state transitions.
 *
 * KEY PATTERN:
 * - Phase 2 (locked): Collect callbacks in list during deterministic computation
 * - Phase 3 (unlocked): Execute callbacks in deterministic order
 *
 * @author hal.hildebrand
 */
public class ViewCoordinatorImpl implements ViewCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ViewCoordinatorImpl.class);

    private final ViewState viewState;
    private final AtomicReference<ReconfiguredState> state;

    /**
     * Initialize ViewCoordinator with initial state.
     *
     * @param viewState ViewState instance to delegate to
     * @param initialDiadem Initial view diadem
     * @param initialContext Initial context with members
     */
    public ViewCoordinatorImpl(ViewState viewState, Digest initialDiadem, Context<Member> initialContext) {
        this.viewState = viewState;
        this.state = new AtomicReference<>(new ReconfiguredState(initialDiadem, initialContext, new ArrayList<>()));
    }

    @Override
    public ReconfigurationPrepared prepareReconfigure(ViewState.Snapshot snapshot, Digest hash, Reconfigure reconfigure) {
        // This method is called while viewStateLock is held by caller.
        // It must be deterministic with no side effects.
        // Caller is responsible for collecting callbacks in the prepared state.

        log.debug("Preparing reconfiguration to view: {} hash: {}", new Digest(reconfigure.getId()), hash);

        // For CHOAM integration: Caller will provide callbacks
        // This is a placeholder that can be overridden for different use cases
        List<Runnable> callbacks = new ArrayList<>();

        // Create prepared state with empty callbacks - caller will add them
        ReconfiguredState prepared = new ReconfiguredState(hash, null, callbacks);

        return new ReconfigurationPreparedImpl(prepared, snapshot);
    }

    @Override
    public void completeReconfigure(ReconfigurationPrepared prepared) {
        // This method is called WITHOUT viewStateLock held.
        // Execute callbacks collected during Phase 2.

        if (!(prepared instanceof ReconfigurationPreparedImpl impl)) {
            return;
        }

        ReconfiguredState preparedState = impl.state;

        // Apply state transition atomically
        state.set(preparedState);

        // Now execute callbacks (after state transition, without locks)
        // Callbacks can safely acquire other locks and perform I/O
        executeCallbacks(preparedState.callbacks);

        log.debug("Reconfiguration completed, state applied, callbacks executed");
    }

    /**
     * Execute callbacks in order (called from completeReconfigure).
     *
     * Each callback is executed independently; exceptions don't prevent other
     * callbacks from running.
     *
     * @param callbacks List of callbacks to execute in order
     */
    public void executeCallbacks(List<Runnable> callbacks) {
        if (callbacks == null || callbacks.isEmpty()) {
            return;
        }

        for (int i = 0; i < callbacks.size(); i++) {
            try {
                callbacks.get(i).run();
            } catch (Exception e) {
                log.error("Callback {} execution failed during reconfigure", i, e);
                // Continue with next callback even if this one fails
            }
        }
    }

    /**
     * Get current state (for testing/validation).
     *
     * @return Current reconfigured state
     */
    public ReconfiguredState getCurrentState() {
        return state.get();
    }

    /**
     * Internal state holder for reconfigured view.
     *
     * This is public (package-private) to allow callers to build
     * ReconfigurationPrepared with their specific callbacks.
     */
    public static class ReconfiguredState {
        public final Digest viewId;
        public final Context<Member> context;
        public final List<Runnable> callbacks;

        public ReconfiguredState(Digest viewId, Context<Member> context, List<Runnable> callbacks) {
            this.viewId = viewId;
            this.context = context;
            this.callbacks = callbacks != null ? callbacks : new ArrayList<>();
        }
    }

    /**
     * Prepared reconfiguration with callbacks.
     */
    private static class ReconfigurationPreparedImpl implements ReconfigurationPrepared {
        final ReconfiguredState state;
        final ViewState.Snapshot snapshot;

        ReconfigurationPreparedImpl(ReconfiguredState state, ViewState.Snapshot snapshot) {
            this.state = state;
            this.snapshot = snapshot;
        }

        @Override
        public void executeCallbacks() {
            if (state.callbacks != null) {
                for (int i = 0; i < state.callbacks.size(); i++) {
                    try {
                        state.callbacks.get(i).run();
                    } catch (Exception e) {
                        LoggerFactory.getLogger(ViewCoordinatorImpl.class)
                                     .error("Callback {} execution failed", i, e);
                    }
                }
            }
        }

        @Override
        public ViewState.Snapshot getPreparedSnapshot() {
            return snapshot;
        }
    }
}
