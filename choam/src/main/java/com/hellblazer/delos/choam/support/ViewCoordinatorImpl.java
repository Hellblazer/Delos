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
 * Separates deterministic state computation (Phase 2, locked) from callback
 * execution (Phase 3, unlocked) to eliminate reentrancy violations.
 *
 * KEY OPERATIONS:
 * - Phase 2: Compute new validators, build committees, collect callbacks
 * - Phase 3: Apply state atomically, execute callbacks (no locks)
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
        this.state = new AtomicReference<>(new ReconfiguredState(initialDiadem, initialContext, null));
    }

    @Override
    public ReconfigurationPrepared prepareReconfigure(ViewState.Snapshot snapshot, Digest hash, Reconfigure reconfigure) {
        // This method is called while viewStateLock is held by caller.
        // It must be deterministic with no side effects.

        log.debug("Preparing reconfiguration to view: {} hash: {}", new Digest(reconfigure.getId()), hash);

        // Extract current state from snapshot
        Digest currentViewId = snapshot.getViewId();
        int currentMemberCount = snapshot.getMemberCount();

        // Compute new state deterministically
        // This is placeholder for actual validator computation logic
        Context<Member> newContext = null;  // Would be computed from reconfigure message

        // Store callbacks to execute in Phase 3 (outside lock)
        List<Runnable> callbacks = new ArrayList<>();

        // Callback 1: Log reconfiguration
        callbacks.add(() -> {
            log.info("Reconfigured to view: {} members: {} from: {}", hash, currentMemberCount, currentViewId);
        });

        // Additional callbacks would be added here based on reconfigure logic
        // (committee transitions, view changes, etc.)

        // Create prepared state
        ReconfiguredState prepared = new ReconfiguredState(hash, newContext, callbacks);

        return new ReconfigurationPreparedImpl(prepared, snapshot);
    }

    @Override
    public void completeReconfigure(ReconfigurationPrepared prepared) {
        // This method is called WITHOUT viewStateLock held.
        // Apply state atomically, then execute callbacks.

        if (!(prepared instanceof ReconfigurationPreparedImpl impl)) {
            return;
        }

        ReconfiguredState preparedState = impl.state;

        // Apply state transition atomically
        state.set(preparedState);

        // Now execute callbacks (after state transition, without locks)
        // Callbacks can safely acquire other locks and perform I/O
        if (preparedState.callbacks != null) {
            for (var callback : preparedState.callbacks) {
                try {
                    callback.run();
                } catch (Exception e) {
                    log.error("Callback execution failed during reconfigure", e);
                }
            }
        }

        log.debug("Reconfiguration completed, state applied, callbacks executed");
    }

    /**
     * Internal state holder for reconfigured view.
     */
    private static class ReconfiguredState {
        final Digest viewId;
        final Context<Member> context;
        final List<Runnable> callbacks;

        ReconfiguredState(Digest viewId, Context<Member> context, List<Runnable> callbacks) {
            this.viewId = viewId;
            this.context = context;
            this.callbacks = callbacks;
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
                for (var callback : state.callbacks) {
                    try {
                        callback.run();
                    } catch (Exception e) {
                        log.error("Callback execution failed", e);
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
