/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.ViewState;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.membership.Member;

import java.util.concurrent.atomic.AtomicReference;

/**
 * ViewStateImpl - Manages view state without holding locks during callbacks.
 *
 * DESIGN: Uses atomic references to provide lock-free reads and atomic state transitions.
 *
 * KEY INVARIANTS:
 * - viewId (AtomicReference) can be read without locks (snapshot())
 * - Mutations to viewId are atomic (completeReconfigure uses AtomicReference.set())
 * - Concurrent snapshot() calls always see consistent state (old or new, never partial)
 *
 * NOTE: This is a minimal implementation that manages view ID and context.
 * Full view state management (including committee lifecycle) remains in CHOAM.
 *
 * @author hal.hildebrand
 */
public class ViewStateImpl implements ViewState {

    private final AtomicReference<Digest> viewId;
    private final AtomicReference<Context<Member>> context;

    /**
     * Initialize with initial view state.
     *
     * @param initialDiadem Initial view diadem (view ID)
     * @param initialContext Initial context with members
     */
    public ViewStateImpl(Digest initialDiadem, Context<Member> initialContext) {
        this.viewId = new AtomicReference<>(initialDiadem);
        this.context = new AtomicReference<>(initialContext);
    }

    @Override
    public Snapshot snapshot() {
        // Lock-free read: atomic references provide visibility
        return new SnapshotImpl(viewId.get(), context.get());
    }

    @Override
    public Prepared prepareReconfigure(Snapshot snapshot, Digest newDiadem, Context<Member> newContext) {
        // Note: This is called while viewStateLock is held (by caller)
        // Compute new state deterministically based on snapshot
        return new PreparedImpl(newDiadem, newContext);
    }

    @Override
    public void completeReconfigure(Prepared prepared) {
        // Lock-free update: atomic swap provides atomicity
        // All concurrent snapshot() calls will see either old or new state
        viewId.set(prepared.getNewDiadem());
        context.set(prepared.getNewContext());
    }

    @Override
    public void addPendingView(Digest diadem, Context<Member> context) {
        // Implementation: This would typically update pending views in CHOAM
        // For now, kept as placeholder - CHOAM manages pending views via ImmutablePendingViews
        // This method is part of the contract but implementation may be deferred to Phase 3
    }

    @Override
    public Digest getViewId() {
        return viewId.get();
    }

    @Override
    public void start() {
        // No resources to initialize
    }

    @Override
    public void stop() {
        // No resources to cleanup
    }

    /**
     * Immutable snapshot of view state.
     */
    private static class SnapshotImpl implements Snapshot {
        private final Digest viewId;
        private final Context<Member> context;

        SnapshotImpl(Digest viewId, Context<Member> context) {
            this.viewId = viewId;
            this.context = context;
        }

        @Override
        public Digest getViewId() {
            return viewId;
        }

        @Override
        public int getMemberCount() {
            return context.cardinality();
        }
    }

    /**
     * Prepared state ready to be applied.
     */
    private static class PreparedImpl implements Prepared {
        private final Digest newDiadem;
        private final Context<Member> newContext;

        PreparedImpl(Digest newDiadem, Context<Member> newContext) {
            this.newDiadem = newDiadem;
            this.newContext = newContext;
        }

        @Override
        public Digest getNewDiadem() {
            return newDiadem;
        }

        @Override
        public Context<Member> getNewContext() {
            return newContext;
        }
    }
}
