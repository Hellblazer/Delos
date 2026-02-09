/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.choam.support;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages CHOAM lifecycle control flags (started, ongoingJoin).
 * <p>
 * This holder encapsulates lifecycle state that was previously embedded in CHOAM.java.
 * It provides lock-free state management using AtomicBoolean CAS operations.
 * </p>
 * <p>
 * <b>Invariants:</b>
 * <ul>
 *   <li>ongoingJoin ⇒ started (join requires system to be started)</li>
 *   <li>Boolean values never corrupted (always true or false)</li>
 * </ul>
 * </p>
 * <p>
 * <b>Thread Safety:</b> All operations are lock-free and thread-safe via AtomicBoolean.
 * </p>
 * <p>
 * Extracted as part of CHOAMStateManager extraction plan (Phase 1, Bead: Delos-zukm).
 * See choam/BASELINE.md and choam/LOCK_ORDERING.md for context.
 * </p>
 *
 * @author hal.hildebrand
 */
public class ControlStateHolder {

    /**
     * System lifecycle flag: true if CHOAM is started, false otherwise.
     * <p>
     * Transitions: false (initial) → true (started) ↔ false (stopped)
     * </p>
     */
    private final AtomicBoolean started = new AtomicBoolean(false);

    /**
     * Join operation flag: true if join is ongoing, false otherwise.
     * <p>
     * Transitions: false (initial) → true (join started) ↔ false (join ended)
     * </p>
     * <p>
     * <b>Invariant:</b> ongoingJoin = true ⇒ started = true
     * (join operations require the system to be started)
     * </p>
     */
    private final AtomicBoolean ongoingJoin = new AtomicBoolean(false);

    /**
     * Check if the system is started.
     *
     * @return true if started, false otherwise
     */
    public boolean isStarted() {
        return started.get();
    }

    /**
     * Attempt to start the system using CAS (Compare-And-Set).
     * <p>
     * This operation is thread-safe and ensures only one thread successfully starts the system.
     * </p>
     *
     * @return true if this call transitioned from not-started to started, false if already started
     */
    public boolean start() {
        return started.compareAndSet(false, true);
    }

    /**
     * Stop the system.
     * <p>
     * This operation is idempotent and safe to call multiple times.
     * </p>
     */
    public void stop() {
        started.set(false);
    }

    /**
     * Attempt to stop the system using CAS (Compare-And-Set).
     * <p>
     * This operation is thread-safe and ensures only one thread successfully stops the system.
     * Useful for ensuring cleanup code runs exactly once.
     * </p>
     *
     * @return true if this call transitioned from started to stopped, false if already stopped
     */
    public boolean stopWithCAS() {
        return started.compareAndSet(true, false);
    }

    /**
     * Check if a join operation is ongoing.
     *
     * @return true if join is ongoing, false otherwise
     */
    public boolean isJoinOngoing() {
        return ongoingJoin.get();
    }

    /**
     * Attempt to begin a join operation using CAS (Compare-And-Set).
     * <p>
     * This operation is thread-safe and ensures only one join can be ongoing at a time.
     * </p>
     * <p>
     * <b>Note:</b> The caller should ensure the system is started before beginning a join
     * to maintain the invariant: ongoingJoin ⇒ started.
     * </p>
     *
     * @return true if this call transitioned to join-ongoing, false if join already ongoing
     */
    public boolean beginJoin() {
        return ongoingJoin.compareAndSet(false, true);
    }

    /**
     * End the ongoing join operation.
     * <p>
     * This operation is idempotent and safe to call multiple times.
     * </p>
     */
    public void endJoin() {
        ongoingJoin.set(false);
    }

    /**
     * Attempt to end join using CAS (Compare-And-Set).
     * <p>
     * This operation is thread-safe and checks if join was actually ongoing.
     * </p>
     *
     * @return true if this call transitioned from join-ongoing to not-ongoing, false otherwise
     */
    public boolean endJoinWithCAS() {
        return ongoingJoin.compareAndSet(true, false);
    }

    /**
     * Get direct access to the started AtomicBoolean (for legacy code compatibility).
     * <p>
     * <b>Note:</b> This is provided for backward compatibility with code that directly
     * manipulates the AtomicBoolean. Prefer using start(), stop(), and isStarted() methods.
     * </p>
     *
     * @return the internal started AtomicBoolean
     */
    public AtomicBoolean getStartedRef() {
        return started;
    }

    /**
     * Get direct access to the ongoingJoin AtomicBoolean (for legacy code compatibility).
     * <p>
     * <b>Note:</b> This is provided for backward compatibility with code that directly
     * manipulates the AtomicBoolean. Prefer using beginJoin(), endJoin(), and isJoinOngoing() methods.
     * </p>
     *
     * @return the internal ongoingJoin AtomicBoolean
     */
    public AtomicBoolean getOngoingJoinRef() {
        return ongoingJoin;
    }

    /**
     * Get a human-readable representation of the control state.
     *
     * @return string representation showing started and ongoingJoin flags
     */
    @Override
    public String toString() {
        return String.format("ControlStateHolder{started=%s, ongoingJoin=%s}", started.get(), ongoingJoin.get());
    }
}
