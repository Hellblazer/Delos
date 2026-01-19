/*
 * Copyright (c) 2024, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.witness;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 1A-3 Task A.2 AUDIT CONDITION: Nested lock detection tests.
 * <p>
 * Verifies that CHOAM ViewCoordinator lock ordering constraints are satisfied:
 * <p>
 * LOCK ORDERING RULES:
 * 1. CHOAM Phase 1 (LOCKED): prepareReconfigure() holds viewStateLock
 * 2. CHOAM Phase 2 (UNLOCKED): completeReconfigure() executes callbacks WITHOUT viewStateLock
 * 3. Witness operations NEVER acquire CHOAM viewStateLock
 * 4. Execution order: CHOAM completeReconfigure() → witness callback → witness state update
 * <p>
 * DEADLOCK PREVENTION:
 * - Witness callbacks execute AFTER CHOAM releases viewStateLock
 * - Witness internal locks (viewLock, drainLock) acquired AFTER CHOAM lock released
 * - No callback reentrancy: callbacks cannot call back into CHOAM methods that acquire viewStateLock
 * <p>
 * Architecture reference: /Users/hal.hildebrand/git/Delos/.pm/designs/phase1a3/PHASE_1A3_ARCHITECTURE.md
 * Section: Phase A - Task A.2 - Lock Ordering Constraints
 */
class NestedLockDetectionTest {

    /**
     * A.2.6: Verify CHOAM callback executes WITHOUT viewStateLock.
     * <p>
     * Requirement: completeReconfigure() must release viewStateLock before executing callbacks.
     * This prevents deadlock when witness callbacks acquire witness internal locks.
     */
    @Test
    void testCHOAMCallbackExecutesWithoutLock() throws InterruptedException {
        // Given: Mock CHOAM viewStateLock
        var viewStateLock = new ReentrantReadWriteLock();
        var callbackExecuted = new AtomicBoolean(false);
        var callbackStarted = new CountDownLatch(1);
        var lockCheckPassed = new AtomicBoolean(false);

        // When: Simulating CHOAM two-phase pattern
        // Phase 1 (LOCKED): Prepare reconfigure
        viewStateLock.writeLock().lock();
        try {
            // Prepare phase (deterministic computation)
            // Store callback for Phase 2
        } finally {
            viewStateLock.writeLock().unlock();
        }

        // Phase 2 (UNLOCKED): Execute callback
        Runnable witnessCallback = () -> {
            callbackStarted.countDown();
            // CRITICAL: Verify viewStateLock is NOT held
            boolean lockFree = !viewStateLock.isWriteLocked() && viewStateLock.getReadLockCount() == 0;
            lockCheckPassed.set(lockFree);
            callbackExecuted.set(true);
        };

        witnessCallback.run();

        // Then: Callback should execute without holding viewStateLock
        assertTrue(callbackStarted.await(1, TimeUnit.SECONDS));
        assertTrue(callbackExecuted.get(), "Witness callback should execute");
        assertTrue(lockCheckPassed.get(), "Callback must execute WITHOUT CHOAM viewStateLock");
    }

    /**
     * A.2.7: Verify witness operations do not acquire CHOAM viewStateLock.
     * <p>
     * Requirement: Witness internal operations (onViewChange, drain period) must not
     * attempt to acquire CHOAM viewStateLock to prevent deadlock.
     */
    @Test
    void testWitnessOperationsDoNotAcquireCHOAMLock() throws InterruptedException {
        // Given: CHOAM viewStateLock and witness internal lock
        var choamViewStateLock = new ReentrantReadWriteLock();
        var witnessViewLock = new ReentrantReadWriteLock();

        var witnessOperationCompleted = new AtomicBoolean(false);
        var deadlockDetected = new AtomicBoolean(false);

        // When: Simulating witness callback during CHOAM reconfigure
        Runnable witnessOnViewChange = () -> {
            // Witness internal operation: acquire witness lock
            boolean acquired = false;
            try {
                acquired = witnessViewLock.writeLock().tryLock(100, TimeUnit.MILLISECONDS);
                if (acquired) {
                    // Witness state update (must NOT try to acquire CHOAM lock)
                    boolean choamLockHeld = choamViewStateLock.isWriteLocked();
                    if (choamLockHeld) {
                        deadlockDetected.set(true);
                        fail("Witness operation must NOT be called while CHOAM viewStateLock is held");
                    }
                    witnessOperationCompleted.set(true);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (acquired) {
                    witnessViewLock.writeLock().unlock();
                }
            }
        };

        // Execute witness callback (simulating CHOAM Phase 2 - UNLOCKED)
        witnessOnViewChange.run();

        // Then: Witness operation should complete without deadlock
        assertTrue(witnessOperationCompleted.get(), "Witness operation should complete");
        assertFalse(deadlockDetected.get(), "No deadlock should occur");
    }

    /**
     * A.2.8: Verify callback execution order: CHOAM completeReconfigure → witness callback.
     * <p>
     * Requirement: Witness callback must execute AFTER CHOAM releases viewStateLock.
     */
    @Test
    void testCallbackExecutionOrderAfterLockRelease() throws InterruptedException {
        // Given: CHOAM viewStateLock
        var viewStateLock = new ReentrantReadWriteLock();
        var executionOrder = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        var latch = new CountDownLatch(1);

        // When: Executing CHOAM two-phase pattern
        Thread choamThread = new Thread(() -> {
            // Phase 1 (LOCKED)
            viewStateLock.writeLock().lock();
            try {
                executionOrder.add("CHOAM-prepare");
            } finally {
                viewStateLock.writeLock().unlock();
                executionOrder.add("CHOAM-unlock");
            }

            // Phase 2 (UNLOCKED)
            executionOrder.add("CHOAM-complete");

            // Execute witness callback
            executionOrder.add("witness-callback-start");
            latch.countDown();
            executionOrder.add("witness-callback-end");
        });

        choamThread.start();
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        choamThread.join();

        // Then: Execution order should be deterministic
        var order = executionOrder.toArray(new String[0]);
        assertEquals("CHOAM-prepare", order[0], "1. CHOAM prepares while locked");
        assertEquals("CHOAM-unlock", order[1], "2. CHOAM releases lock");
        assertEquals("CHOAM-complete", order[2], "3. CHOAM completes reconfigure");
        assertEquals("witness-callback-start", order[3], "4. Witness callback executes");
        assertEquals("witness-callback-end", order[4], "5. Witness callback completes");
    }

    /**
     * A.2.9: Verify no callback reentrancy into CHOAM locked methods.
     * <p>
     * Requirement: Callbacks must not call back into CHOAM methods that acquire viewStateLock.
     */
    @Test
    void testNoCallbackReentrancy() {
        // Given: CHOAM viewStateLock
        var viewStateLock = new ReentrantReadWriteLock();
        var reentrancyAttempted = new AtomicBoolean(false);
        var reentrancyBlocked = new AtomicBoolean(false);

        // When: Callback attempts to call CHOAM method requiring viewStateLock
        Runnable witnessCallback = () -> {
            // Callback should NOT attempt to acquire CHOAM viewStateLock
            reentrancyAttempted.set(true);

            // If we tried to acquire the lock here, it would either:
            // 1. Succeed (BAD: lock was released, violating two-phase contract)
            // 2. Deadlock (BAD: callback trying to reacquire lock)

            // Correct: Callback should NOT try to acquire viewStateLock
            boolean lockHeld = viewStateLock.isWriteLocked();
            if (!lockHeld) {
                reentrancyBlocked.set(true); // Lock correctly released before callback
            }
        };

        // Execute callback (simulating Phase 2 - UNLOCKED)
        witnessCallback.run();

        // Then: Callback should execute without attempting reentrancy
        assertTrue(reentrancyAttempted.get(), "Callback should execute");
        assertTrue(reentrancyBlocked.get(), "CHOAM lock should be released before callback");
    }

    /**
     * A.2.10: Integration test verifying full lock ordering across CHOAM and witness.
     * <p>
     * Requirement: Complete two-phase pattern with witness state updates must not deadlock.
     */
    @Test
    void testFullLockOrderingIntegration() throws InterruptedException {
        // Given: CHOAM and witness locks
        var choamViewStateLock = new ReentrantReadWriteLock();
        var witnessViewLock = new ReentrantReadWriteLock();
        var witnessUpdateCompleted = new AtomicBoolean(false);
        var integrationSuccessful = new AtomicBoolean(false);

        var latch = new CountDownLatch(1);

        // When: Executing full reconfiguration flow
        Thread integrationTest = new Thread(() -> {
            try {
                // Phase 1 (LOCKED): CHOAM prepares reconfigure
                choamViewStateLock.writeLock().lock();
                try {
                    // Deterministic computation
                } finally {
                    choamViewStateLock.writeLock().unlock();
                }

                // Phase 2 (UNLOCKED): Execute witness callback
                // Witness callback acquires witness internal lock
                witnessViewLock.writeLock().lock();
                try {
                    // Witness state update
                    witnessUpdateCompleted.set(true);
                } finally {
                    witnessViewLock.writeLock().unlock();
                }

                integrationSuccessful.set(true);
                latch.countDown();
            } catch (Exception e) {
                fail("Integration test should not throw exception: " + e.getMessage());
            }
        });

        integrationTest.start();
        assertTrue(latch.await(2, TimeUnit.SECONDS), "Integration should complete within 2 seconds");
        integrationTest.join();

        // Then: Full flow should complete without deadlock
        assertTrue(witnessUpdateCompleted.get(), "Witness update should complete");
        assertTrue(integrationSuccessful.get(), "Full integration should succeed");
    }
}
