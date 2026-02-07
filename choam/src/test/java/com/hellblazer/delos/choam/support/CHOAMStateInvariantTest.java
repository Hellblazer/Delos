/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.fsm.Combine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CHOAMStateInvariant enum.
 * Verifies that each invariant correctly validates snapshots matching its state
 * and rejects snapshots that violate invariant conditions.
 *
 * @author hal.hildebrand
 */
public class CHOAMStateInvariantTest {

    @Test
    public void testInitialInvariant() {
        // Valid INITIAL state: not started, no committee, no genesis
        var validSnapshot = new CHOAMStateSnapshot(
            false, false,  // not started, no join
            false, null,   // no committee
            false, false, -1,  // no genesis, no head
            false, -1, 0,  // no view
            0, false, false,  // no async ops
            "INITIAL"
        );
        assertTrue(CHOAMStateInvariant.INITIAL_INVARIANT.test(validSnapshot));

        // Invalid: started in INITIAL state
        var invalidSnapshot = new CHOAMStateSnapshot(
            true, false,  // started - VIOLATES INVARIANT
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "INITIAL"
        );
        assertFalse(CHOAMStateInvariant.INITIAL_INVARIANT.test(invalidSnapshot));
    }

    @Test
    public void testRecoveringInvariant() {
        // Valid RECOVERING state: started, no committee
        var validSnapshot = new CHOAMStateSnapshot(
            true, false,  // started
            false, null,  // no committee yet
            false, false, -1,  // may not have genesis yet
            false, -1, 0,
            0, false, false,
            "RECOVERING"
        );
        assertTrue(CHOAMStateInvariant.RECOVERING_INVARIANT.test(validSnapshot));

        // Invalid: not started in RECOVERING state
        var invalidSnapshot = new CHOAMStateSnapshot(
            false, false,  // not started - VIOLATES INVARIANT
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "RECOVERING"
        );
        assertFalse(CHOAMStateInvariant.RECOVERING_INVARIANT.test(invalidSnapshot));
    }

    @Test
    public void testBootstrappingInvariant() {
        // Valid BOOTSTRAPPING state: started, GenesisFormation committee
        var validSnapshot = new CHOAMStateSnapshot(
            true, false,
            true, "GenesisFormation",  // GenesisFormation committee
            false, false, -1,
            false, -1, 0,
            0, true, false,
            "BOOTSTRAPPING"
        );
        assertTrue(CHOAMStateInvariant.BOOTSTRAPPING_INVARIANT.test(validSnapshot));

        // Invalid: Standard committee instead of GenesisFormation
        var invalidSnapshot = new CHOAMStateSnapshot(
            true, false,
            true, "Standard",  // Wrong committee type - VIOLATES INVARIANT
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "BOOTSTRAPPING"
        );
        assertFalse(CHOAMStateInvariant.BOOTSTRAPPING_INVARIANT.test(invalidSnapshot));
    }

    @Test
    public void testSynchronizingInvariant() {
        // Valid SYNCHRONIZING state: started, committee, sync scheduled
        var validSnapshot = new CHOAMStateSnapshot(
            true, false,
            true, "Standard",
            false, false, -1,
            false, -1, 0,
            0, false, true,  // sync scheduled
            "SYNCHRONIZING"
        );
        assertTrue(CHOAMStateInvariant.SYNCHRONIZING_INVARIANT.test(validSnapshot));

        // Invalid: sync not scheduled
        var invalidSnapshot = new CHOAMStateSnapshot(
            true, false,
            true, "Standard",
            false, false, -1,
            false, -1, 0,
            0, false, false,  // sync not scheduled - VIOLATES INVARIANT
            "SYNCHRONIZING"
        );
        assertFalse(CHOAMStateInvariant.SYNCHRONIZING_INVARIANT.test(invalidSnapshot));
    }

    @Test
    public void testOperationalInvariant() {
        // Valid OPERATIONAL state: started, genesis, committee, view
        var validSnapshot = new CHOAMStateSnapshot(
            true, false,
            true, "Standard",
            true, true, 100,  // has genesis and head
            true, 95, 2,      // has view
            5, false, false,
            "OPERATIONAL"
        );
        assertTrue(CHOAMStateInvariant.OPERATIONAL_INVARIANT.test(validSnapshot));

        // Invalid: no view in OPERATIONAL state
        var invalidSnapshot = new CHOAMStateSnapshot(
            true, false,
            true, "Standard",
            true, true, 100,
            false, -1, 0,  // no view - VIOLATES INVARIANT
            0, false, false,
            "OPERATIONAL"
        );
        assertFalse(CHOAMStateInvariant.OPERATIONAL_INVARIANT.test(invalidSnapshot));
    }

    @Test
    public void testCheckpointingInvariant() {
        // Valid CHECKPOINTING state: operational + head with positive height
        var validSnapshot = new CHOAMStateSnapshot(
            true, false,
            true, "Standard",
            true, true, 50,  // head height > 0
            true, 45, 1,
            0, false, false,
            "CHECKPOINTING"
        );
        assertTrue(CHOAMStateInvariant.CHECKPOINTING_INVARIANT.test(validSnapshot));

        // Invalid: head height = 0
        var invalidSnapshot = new CHOAMStateSnapshot(
            true, false,
            true, "Standard",
            true, true, 0,  // head height = 0 - VIOLATES INVARIANT (need > 0)
            true, 0, 0,
            0, false, false,
            "CHECKPOINTING"
        );
        assertFalse(CHOAMStateInvariant.CHECKPOINTING_INVARIANT.test(invalidSnapshot));
    }

    @Test
    public void testRegeneratingInvariant() {
        // Valid REGENERATING state: started, committee
        var validSnapshot = new CHOAMStateSnapshot(
            true, false,
            true, "Standard",  // has committee
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "REGENERATING"
        );
        assertTrue(CHOAMStateInvariant.REGENERATING_INVARIANT.test(validSnapshot));

        // Invalid: no committee in REGENERATING state
        var invalidSnapshot = new CHOAMStateSnapshot(
            true, false,
            false, null,  // no committee - VIOLATES INVARIANT
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "REGENERATING"
        );
        assertFalse(CHOAMStateInvariant.REGENERATING_INVARIANT.test(invalidSnapshot));
    }

    @Test
    public void testAwaitingRegenerationInvariant() {
        // Valid AWAITING_REGENERATION state: just needs to be started
        var validSnapshot = new CHOAMStateSnapshot(
            true, false,  // started
            false, null,  // may not have committee yet
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "AWAITING_REGENERATION"
        );
        assertTrue(CHOAMStateInvariant.AWAITING_REGENERATION_INVARIANT.test(validSnapshot));

        // Invalid: not started
        var invalidSnapshot = new CHOAMStateSnapshot(
            false, false,  // not started - VIOLATES INVARIANT
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "AWAITING_REGENERATION"
        );
        assertFalse(CHOAMStateInvariant.AWAITING_REGENERATION_INVARIANT.test(invalidSnapshot));
    }

    @Test
    public void testProtocolFailureInvariant() {
        // PROTOCOL_FAILURE accepts any state (no invariants)
        var anySnapshot1 = new CHOAMStateSnapshot(
            false, false,
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "PROTOCOL_FAILURE"
        );
        assertTrue(CHOAMStateInvariant.PROTOCOL_FAILURE_INVARIANT.test(anySnapshot1));

        // Even contradictory state is accepted
        var anySnapshot2 = new CHOAMStateSnapshot(
            true, true,
            true, "GenesisFormation",
            true, true, 1000,
            true, 999, 50,
            10, true, true,
            "PROTOCOL_FAILURE"
        );
        assertTrue(CHOAMStateInvariant.PROTOCOL_FAILURE_INVARIANT.test(anySnapshot2));
    }

    @Test
    public void testForState() {
        // Verify mapping from states to invariants
        assertEquals(CHOAMStateInvariant.INITIAL_INVARIANT,
                     CHOAMStateInvariant.forState(Combine.Mercantile.INITIAL));
        assertEquals(CHOAMStateInvariant.RECOVERING_INVARIANT,
                     CHOAMStateInvariant.forState(Combine.Mercantile.RECOVERING));
        assertEquals(CHOAMStateInvariant.OPERATIONAL_INVARIANT,
                     CHOAMStateInvariant.forState(Combine.Mercantile.OPERATIONAL));
        assertEquals(CHOAMStateInvariant.PROTOCOL_FAILURE_INVARIANT,
                     CHOAMStateInvariant.forState(Combine.Mercantile.PROTOCOL_FAILURE));
    }

    @Test
    public void testForStateName() {
        // Verify string-based lookup
        assertEquals(CHOAMStateInvariant.INITIAL_INVARIANT,
                     CHOAMStateInvariant.forStateName("INITIAL"));
        assertEquals(CHOAMStateInvariant.OPERATIONAL_INVARIANT,
                     CHOAMStateInvariant.forStateName("OPERATIONAL"));

        // Invalid state name
        assertThrows(IllegalArgumentException.class,
                     () -> CHOAMStateInvariant.forStateName("INVALID_STATE"));
    }

    @Test
    public void testForStateNullHandling() {
        // Null state should throw
        assertThrows(IllegalArgumentException.class,
                     () -> CHOAMStateInvariant.forState(null));
    }

    @Test
    public void testInvariantDescriptions() {
        // Verify all invariants have descriptions
        for (var invariant : CHOAMStateInvariant.values()) {
            assertNotNull(invariant.getDescription());
            assertFalse(invariant.getDescription().isBlank());
        }
    }
}
