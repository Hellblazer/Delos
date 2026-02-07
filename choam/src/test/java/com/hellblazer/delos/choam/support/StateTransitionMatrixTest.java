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

import static com.hellblazer.delos.choam.fsm.Combine.Mercantile.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for StateTransitionMatrix.
 * Verifies completeness against Combine.Mercantile and transition validity.
 *
 * @author hal.hildebrand
 */
public class StateTransitionMatrixTest {

    private final StateTransitionMatrix matrix = StateTransitionMatrix.getInstance();

    @Test
    public void testMatrixCompleteness() {
        // Verify expected size: 55 transitions
        // 21 explicit valid + 13 PROTOCOL_FAILURE + 8 fail() + 7 nextView defaults + 6 rotateKeys defaults = 55
        assertTrue(matrix.isComplete(), "Matrix should have exactly 55 transitions");
        assertEquals(55, matrix.size());
    }

    @Test
    public void testInitialStateTransitions() {
        // INITIAL has 1 valid transition: start → RECOVERING
        var start = matrix.getTransition(INITIAL, "start");
        assertTrue(start.isPresent());
        assertEquals(RECOVERING, start.get().target());
        assertFalse(start.get().isLoopback());

        // INITIAL also has fail() → PROTOCOL_FAILURE
        var fail = matrix.getTransition(INITIAL, "fail");
        assertTrue(fail.isPresent());
        assertEquals(PROTOCOL_FAILURE, fail.get().target());
    }

    @Test
    public void testRecoveringStateTransitions() {
        // RECOVERING has 4 valid transitions
        var bootstrap = matrix.getTransition(RECOVERING, "bootstrap");
        assertTrue(bootstrap.isPresent());
        assertEquals(BOOTSTRAPPING, bootstrap.get().target());

        var combine = matrix.getTransition(RECOVERING, "combine");
        assertTrue(combine.isPresent());
        assertTrue(combine.get().isLoopback());  // Returns null

        var regenerate = matrix.getTransition(RECOVERING, "regenerate");
        assertTrue(regenerate.isPresent());
        assertEquals(REGENERATING, regenerate.get().target());

        var syncFailed = matrix.getTransition(RECOVERING, "synchronizationFailed");
        assertTrue(syncFailed.isPresent());
        assertEquals(AWAITING_REGENERATION, syncFailed.get().target());
    }

    @Test
    public void testOperationalStateTransitions() {
        // OPERATIONAL has 3 valid transitions
        var combine = matrix.getTransition(OPERATIONAL, "combine");
        assertTrue(combine.isPresent());
        assertTrue(combine.get().isLoopback());

        var checkpoint = matrix.getTransition(OPERATIONAL, "beginCheckpoint");
        assertTrue(checkpoint.isPresent());
        assertTrue(checkpoint.get().isLoopback());  // FSM push, returns null

        var rotateKeys = matrix.getTransition(OPERATIONAL, "rotateViewKeys");
        assertTrue(rotateKeys.isPresent());
        assertTrue(rotateKeys.get().isLoopback());
    }

    @Test
    public void testProtocolFailureTransitions() {
        // PROTOCOL_FAILURE has 13 transitions (all return null, absorbing state)
        var transitions = new String[] {
            "beginCheckpoint", "bootstrap", "combine", "fail",
            "finishCheckpoint", "nextView", "regenerate", "regenerated",
            "rotateViewKeys", "start", "synchd", "synchronizationFailed",
            "synchronizing"
        };

        for (var transitionName : transitions) {
            var spec = matrix.getTransition(PROTOCOL_FAILURE, transitionName);
            assertTrue(spec.isPresent(),
                       "PROTOCOL_FAILURE should have " + transitionName + " transition");
            assertTrue(spec.get().isLoopback(),
                       "PROTOCOL_FAILURE transitions should be loopback (absorbing state)");
        }
    }

    @Test
    public void testFailTransitionsFromAllStates() {
        // All states except PROTOCOL_FAILURE can transition to PROTOCOL_FAILURE via fail()
        var states = new Combine.Mercantile[] {
            INITIAL, RECOVERING, BOOTSTRAPPING, SYNCHRONIZING,
            OPERATIONAL, CHECKPOINTING, REGENERATING, AWAITING_REGENERATION
        };

        for (var state : states) {
            var fail = matrix.getTransition(state, "fail");
            assertTrue(fail.isPresent(),
                       state + " should have fail() transition");
            assertEquals(PROTOCOL_FAILURE, fail.get().target(),
                         state + " fail() should target PROTOCOL_FAILURE");
        }
    }

    @Test
    public void testLoopbackTransitions() {
        // Verify loopback detection
        var loopback = matrix.getTransition(OPERATIONAL, "combine");
        assertTrue(loopback.isPresent());
        assertTrue(loopback.get().isLoopback());
        assertNull(loopback.get().target());

        // Non-loopback
        var nonLoopback = matrix.getTransition(INITIAL, "start");
        assertTrue(nonLoopback.isPresent());
        assertFalse(nonLoopback.get().isLoopback());
        assertNotNull(nonLoopback.get().target());
    }

    @Test
    public void testTransitionPreconditions() {
        // Verify preconditions are defined for all transitions
        var start = matrix.getTransition(INITIAL, "start");
        assertTrue(start.isPresent());
        assertNotNull(start.get().precondition());

        // Test precondition logic: INITIAL→start requires !started
        var validSnapshot = new CHOAMStateSnapshot(
            false, false,  // not started
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "INITIAL"
        );
        assertTrue(start.get().precondition().test(validSnapshot));

        var invalidSnapshot = new CHOAMStateSnapshot(
            true, false,  // already started - violates precondition
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "INITIAL"
        );
        assertFalse(start.get().precondition().test(invalidSnapshot));
    }

    @Test
    public void testTransitionPostconditions() {
        // Verify postconditions are defined for all transitions
        var start = matrix.getTransition(INITIAL, "start");
        assertTrue(start.isPresent());
        assertNotNull(start.get().postcondition());

        // Test postcondition logic: INITIAL→start should result in started=true
        var preSnapshot = new CHOAMStateSnapshot(
            false, false,
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "INITIAL"
        );

        var validPostSnapshot = new CHOAMStateSnapshot(
            true, false,  // started after transition
            false, null,  // no committee yet
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "RECOVERING"
        );
        assertTrue(start.get().postcondition().test(preSnapshot, validPostSnapshot));

        var invalidPostSnapshot = new CHOAMStateSnapshot(
            false, false,  // still not started - violates postcondition
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "RECOVERING"
        );
        assertFalse(start.get().postcondition().test(preSnapshot, invalidPostSnapshot));
    }

    @Test
    public void testTransitionDescriptions() {
        // Verify all transitions have descriptions
        var start = matrix.getTransition(INITIAL, "start");
        assertTrue(start.isPresent());
        assertNotNull(start.get().description());
        assertFalse(start.get().description().isBlank());
    }

    @Test
    public void testInvalidTransitionLookup() {
        // Non-existent transition should return empty
        var invalid = matrix.getTransition(INITIAL, "nonexistentTransition");
        assertTrue(invalid.isEmpty());
    }

    @Test
    public void testBootstrappingTransitions() {
        // BOOTSTRAPPING has 5 valid transitions (3 explicit + 2 defaults: nextView, rotateViewKeys)
        assertEquals(5, countTransitionsFor(BOOTSTRAPPING));

        var combine = matrix.getTransition(BOOTSTRAPPING, "combine");
        assertTrue(combine.isPresent());

        var synchronizing = matrix.getTransition(BOOTSTRAPPING, "synchronizing");
        assertTrue(synchronizing.isPresent());
        assertEquals(SYNCHRONIZING, synchronizing.get().target());

        var bootstrap = matrix.getTransition(BOOTSTRAPPING, "bootstrap");
        assertTrue(bootstrap.isPresent());
        assertTrue(bootstrap.get().isLoopback());

        // Default transitions
        assertTrue(matrix.getTransition(BOOTSTRAPPING, "nextView").isPresent());
        assertTrue(matrix.getTransition(BOOTSTRAPPING, "rotateViewKeys").isPresent());
    }

    @Test
    public void testSynchronizingTransitions() {
        // SYNCHRONIZING has 4 valid transitions (2 explicit + 2 defaults: nextView, rotateViewKeys)
        assertEquals(4, countTransitionsFor(SYNCHRONIZING));

        var combine = matrix.getTransition(SYNCHRONIZING, "combine");
        assertTrue(combine.isPresent());
        assertTrue(combine.get().isLoopback());

        var synchd = matrix.getTransition(SYNCHRONIZING, "synchd");
        assertTrue(synchd.isPresent());
        assertEquals(OPERATIONAL, synchd.get().target());

        // Default transitions
        assertTrue(matrix.getTransition(SYNCHRONIZING, "nextView").isPresent());
        assertTrue(matrix.getTransition(SYNCHRONIZING, "rotateViewKeys").isPresent());
    }

    @Test
    public void testCheckpointingTransitions() {
        // CHECKPOINTING has 4 valid transitions (2 explicit + 2 defaults: nextView, rotateViewKeys)
        assertEquals(4, countTransitionsFor(CHECKPOINTING));

        var combine = matrix.getTransition(CHECKPOINTING, "combine");
        assertTrue(combine.isPresent());

        var finish = matrix.getTransition(CHECKPOINTING, "finishCheckpoint");
        assertTrue(finish.isPresent());
        assertTrue(finish.get().isLoopback());  // FSM pop, returns null

        // Default transitions
        assertTrue(matrix.getTransition(CHECKPOINTING, "nextView").isPresent());
        assertTrue(matrix.getTransition(CHECKPOINTING, "rotateViewKeys").isPresent());
    }

    @Test
    public void testRegeneratingTransitions() {
        // REGENERATING has 4 valid transitions
        assertEquals(4, countTransitionsFor(REGENERATING));

        var combine = matrix.getTransition(REGENERATING, "combine");
        assertTrue(combine.isPresent());

        var nextView = matrix.getTransition(REGENERATING, "nextView");
        assertTrue(nextView.isPresent());
        assertEquals(RECOVERING, nextView.get().target());

        var regenerated = matrix.getTransition(REGENERATING, "regenerated");
        assertTrue(regenerated.isPresent());
        assertEquals(OPERATIONAL, regenerated.get().target());

        var rotateKeys = matrix.getTransition(REGENERATING, "rotateViewKeys");
        assertTrue(rotateKeys.isPresent());
        assertEquals(OPERATIONAL, rotateKeys.get().target());
    }

    @Test
    public void testAwaitingRegenerationTransitions() {
        // AWAITING_REGENERATION has 4 valid transitions (2 explicit + 2 defaults: nextView, rotateViewKeys)
        assertEquals(4, countTransitionsFor(AWAITING_REGENERATION));

        var combine = matrix.getTransition(AWAITING_REGENERATION, "combine");
        assertTrue(combine.isPresent());
        assertTrue(combine.get().isLoopback());

        var syncFailed = matrix.getTransition(AWAITING_REGENERATION, "synchronizationFailed");
        assertTrue(syncFailed.isPresent());
        assertTrue(syncFailed.get().isLoopback());

        // Default transitions
        assertTrue(matrix.getTransition(AWAITING_REGENERATION, "nextView").isPresent());
        assertTrue(matrix.getTransition(AWAITING_REGENERATION, "rotateViewKeys").isPresent());
    }

    @Test
    public void testSingletonInstance() {
        // Verify singleton pattern
        var instance1 = StateTransitionMatrix.getInstance();
        var instance2 = StateTransitionMatrix.getInstance();
        assertSame(instance1, instance2);
    }

    /**
     * Count valid non-fail transitions for a given state.
     */
    private int countTransitionsFor(Combine.Mercantile state) {
        var transitions = new String[] {
            "start", "bootstrap", "combine", "synchronizing", "synchd",
            "beginCheckpoint", "finishCheckpoint", "regenerate", "regenerated",
            "rotateViewKeys", "nextView", "synchronizationFailed"
        };

        int count = 0;
        for (var transitionName : transitions) {
            if (matrix.getTransition(state, transitionName).isPresent()) {
                count++;
            }
        }
        return count;
    }
}
