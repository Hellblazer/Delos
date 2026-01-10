/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

import java.lang.reflect.Field;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReadWriteLock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 0 Validation Test: Nested Lock Detection
 *
 * PURPOSE: Detect and prove that viewStateLock → pendingViews.lock nesting exists (lock hierarchy violation).
 *
 * EXPECTED BEHAVIOR:
 * - With current code: Test should FAIL (proving nested locks exist)
 * - After Phase 1: Test should PASS (ImmutablePendingViews has no internal lock)
 *
 * DETECTION MECHANISM:
 * This test uses reflection to inspect CHOAM's lock structure and verify that PendingViews has an internal
 * lock (ReadWriteLock). The nested locking occurs in CHOAM.reconfigure() line 762:
 * - Line 758: viewStateLock.lock()
 * - Line 762: pendingViews.advance() → acquires pendingViews.lock (nested!)
 * - Line 799: viewStateLock.unlock()
 *
 * KEY INSIGHT:
 * Nested locking (lock A → lock B) creates fragility and deadlock risk. The solution (Phase 1) is to eliminate
 * PendingViews' internal lock by making it immutable, so no nesting can occur.
 *
 * @author hal.hildebrand
 */
public class NestedLockDetectionTest {

    /**
     * Test: PendingViews should not have internal locks (after Phase 1).
     *
     * This test uses reflection to inspect CHOAM.PendingViews class structure and verifies that it has
     * an internal ReadWriteLock field. This proves the nested locking pattern exists.
     *
     * EXPECTED WITH CURRENT CODE: FAIL (internal lock detected, proving nested locking exists)
     * EXPECTED AFTER PHASE 1: PASS (no internal lock, ImmutablePendingViews has no locks)
     *
     * STATUS: Disabled during Phase 0. Will be enabled after Phase 1 eliminates nested locking.
     * This test is disabled here because it MUST fail to prove the problem exists, but CI should
     * remain green during Phase 0. Once Phase 1 extracts ImmutablePendingViews, this test will pass
     * and @Disabled annotation will be removed.
     */
    @Test
    @Disabled("Phase 0: Nested locking test disabled until Phase 1 fixes the problem. Will pass after Phase 1.")
    public void pendingViewsShouldNotHaveInternalLock() {
        try {
            // Use reflection to access CHOAM.PendingViews (static inner class)
            Class<?> pendingViewsClass = Class.forName("com.hellblazer.delos.choam.CHOAM$PendingViews");

            // Check if PendingViews has a lock field
            Field lockField = null;
            try {
                lockField = pendingViewsClass.getDeclaredField("lock");
            } catch (NoSuchFieldException e) {
                // No lock field found - this is GOOD (expected after Phase 1)
                // Test passes
                return;
            }

            // If we get here, lock field exists - this is BAD (current code)
            lockField.setAccessible(true);
            Class<?> lockType = lockField.getType();

            // Verify it's a ReadWriteLock (as documented in CHOAM.java line 1117)
            boolean isReadWriteLock = ReadWriteLock.class.isAssignableFrom(lockType);

            // Assert: PendingViews should NOT have an internal lock
            // With current code, this assertion will FAIL (proving nested locking exists)
            // After Phase 1, lockField lookup will throw NoSuchFieldException and test will PASS
            assertFalse(isReadWriteLock,
                       "NESTED LOCKING VIOLATION: PendingViews has internal ReadWriteLock. " +
                       "This creates lock hierarchy: viewStateLock → pendingViews.lock. " +
                       "Current code structure (line 1117): 'private final ReadWriteLock lock = new ReentrantReadWriteLock()'. " +
                       "After Phase 1 (ImmutablePendingViews), this test should pass (no lock field).");

        } catch (ClassNotFoundException e) {
            fail("Could not find CHOAM.PendingViews class via reflection. " +
                 "This test validates the internal structure of CHOAM.", e);
        }
    }

    /**
     * Test: CHOAM should have viewStateLock field (sanity check).
     *
     * This test verifies that CHOAM has the viewStateLock field that we're testing for nested locking with.
     * If this test fails, the nested lock detection test is invalid.
     */
    @Test
    public void choamShouldHaveViewStateLock() {
        try {
            Class<?> choamClass = CHOAM.class;
            Field viewStateLockField = choamClass.getDeclaredField("viewStateLock");
            viewStateLockField.setAccessible(true);

            // Verify it's a ReentrantLock
            Class<?> lockType = viewStateLockField.getType();
            assertTrue(ReentrantLock.class.isAssignableFrom(lockType),
                      "viewStateLock should be a ReentrantLock");

        } catch (NoSuchFieldException e) {
            fail("Could not find viewStateLock field in CHOAM. " +
                 "This is unexpected and invalidates nested lock detection test.", e);
        }
    }

    /**
     * Test: Document the lock hierarchy for future reference.
     *
     * This test extracts and documents the actual lock hierarchy in current CHOAM code.
     * It serves as baseline documentation for Phase 1 implementation.
     */
    @Test
    public void documentCurrentLockHierarchy() {
        System.out.println("=== CHOAM Lock Hierarchy Documentation ===");

        try {
            // Document viewStateLock
            Class<?> choamClass = CHOAM.class;
            Field viewStateLockField = choamClass.getDeclaredField("viewStateLock");
            System.out.println("1. CHOAM.viewStateLock: " + viewStateLockField.getType().getName());

            // Document PendingViews.lock
            Class<?> pendingViewsClass = Class.forName("com.hellblazer.delos.choam.CHOAM$PendingViews");
            Field lockField = pendingViewsClass.getDeclaredField("lock");
            System.out.println("2. PendingViews.lock: " + lockField.getType().getName());

            // Document nesting location
            System.out.println("\nNested locking occurs in CHOAM.reconfigure():");
            System.out.println("  Line 758: viewStateLock.lock()");
            System.out.println("  Line 762: var pv = pendingViews.advance() → acquires pendingViews.lock");
            System.out.println("  Line 799: viewStateLock.unlock()");
            System.out.println("\nAfter Phase 1: ImmutablePendingViews will have NO lock field.");

        } catch (Exception e) {
            System.out.println("Could not document lock hierarchy: " + e.getMessage());
        }

        // This test always passes - it's for documentation only
        System.out.println("=== End Lock Hierarchy Documentation ===\n");
    }
}
