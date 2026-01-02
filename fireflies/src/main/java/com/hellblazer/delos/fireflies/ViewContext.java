/*
 * Copyright (c) 2024, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies;

import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.fireflies.View.Participant;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;

import java.util.concurrent.Callable;

/**
 * Shared context provided by View to all extracted components. Centralizes locking and shared state access.
 * <p>
 * This interface is the primary coordination point between View and its extracted components (MembershipManager,
 * GossipCoordinator, AccusationTracker, ViewChangeCoordinator). It provides:
 * <ul>
 *   <li>Lifecycle guards for operation safety</li>
 *   <li>Lock access (read via stable(), write via viewChange())</li>
 *   <li>Shared state references (context, node, view)</li>
 *   <li>Validation services</li>
 * </ul>
 * <p>
 * <b>Thread-Safety:</b> All methods are thread-safe.
 * <p>
 * <b>Lock Ordering:</b> lifecycleLock -> viewChange (read/write). This ordering must be preserved to prevent deadlock.
 *
 * @author hal.hildebrand
 * @see View
 */
public interface ViewContext {

    // === Lifecycle Guards ===

    /**
     * Enter an operation with lifecycle check. Must be paired with {@link #exitOperation()} in a finally block.
     * <p>
     * Pattern:
     * <pre>{@code
     * if (!viewContext.enterOperation()) {
     *     return; // View is stopped
     * }
     * try {
     *     // ... operation logic
     * } finally {
     *     viewContext.exitOperation();
     * }
     * }</pre>
     *
     * @return true if operation can proceed, false if view is stopped
     */
    boolean enterOperation();

    /**
     * Exit an operation. Must be called after {@link #enterOperation()} returns true.
     */
    void exitOperation();

    /**
     * @return true if the view is started and operational
     */
    boolean isStarted();

    // === View State Access ===

    /**
     * Execute action under read lock (allows concurrent reads). Use for queries that need consistent view state.
     * <p>
     * The read lock allows multiple threads to read view state concurrently, but blocks if a write lock is held.
     *
     * @param action the action to execute under read lock
     */
    void stable(Runnable action);

    /**
     * Execute callable under read lock and return result.
     *
     * @param callable the callable to execute under read lock
     * @param <T>      the return type
     * @return the result of the callable
     */
    <T> T stable(Callable<T> callable);

    /**
     * Execute action under write lock (exclusive access). Use for view modifications (install, finalize).
     * <p>
     * <b>CRITICAL:</b> This is the BFT safety mechanism. All view modifications MUST hold the write lock. The write
     * lock is exclusive - no other thread can read or write while held.
     *
     * @param action the action to execute under write lock
     */
    void viewChange(Runnable action);

    // === Shared State ===

    /**
     * @return the current view digest
     */
    Digest currentView();

    /**
     * @return the dynamic context managing ring membership
     */
    DynamicContext<Participant> getContext();

    /**
     * @return the local node representation
     */
    NodeMember getNode();

    /**
     * @return the digest algorithm in use
     */
    DigestAlgorithm getDigestAlgorithm();

    /**
     * @return the configuration parameters
     */
    Parameters getParams();

    /**
     * @return metrics collector (may be null if metrics disabled)
     */
    FireflyMetrics getMetrics();

    // === Validation Services ===

    /**
     * Validate a KERI identifier using the configured EventValidation.
     *
     * @param identifier the identifier to validate
     * @return true if the identifier is valid
     */
    boolean validate(SelfAddressingIdentifier identifier);

    /**
     * Validate a note during bootstrap. This validates the self-addressing identity property and signature without
     * requiring full KERI event validation.
     * <p>
     * Checks:
     * <ol>
     *   <li>ID equals identifier digest (self-addressing property)</li>
     *   <li>Signature is valid using KERI verifiers</li>
     * </ol>
     *
     * @param note the note to validate
     * @return true if the note is valid for bootstrap purposes
     */
    boolean validateBootstrapNote(NoteWrapper note);
}
