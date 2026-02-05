/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.support.Bootstrapper.SynchronizedState;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages CHOAM async operation state (bootstrap, synchronization futures, retry tracking).
 * <p>
 * This holder encapsulates async operation tracking that was previously embedded in CHOAM.java.
 * It provides lock-free state management for bootstrap operations, synchronization scheduling,
 * and retry attempt counting.
 * </p>
 * <p>
 * <b>Invariants:</b>
 * <ul>
 *   <li>syncAttempts >= 0 and bounded (< 100, enforced by caller)</li>
 *   <li>At most one bootstrap operation active at a time</li>
 * </ul>
 * </p>
 * <p>
 * <b>Thread Safety:</b> All operations are lock-free and thread-safe via AtomicReference/AtomicInteger.
 * </p>
 * <p>
 * Extracted as part of CHOAMStateManager extraction plan (Phase 2, Bead: Delos-5jo6).
 * See choam/BASELINE.md and choam/LOCK_ORDERING.md for context.
 * </p>
 *
 * @author hal.hildebrand
 */
public class AsyncOperationStateHolder {

    /**
     * Bootstrap future: tracks ongoing bootstrap operation.
     * Null when no bootstrap is active.
     */
    private final AtomicReference<CompletableFuture<SynchronizedState>> futureBootstrap = new AtomicReference<>();

    /**
     * Synchronization future: tracks scheduled synchronization operation.
     * Null when no sync is scheduled.
     */
    private final AtomicReference<ScheduledFuture<?>> futureSynchronization = new AtomicReference<>();

    /**
     * Synchronization attempt counter: tracks retry attempts.
     * Should be reset when sync succeeds or is cancelled.
     * Caller should enforce bound (< 100) to prevent infinite retries.
     */
    private final AtomicInteger syncAttempts = new AtomicInteger(0);

    /**
     * Get the current bootstrap future.
     *
     * @return the bootstrap future, or null if none active
     */
    public CompletableFuture<SynchronizedState> getBootstrapFuture() {
        return futureBootstrap.get();
    }

    /**
     * Set the bootstrap future using CAS (Compare-And-Set).
     * <p>
     * This ensures only one bootstrap operation can be active at a time.
     * </p>
     *
     * @param future the bootstrap future to set
     * @return true if set successfully, false if another bootstrap is already active
     */
    public boolean setBootstrapFuture(CompletableFuture<SynchronizedState> future) {
        return futureBootstrap.compareAndSet(null, future);
    }

    /**
     * Clear the bootstrap future.
     * <p>
     * This operation is idempotent and safe to call multiple times.
     * </p>
     */
    public void clearBootstrapFuture() {
        futureBootstrap.set(null);
    }

    /**
     * Get the current synchronization future.
     *
     * @return the sync future, or null if none scheduled
     */
    public ScheduledFuture<?> getSyncFuture() {
        return futureSynchronization.get();
    }

    /**
     * Set the synchronization future using CAS (Compare-And-Set).
     * <p>
     * This ensures only one sync operation can be scheduled at a time.
     * </p>
     *
     * @param future the sync future to set
     * @return true if set successfully, false if another sync is already scheduled
     */
    public boolean setSyncFuture(ScheduledFuture<?> future) {
        return futureSynchronization.compareAndSet(null, future);
    }

    /**
     * Clear the synchronization future.
     * <p>
     * This operation is idempotent and safe to call multiple times.
     * </p>
     */
    public void clearSyncFuture() {
        futureSynchronization.set(null);
    }

    /**
     * Get the current sync attempt count.
     *
     * @return the number of sync attempts
     */
    public int getSyncAttempts() {
        return syncAttempts.get();
    }

    /**
     * Increment the sync attempt counter atomically.
     *
     * @return the new attempt count after increment
     */
    public int incrementSyncAttempts() {
        return syncAttempts.incrementAndGet();
    }

    /**
     * Reset the sync attempt counter to zero.
     */
    public void resetSyncAttempts() {
        syncAttempts.set(0);
    }

    /**
     * Get direct access to the futureBootstrap AtomicReference (for legacy code compatibility).
     * <p>
     * <b>Note:</b> This is provided for backward compatibility with code that directly
     * manipulates the AtomicReference. Prefer using the accessor methods.
     * </p>
     *
     * @return the internal futureBootstrap AtomicReference
     */
    public AtomicReference<CompletableFuture<SynchronizedState>> getFutureBootstrapRef() {
        return futureBootstrap;
    }

    /**
     * Get direct access to the futureSynchronization AtomicReference (for legacy code compatibility).
     * <p>
     * <b>Note:</b> This is provided for backward compatibility with code that directly
     * manipulates the AtomicReference. Prefer using the accessor methods.
     * </p>
     *
     * @return the internal futureSynchronization AtomicReference
     */
    public AtomicReference<ScheduledFuture<?>> getFutureSynchronizationRef() {
        return futureSynchronization;
    }

    /**
     * Get direct access to the syncAttempts AtomicInteger (for legacy code compatibility).
     * <p>
     * <b>Note:</b> This is provided for backward compatibility with code that directly
     * manipulates the AtomicInteger. Prefer using the accessor methods.
     * </p>
     *
     * @return the internal syncAttempts AtomicInteger
     */
    public AtomicInteger getSyncAttemptsRef() {
        return syncAttempts;
    }

    /**
     * Get a human-readable representation of the async operation state.
     *
     * @return string representation showing bootstrap, sync futures, and attempt count
     */
    @Override
    public String toString() {
        return String.format("AsyncOperationStateHolder{bootstrapActive=%s, syncActive=%s, attempts=%d}",
                             futureBootstrap.get() != null, futureSynchronization.get() != null,
                             syncAttempts.get());
    }
}
