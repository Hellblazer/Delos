/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.ViewCoordinator;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.membership.Member;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages CHOAM view state (next view, pending views, coordinator).
 * <p>
 * This holder encapsulates view state that was previously embedded in CHOAM.java.
 * It provides lock-protected state management for view transitions and exposes
 * the viewStateLock for external caller coordination.
 * </p>
 * <p>
 * <b>Invariants:</b>
 * <ul>
 *   <li>Pending views bounded: pendingViews.size() < 100</li>
 *   <li>View transition atomicity: snapshot() sees old or new state, never partial</li>
 * </ul>
 * </p>
 * <p>
 * <b>Thread Safety:</b> View references use AtomicReference. Multi-step operations
 * require callers to hold viewStateLock appropriately.
 * </p>
 * <p>
 * <b>Lock Ownership:</b> This holder owns the viewStateLock. Callers MUST hold the lock
 * when performing operations that span multiple state accesses.
 * </p>
 * <p>
 * <b>CRITICAL:</b> This lock MUST remain disjoint from headLock.
 * Never hold both locks simultaneously to prevent deadlock.
 * </p>
 * <p>
 * Extracted as part of CHOAMStateManager extraction plan (Phase 5, Bead: Delos-zfb1).
 * See choam/BASELINE.md and choam/LOCK_ORDERING.md for context.
 * </p>
 *
 * @author hal.hildebrand
 */
public class ViewStateHolder {

    /**
     * Next view ID: diadem for pending view change.
     */
    private final AtomicReference<Digest> nextViewId = new AtomicReference<>();

    /**
     * Next view member: pending signed view membership.
     * Type is generic to support NextView or other view member representations.
     */
    private final AtomicReference<Object> next = new AtomicReference<>();

    /**
     * Pending views: views waiting to be installed, bounded for DoS protection.
     */
    private final AtomicReference<ImmutablePendingViews> pendingViews;

    /**
     * View coordinator: manages two-phase reconfigure pattern.
     */
    private final ViewCoordinator coordinator;

    /**
     * View state lock: protects multi-step view transition operations.
     * Exposed publicly so callers can coordinate complex operations.
     * <p>
     * <b>CRITICAL:</b> This lock MUST remain disjoint from headLock.
     * Never hold both locks simultaneously to prevent deadlock.
     * </p>
     */
    public final ReentrantLock viewStateLock = new ReentrantLock();

    /**
     * Create a new ViewStateHolder with coordinator.
     *
     * @param coordinator ViewCoordinator for two-phase reconfigure pattern
     */
    public ViewStateHolder(ViewCoordinator coordinator) {
        this(coordinator, ImmutablePendingViews.EMPTY);
    }

    /**
     * Create a new ViewStateHolder with coordinator and initial pending views.
     *
     * @param coordinator ViewCoordinator for two-phase reconfigure pattern
     * @param initialPendingViews Initial pending views (default: EMPTY)
     */
    public ViewStateHolder(ViewCoordinator coordinator, ImmutablePendingViews initialPendingViews) {
        this.coordinator = coordinator;
        this.pendingViews = new AtomicReference<>(initialPendingViews);
    }

    /**
     * Get the next view ID.
     *
     * @return the next view ID, or null if not set
     */
    public Digest getNextViewId() {
        return nextViewId.get();
    }

    /**
     * Set the next view ID.
     * <p>
     * <b>Caller responsibility:</b> Should hold viewStateLock during
     * multi-step operations involving view transitions.
     * </p>
     *
     * @param viewId the new view ID (may be null)
     */
    public void setNextViewId(Digest viewId) {
        nextViewId.set(viewId);
    }

    /**
     * Get the next view member.
     *
     * @return the next view member, or null if not set
     */
    public Object getNext() {
        return next.get();
    }

    /**
     * Set the next view member.
     * <p>
     * <b>Caller responsibility:</b> Should hold viewStateLock during
     * multi-step operations involving view transitions.
     * </p>
     *
     * @param nextMember the new view member (may be null)
     */
    public void setNext(Object nextMember) {
        next.set(nextMember);
    }

    /**
     * Get the current pending views.
     *
     * @return immutable pending views
     */
    public ImmutablePendingViews getPendingViews() {
        return pendingViews.get();
    }

    /**
     * Set the pending views.
     * <p>
     * Uses lock-free atomic swap. Prefer addPendingView() for adding views.
     * </p>
     *
     * @param views new pending views
     */
    public void setPendingViews(ImmutablePendingViews views) {
        pendingViews.set(views);
    }

    /**
     * Add a pending view (lock-free).
     * <p>
     * Uses atomic updateAndGet for thread-safe addition without holding locks.
     * </p>
     *
     * @param diadem view diadem (view ID)
     * @param context context with members
     */
    public void addPendingView(Digest diadem, Context<Member> context) {
        pendingViews.updateAndGet(pv -> pv.add(diadem, context));
    }

    /**
     * Get the view coordinator.
     *
     * @return the ViewCoordinator
     */
    public ViewCoordinator getCoordinator() {
        return coordinator;
    }

    /**
     * Get direct access to the nextViewId AtomicReference for legacy compatibility.
     * <p>
     * <b>Use with caution:</b> Direct manipulation bypasses holder semantics.
     * Prefer getNextViewId()/setNextViewId() methods for normal operations.
     * </p>
     *
     * @return the underlying AtomicReference for next view ID
     */
    public AtomicReference<Digest> getNextViewIdRef() {
        return nextViewId;
    }

    /**
     * Get direct access to the next AtomicReference for legacy compatibility.
     * <p>
     * <b>Use with caution:</b> Direct manipulation bypasses holder semantics.
     * Prefer getNext()/setNext() methods for normal operations.
     * </p>
     *
     * @return the underlying AtomicReference for next view member
     */
    public AtomicReference<Object> getNextRef() {
        return next;
    }

    /**
     * Get direct access to the pendingViews AtomicReference for legacy compatibility.
     * <p>
     * <b>Use with caution:</b> Direct manipulation bypasses holder semantics.
     * Prefer getPendingViews()/setPendingViews() methods for normal operations.
     * </p>
     *
     * @return the underlying AtomicReference for pending views
     */
    public AtomicReference<ImmutablePendingViews> getPendingViewsRef() {
        return pendingViews;
    }

    /**
     * Get a human-readable representation of the view state.
     *
     * @return string representation showing next view ID and pending count
     */
    @Override
    public String toString() {
        var nvid = nextViewId.get();
        var n = next.get();
        return String.format("ViewStateHolder{nextViewId=%s, next=%s, pendingViews=%d}",
                             nvid == null ? "null" : nvid.toString().substring(0, 8),
                             n == null ? "null" : "coords",
                             pendingViews.get().size());
    }
}
