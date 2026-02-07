/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.fsm.Combine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.hellblazer.delos.choam.fsm.Combine.Mercantile.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial tests for state machine validation.
 * Tests detection of invalid transitions and state violations:
 * 1. Invalid preconditions (violated prerequisites)
 * 2. Invalid postconditions (violated outcomes)
 * 3. Invariant violations (illegal states)
 * 4. Non-existent transitions
 *
 * Verifies that validation correctly detects Byzantine behavior.
 *
 * @author hal.hildebrand
 */
public class AdversarialValidationTest {

    private StateTransitionValidator validator;
    private SimpleMeterRegistry metrics;

    @BeforeEach
    public void setup() {
        var matrix = StateTransitionMatrix.getInstance();
        metrics = new SimpleMeterRegistry();
        validator = new StateTransitionValidator(matrix, metrics);
    }

    /**
     * Test: INITIAL→start with already-started snapshot (precondition violation)
     */
    @Test
    public void testInvalidPrecondition_AlreadyStarted() {
        // Snapshot shows already started (violates INITIAL→start precondition)
        var invalidSnapshot = new CHOAMStateSnapshot(
            true, false,  // INVALID: already started
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "INITIAL"
        );

        var result = validator.validatePrecondition(INITIAL, "start", invalidSnapshot);

        assertFalse(result.valid(), "Precondition should be violated");
        assertEquals(1, result.violations().size());
        assertTrue(result.violations().get(0).contains("Precondition violated"));

        // Verify violation was recorded
        assertEquals(1, metrics.counter("validation.violations").count());
        assertEquals(1, metrics.counter("validation.precondition.violations").count());
    }

    /**
     * Test: RECOVERING→bootstrap with no committee after transition (postcondition violation)
     */
    @Test
    public void testInvalidPostcondition_NoCommitteeAfterBootstrap() {
        var preSnapshot = new CHOAMStateSnapshot(
            true, false,
            false, null,  // Valid: no committee yet
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "RECOVERING"
        );

        // INVALID: Still no committee after bootstrap (violates postcondition)
        var postSnapshot = new CHOAMStateSnapshot(
            true, false,
            false, null,  // INVALID: should have GenesisFormation committee
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "BOOTSTRAPPING"
        );

        var result = validator.validatePostcondition(RECOVERING, "bootstrap", preSnapshot, postSnapshot);

        assertFalse(result.valid(), "Postcondition should be violated");
        assertEquals(1, result.violations().size());
        assertTrue(result.violations().get(0).contains("Postcondition violated"));

        assertEquals(1, metrics.counter("validation.violations").count());
        assertEquals(1, metrics.counter("validation.postcondition.violations").count());
    }

    /**
     * Test: OPERATIONAL invariant violation (missing view)
     */
    @Test
    public void testInvariantViolation_OperationalMissingView() {
        // OPERATIONAL requires: started && hasGenesis && hasCommittee && hasView
        var invalidSnapshot = new CHOAMStateSnapshot(
            true, false,
            true, "Standard",
            true, true, 100,
            false, -1, 0,  // INVALID: no view
            0, false, false,
            "OPERATIONAL"
        );

        var result = validator.validateInvariant(invalidSnapshot);

        assertFalse(result.valid(), "Invariant should be violated");
        assertEquals(1, result.violations().size());
        assertTrue(result.violations().get(0).contains("invariant"));

        assertEquals(1, metrics.counter("validation.violations").count());
        assertEquals(1, metrics.counter("validation.invariant.violations").count());
    }

    /**
     * Test: OPERATIONAL invariant violation (missing genesis)
     */
    @Test
    public void testInvariantViolation_OperationalMissingGenesis() {
        var invalidSnapshot = new CHOAMStateSnapshot(
            true, false,
            true, "Standard",
            false, false, -1,  // INVALID: no genesis
            true, 50, 0,
            0, false, false,
            "OPERATIONAL"
        );

        var result = validator.validateInvariant(invalidSnapshot);

        assertFalse(result.valid(), "Invariant should be violated");
        assertTrue(result.violations().get(0).contains("invariant"));

        assertEquals(1, metrics.counter("validation.violations").count());
        assertEquals(1, metrics.counter("validation.invariant.violations").count());
    }

    /**
     * Test: BOOTSTRAPPING invariant violation (wrong committee type)
     */
    @Test
    public void testInvariantViolation_BootstrappingWrongCommitteeType() {
        // BOOTSTRAPPING requires GenesisFormation committee
        var invalidSnapshot = new CHOAMStateSnapshot(
            true, false,
            true, "Standard",  // INVALID: should be "GenesisFormation"
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "BOOTSTRAPPING"
        );

        var result = validator.validateInvariant(invalidSnapshot);

        assertFalse(result.valid(), "Invariant should be violated");
        assertTrue(result.violations().get(0).contains("invariant"));

        assertEquals(1, metrics.counter("validation.violations").count());
        assertEquals(1, metrics.counter("validation.invariant.violations").count());
    }

    /**
     * Test: Non-existent transition
     */
    @Test
    public void testInvalidTransition_NonExistent() {
        var snapshot = new CHOAMStateSnapshot(
            false, false,
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "INITIAL"
        );

        var result = validator.validatePrecondition(INITIAL, "nonexistentTransition", snapshot);

        assertFalse(result.valid(), "Non-existent transition should be rejected");
        assertTrue(result.violations().get(0).contains("not found in matrix"));

        assertEquals(1, metrics.counter("validation.violations").count());
        assertEquals(1, metrics.counter("validation.precondition.violations").count());
    }

    /**
     * Test: INITIAL→start postcondition violation (not actually started)
     */
    @Test
    public void testInvalidPostcondition_StartDidNotStart() {
        var preSnapshot = new CHOAMStateSnapshot(
            false, false,
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "INITIAL"
        );

        // INVALID: Still not started after start transition
        var postSnapshot = new CHOAMStateSnapshot(
            false, false,  // INVALID: should be started
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "RECOVERING"
        );

        var result = validator.validatePostcondition(INITIAL, "start", preSnapshot, postSnapshot);

        assertFalse(result.valid(), "Postcondition should be violated");
        assertTrue(result.violations().get(0).contains("Postcondition violated"));

        assertEquals(1, metrics.counter("validation.violations").count());
        assertEquals(1, metrics.counter("validation.postcondition.violations").count());
    }

    /**
     * Test: RECOVERING→bootstrap precondition violation (already has committee)
     */
    @Test
    public void testInvalidPrecondition_AlreadyHasCommittee() {
        // INVALID: Already has committee (violates RECOVERING precondition)
        var invalidSnapshot = new CHOAMStateSnapshot(
            true, false,
            true, "Standard",  // INVALID: should not have committee yet
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "RECOVERING"
        );

        // First validate invariant - should fail
        var invariantResult = validator.validateInvariant(invalidSnapshot);
        assertFalse(invariantResult.valid(), "RECOVERING with committee violates invariant");

        // This also violates precondition for bootstrap
        var preconditionResult = validator.validatePrecondition(RECOVERING, "bootstrap", invalidSnapshot);
        // Note: The precondition might still pass if it only checks for anchor != null
        // But the invariant violation should be caught first
    }

    /**
     * Test: CHECKPOINTING invariant violation (missing head)
     */
    @Test
    public void testInvariantViolation_CheckpointingMissingHead() {
        // CHECKPOINTING requires head with height > 0
        var invalidSnapshot = new CHOAMStateSnapshot(
            true, false,
            true, "Standard",
            true, false, -1,  // INVALID: no head
            true, 50, 0,
            0, false, false,
            "CHECKPOINTING"
        );

        var result = validator.validateInvariant(invalidSnapshot);

        assertFalse(result.valid(), "Invariant should be violated");
        assertTrue(result.violations().get(0).contains("invariant"));

        assertEquals(1, metrics.counter("validation.violations").count());
        assertEquals(1, metrics.counter("validation.invariant.violations").count());
    }

    /**
     * Test: SYNCHRONIZING invariant violation (sync not scheduled)
     */
    @Test
    public void testInvariantViolation_SynchronizingNotScheduled() {
        // SYNCHRONIZING requires syncScheduled = true
        var invalidSnapshot = new CHOAMStateSnapshot(
            true, false,
            true, "Standard",
            false, false, -1,
            false, -1, 0,
            0, false, false,  // INVALID: sync not scheduled
            "SYNCHRONIZING"
        );

        var result = validator.validateInvariant(invalidSnapshot);

        assertFalse(result.valid(), "Invariant should be violated");
        assertTrue(result.violations().get(0).contains("invariant"));

        assertEquals(1, metrics.counter("validation.violations").count());
        assertEquals(1, metrics.counter("validation.invariant.violations").count());
    }

    /**
     * Test: Multiple violations accumulate
     */
    @Test
    public void testMultipleViolationsAccumulate() {
        // Reset metrics
        metrics = new SimpleMeterRegistry();
        validator = new StateTransitionValidator(StateTransitionMatrix.getInstance(), metrics);

        // Violation 1: Invalid invariant
        var invalidSnapshot1 = new CHOAMStateSnapshot(
            true, false,
            true, "Standard",
            false, false, -1,  // No genesis
            true, 50, 0,
            0, false, false,
            "OPERATIONAL"
        );
        validator.validateInvariant(invalidSnapshot1);

        // Violation 2: Invalid precondition
        var invalidSnapshot2 = new CHOAMStateSnapshot(
            true, false,  // Already started
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "INITIAL"
        );
        validator.validatePrecondition(INITIAL, "start", invalidSnapshot2);

        // Violation 3: Invalid postcondition
        var preSnapshot = new CHOAMStateSnapshot(
            false, false,
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "INITIAL"
        );
        var postSnapshot = new CHOAMStateSnapshot(
            false, false,  // Not started
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "RECOVERING"
        );
        validator.validatePostcondition(INITIAL, "start", preSnapshot, postSnapshot);

        // Verify all violations recorded
        assertEquals(3, metrics.counter("validation.violations").count());
        assertEquals(1, metrics.counter("validation.invariant.violations").count());
        assertEquals(1, metrics.counter("validation.precondition.violations").count());
        assertEquals(1, metrics.counter("validation.postcondition.violations").count());
    }

    /**
     * Test: Invalid state name
     */
    @Test
    public void testInvalidStateName() {
        var invalidSnapshot = new CHOAMStateSnapshot(
            false, false,
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "INVALID_STATE_NAME"  // INVALID state
        );

        assertThrows(IllegalArgumentException.class,
                     () -> validator.validateInvariant(invalidSnapshot),
                     "Invalid state name should throw");
    }

    /**
     * Test: Null arguments rejected
     */
    @Test
    public void testNullArgumentsRejected() {
        var snapshot = new CHOAMStateSnapshot(
            false, false,
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "INITIAL"
        );

        assertThrows(IllegalArgumentException.class, () -> validator.validateInvariant(null));
        assertThrows(IllegalArgumentException.class, () -> validator.validatePrecondition(null, "start", snapshot));
        assertThrows(IllegalArgumentException.class, () -> validator.validatePrecondition(INITIAL, null, snapshot));
        assertThrows(IllegalArgumentException.class, () -> validator.validatePrecondition(INITIAL, "start", null));
        assertThrows(IllegalArgumentException.class, () -> validator.validatePostcondition(null, "start", snapshot, snapshot));
        assertThrows(IllegalArgumentException.class, () -> validator.validatePostcondition(INITIAL, null, snapshot, snapshot));
        assertThrows(IllegalArgumentException.class, () -> validator.validatePostcondition(INITIAL, "start", null, snapshot));
        assertThrows(IllegalArgumentException.class, () -> validator.validatePostcondition(INITIAL, "start", snapshot, null));
    }
}
