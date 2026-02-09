/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.Committee;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages CHOAM committee state machine reference.
 * <p>
 * This holder encapsulates the committee state reference that was previously embedded in CHOAM.java.
 * It provides lock-free state management for committee transitions following the state machine:
 * null → Formation → Administration ↔ Synchronizer
 * </p>
 * <p>
 * <b>Invariants:</b>
 * <ul>
 *   <li>Committee transitions follow state machine: null → Formation → Administration ↔ Synchronizer</li>
 *   <li>Committee never null after genesis block acceptance</li>
 * </ul>
 * </p>
 * <p>
 * <b>Thread Safety:</b> All operations are lock-free and thread-safe via AtomicReference.
 * </p>
 * <p>
 * Extracted as part of CHOAMStateManager extraction plan (Phase 3, Bead: Delos-k4ml).
 * See choam/BASELINE.md and choam/LOCK_ORDERING.md for context.
 * </p>
 *
 * @author hal.hildebrand
 */
public class CommitteeStateHolder {

    /**
     * Committee state machine reference: tracks current committee (Formation, Administration, Synchronizer).
     * Null when no committee is active (before genesis).
     */
    private final AtomicReference<Committee> current = new AtomicReference<>();

    /**
     * Get the current committee.
     *
     * @return the current committee, or null if none active
     */
    public Committee getCommittee() {
        return current.get();
    }

    /**
     * Set the committee (unconditional overwrite).
     * <p>
     * This always succeeds and overwrites any existing value.
     * </p>
     *
     * @param committee the committee to set (may be null)
     */
    public void setCommittee(Committee committee) {
        current.set(committee);
    }

    /**
     * Atomically set the committee using CAS (Compare-And-Set).
     * <p>
     * This ensures atomic state machine transitions. Only succeeds if current
     * value matches the expected value.
     * </p>
     *
     * @param expected the expected current committee
     * @param updated  the new committee to set
     * @return true if CAS succeeded, false if current value didn't match expected
     */
    public boolean compareAndSetCommittee(Committee expected, Committee updated) {
        return current.compareAndSet(expected, updated);
    }

    /**
     * Check if a committee is currently active.
     *
     * @return true if committee is not null
     */
    public boolean hasCommittee() {
        return current.get() != null;
    }

    /**
     * Get the committee type as a string for debugging/logging.
     * <p>
     * Returns the simple class name of the committee (e.g., "Formation", "Administration").
     * </p>
     *
     * @return committee type name, or "none" if null
     */
    public String getCommitteeType() {
        Committee c = current.get();
        return c == null ? "none" : c.getClass().getSimpleName();
    }

    /**
     * Get direct access to the committee AtomicReference (for legacy code compatibility).
     * <p>
     * <b>Note:</b> This is provided for backward compatibility with code that directly
     * manipulates the AtomicReference. Prefer using the accessor methods.
     * </p>
     *
     * @return the internal committee AtomicReference
     */
    public AtomicReference<Committee> getCommitteeRef() {
        return current;
    }

    /**
     * Get a human-readable representation of the committee state.
     *
     * @return string representation showing committee type
     */
    @Override
    public String toString() {
        return String.format("CommitteeStateHolder{committee=%s}", getCommitteeType());
    }
}
