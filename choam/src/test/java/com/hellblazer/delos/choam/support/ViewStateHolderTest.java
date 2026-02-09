/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.ViewCoordinator;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ViewStateHolder - validates view state management.
 * Tests follow CHOAMStateManager extraction plan (Phase 5, Bead: Delos-zfb1)
 *
 * @author hal.hildebrand
 */
public class ViewStateHolderTest {

    private ViewStateHolder holder;
    private ViewCoordinator mockCoordinator;

    @BeforeEach
    public void setUp() {
        mockCoordinator = Mockito.mock(ViewCoordinator.class);
        holder = new ViewStateHolder(mockCoordinator);
    }

    /**
     * Test nextViewId lifecycle: initially null, can be set and retrieved.
     */
    @Test
    public void testNextViewIdLifecycle() {
        // Initially null
        assertNull(holder.getNextViewId(), "NextViewId should be null initially");

        // Set and retrieve
        var viewId = DigestAlgorithm.DEFAULT.digest("test-view".getBytes());
        holder.setNextViewId(viewId);
        assertSame(viewId, holder.getNextViewId(), "Should return same view ID");

        // Can set to null
        holder.setNextViewId(null);
        assertNull(holder.getNextViewId(), "Should be null after clear");
    }

    /**
     * Test next view member lifecycle: initially null, can be set and retrieved.
     */
    @Test
    public void testNextMemberLifecycle() {
        // Initially null
        assertNull(holder.getNext(), "Next should be null initially");

        // Set and retrieve (using String as test object)
        var testNext = "test-next-view";
        holder.setNext(testNext);
        assertSame(testNext, holder.getNext(), "Should return same next");

        // Can set to null
        holder.setNext(null);
        assertNull(holder.getNext(), "Should be null after clear");
    }

    /**
     * Test pending views initialization with default EMPTY.
     */
    @Test
    public void testPendingViewsInitialization() {
        // Should be EMPTY initially
        assertNotNull(holder.getPendingViews(), "PendingViews should not be null");
        assertEquals(0, holder.getPendingViews().size(), "Should be empty initially");
    }

    /**
     * Test pending views can be set and retrieved.
     */
    @Test
    public void testPendingViewsSetGet() {
        var empty = ImmutablePendingViews.EMPTY;
        holder.setPendingViews(empty);
        assertSame(empty, holder.getPendingViews(), "Should return same pending views");
    }

    /**
     * Test coordinator retrieval.
     */
    @Test
    public void testCoordinatorRetrieval() {
        assertSame(mockCoordinator, holder.getCoordinator(), "Should return same coordinator");
    }

    /**
     * Test lock exposure: viewStateLock should be accessible for callers.
     */
    @Test
    public void testLockExposure() {
        assertNotNull(holder.viewStateLock, "viewStateLock should not be null");

        // Verify it's a valid lock
        holder.viewStateLock.lock();
        try {
            // Can set nextViewId while holding lock
            holder.setNextViewId(DigestAlgorithm.DEFAULT.digest("test".getBytes()));
        } finally {
            holder.viewStateLock.unlock();
        }

        assertNotNull(holder.getNextViewId(), "NextViewId should be set");
    }

    /**
     * Test legacy compatibility methods for AtomicReference access.
     */
    @Test
    public void testLegacyReferenceAccess() {
        // nextViewId ref
        assertNotNull(holder.getNextViewIdRef(), "NextViewId ref should not be null");
        var viewId = DigestAlgorithm.DEFAULT.digest("test".getBytes());
        holder.getNextViewIdRef().set(viewId);
        assertSame(viewId, holder.getNextViewId(), "Direct ref set should work");

        // next ref
        assertNotNull(holder.getNextRef(), "Next ref should not be null");
        var testNext = "test-next";
        holder.getNextRef().set(testNext);
        assertSame(testNext, holder.getNext(), "Direct ref set should work");

        // pendingViews ref
        assertNotNull(holder.getPendingViewsRef(), "PendingViews ref should not be null");
        var empty = ImmutablePendingViews.EMPTY;
        holder.getPendingViewsRef().set(empty);
        assertSame(empty, holder.getPendingViews(), "Direct ref set should work");
    }

    /**
     * Test toString provides useful debug information.
     */
    @Test
    public void testToString() {
        String str = holder.toString();
        assertTrue(str.contains("ViewStateHolder"), "Should contain class name");
        assertTrue(str.contains("nextViewId="), "Should show nextViewId");
        assertTrue(str.contains("next="), "Should show next");
        assertTrue(str.contains("pendingViews="), "Should show pending count");
    }

    /**
     * Test constructor with initial pending views.
     */
    @Test
    public void testConstructorWithInitialPendingViews() {
        var initialViews = ImmutablePendingViews.EMPTY;
        var holder2 = new ViewStateHolder(mockCoordinator, initialViews);
        assertSame(initialViews, holder2.getPendingViews(), "Should use initial pending views");
    }
}
