/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.Committee;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for CommitteeStateHolder - validates committee state machine reference management.
 * Tests follow CHOAMStateManager extraction plan (Phase 3, Bead: Delos-k4ml)
 *
 * @author hal.hildebrand
 */
public class CommitteeStateHolderTest {

    private CommitteeStateHolder holder;

    @BeforeEach
    public void setUp() {
        holder = new CommitteeStateHolder();
    }

    /**
     * Test committee lifecycle: initially null, set, get, replace.
     */
    @Test
    public void testCommitteeLifecycle() {
        // Initially null
        assertNull(holder.getCommittee(), "Committee should be null initially");
        assertFalse(holder.hasCommittee(), "hasCommittee should be false initially");

        // Set committee
        Committee committee1 = mock(Committee.class);
        holder.setCommittee(committee1);
        assertSame(committee1, holder.getCommittee(), "Should return same committee");
        assertTrue(holder.hasCommittee(), "hasCommittee should be true after set");

        // Replace committee
        Committee committee2 = mock(Committee.class);
        holder.setCommittee(committee2);
        assertSame(committee2, holder.getCommittee(), "Should return new committee");
        assertTrue(holder.hasCommittee(), "hasCommittee should still be true");

        // Set to null
        holder.setCommittee(null);
        assertNull(holder.getCommittee(), "Should be null after clearing");
        assertFalse(holder.hasCommittee(), "hasCommittee should be false after clearing");
    }

    /**
     * Test CAS (Compare-And-Set) operations for atomic committee transitions.
     */
    @Test
    public void testCompareAndSet() {
        Committee committee1 = mock(Committee.class);
        Committee committee2 = mock(Committee.class);

        // CAS from null to committee1 should succeed
        assertTrue(holder.compareAndSetCommittee(null, committee1), "CAS from null should succeed");
        assertSame(committee1, holder.getCommittee(), "Should be committee1");

        // CAS from null to committee2 should fail (current is committee1)
        assertFalse(holder.compareAndSetCommittee(null, committee2), "CAS from null should fail");
        assertSame(committee1, holder.getCommittee(), "Should still be committee1");

        // CAS from committee1 to committee2 should succeed
        assertTrue(holder.compareAndSetCommittee(committee1, committee2), "CAS from committee1 should succeed");
        assertSame(committee2, holder.getCommittee(), "Should be committee2");

        // CAS from committee1 to null should fail (current is committee2)
        assertFalse(holder.compareAndSetCommittee(committee1, null), "CAS from committee1 should fail");
        assertSame(committee2, holder.getCommittee(), "Should still be committee2");
    }

    /**
     * Test getting committee type as string for debugging/logging.
     */
    @Test
    public void testGetCommitteeType() {
        // Null committee
        assertEquals("none", holder.getCommitteeType(), "Type should be 'none' when null");

        // Set mock committee
        Committee committee = mock(Committee.class);
        holder.setCommittee(committee);

        // Should return simple class name (ends with "MockitoMock" for Mockito mocks)
        String type = holder.getCommitteeType();
        assertNotNull(type, "Type should not be null");
        assertTrue(type.contains("Committee"), "Type should contain 'Committee'");
    }

    /**
     * Test direct access to AtomicReference for legacy compatibility.
     */
    @Test
    public void testDirectReferenceAccess() {
        Committee committee = mock(Committee.class);

        // Use direct reference access
        holder.getCommitteeRef().set(committee);
        assertSame(committee, holder.getCommittee(), "Direct set should work");

        // CAS via direct reference
        Committee committee2 = mock(Committee.class);
        assertTrue(holder.getCommitteeRef().compareAndSet(committee, committee2), "Direct CAS should work");
        assertSame(committee2, holder.getCommittee(), "Should be committee2");
    }

    /**
     * Test toString provides useful debug information.
     */
    @Test
    public void testToString() {
        // Null committee
        String str = holder.toString();
        assertTrue(str.contains("CommitteeStateHolder"), "Should contain class name");
        assertTrue(str.contains("none"), "Should indicate no committee");

        // With committee
        Committee committee = mock(Committee.class);
        holder.setCommittee(committee);
        str = holder.toString();
        assertTrue(str.contains("CommitteeStateHolder"), "Should contain class name");
        assertTrue(str.contains("Committee"), "Should contain committee type");
    }
}
