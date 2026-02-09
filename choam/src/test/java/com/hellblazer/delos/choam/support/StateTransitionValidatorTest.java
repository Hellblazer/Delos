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
import static com.hellblazer.delos.choam.support.ValidationResult.ValidationType.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for StateTransitionValidator.
 * Verifies validation logic for invariants, preconditions, and postconditions.
 *
 * @author hal.hildebrand
 */
public class StateTransitionValidatorTest {

    private StateTransitionValidator validator;
    private SimpleMeterRegistry metrics;

    @BeforeEach
    public void setup() {
        var matrix = StateTransitionMatrix.getInstance();
        metrics = new SimpleMeterRegistry();
        validator = new StateTransitionValidator(matrix, metrics);
    }

    @Test
    public void testValidateInvariant_Success() {
        // Valid OPERATIONAL state snapshot
        var snapshot = new CHOAMStateSnapshot(
            true, false,
            true, "Standard",
            true, true, 100,
            true, 95, 2,
            5, false, false,
            "OPERATIONAL"
        );

        var result = validator.validateInvariant(snapshot);

        assertTrue(result.valid());
        assertEquals(INVARIANT, result.validationType());
        assertEquals(OPERATIONAL, result.state());
        assertTrue(result.violations().isEmpty());

        // Verify metrics
        assertEquals(0, metrics.counter("validation.violations").count());
        assertEquals(0, metrics.counter("validation.invariant.violations").count());
    }

    @Test
    public void testValidateInvariant_Failure() {
        // Invalid OPERATIONAL state: missing view
        var snapshot = new CHOAMStateSnapshot(
            true, false,
            true, "Standard",
            true, true, 100,
            false, -1, 0,  // no view - VIOLATES INVARIANT
            0, false, false,
            "OPERATIONAL"
        );

        var result = validator.validateInvariant(snapshot);

        assertFalse(result.valid());
        assertEquals(INVARIANT, result.validationType());
        assertEquals(OPERATIONAL, result.state());
        assertEquals(1, result.violations().size());
        assertTrue(result.violations().get(0).contains("invariant"));

        // Verify metrics
        assertEquals(1, metrics.counter("validation.violations").count());
        assertEquals(1, metrics.counter("validation.invariant.violations").count());
    }

    @Test
    public void testValidatePrecondition_Success() {
        // Valid snapshot for INITIAL→start transition
        var snapshot = new CHOAMStateSnapshot(
            false, false,  // not started - valid precondition
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "INITIAL"
        );

        var result = validator.validatePrecondition(INITIAL, "start", snapshot);

        assertTrue(result.valid());
        assertEquals(PRECONDITION, result.validationType());
        assertEquals(INITIAL, result.state());
        assertEquals("start", result.transitionName());
        assertTrue(result.violations().isEmpty());

        // Verify metrics
        assertEquals(0, metrics.counter("validation.violations").count());
        assertEquals(0, metrics.counter("validation.precondition.violations").count());
    }

    @Test
    public void testValidatePrecondition_Failure() {
        // Invalid snapshot for INITIAL→start: already started
        var snapshot = new CHOAMStateSnapshot(
            true, false,  // already started - VIOLATES PRECONDITION
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "INITIAL"
        );

        var result = validator.validatePrecondition(INITIAL, "start", snapshot);

        assertFalse(result.valid());
        assertEquals(PRECONDITION, result.validationType());
        assertEquals(INITIAL, result.state());
        assertEquals("start", result.transitionName());
        assertEquals(1, result.violations().size());
        assertTrue(result.violations().get(0).contains("Precondition violated"));

        // Verify metrics
        assertEquals(1, metrics.counter("validation.violations").count());
        assertEquals(1, metrics.counter("validation.precondition.violations").count());
    }

    @Test
    public void testValidatePrecondition_TransitionNotFound() {
        var snapshot = new CHOAMStateSnapshot(
            false, false,
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "INITIAL"
        );

        // Non-existent transition
        var result = validator.validatePrecondition(INITIAL, "nonexistentTransition", snapshot);

        assertFalse(result.valid());
        assertEquals(PRECONDITION, result.validationType());
        assertTrue(result.violations().get(0).contains("not found in matrix"));

        // Verify metrics
        assertEquals(1, metrics.counter("validation.violations").count());
        assertEquals(1, metrics.counter("validation.precondition.violations").count());
    }

    @Test
    public void testValidatePostcondition_Success() {
        // Valid INITIAL→start transition
        var preSnapshot = new CHOAMStateSnapshot(
            false, false,  // not started before
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "INITIAL"
        );

        var postSnapshot = new CHOAMStateSnapshot(
            true, false,  // started after - valid postcondition
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "RECOVERING"
        );

        var result = validator.validatePostcondition(INITIAL, "start", preSnapshot, postSnapshot);

        assertTrue(result.valid());
        assertEquals(POSTCONDITION, result.validationType());
        assertEquals(INITIAL, result.state());
        assertEquals("start", result.transitionName());
        assertTrue(result.violations().isEmpty());

        // Verify metrics
        assertEquals(0, metrics.counter("validation.violations").count());
        assertEquals(0, metrics.counter("validation.postcondition.violations").count());
    }

    @Test
    public void testValidatePostcondition_Failure() {
        // Invalid INITIAL→start transition: state not changed
        var preSnapshot = new CHOAMStateSnapshot(
            false, false,
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "INITIAL"
        );

        var postSnapshot = new CHOAMStateSnapshot(
            false, false,  // still not started - VIOLATES POSTCONDITION
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "RECOVERING"
        );

        var result = validator.validatePostcondition(INITIAL, "start", preSnapshot, postSnapshot);

        assertFalse(result.valid());
        assertEquals(POSTCONDITION, result.validationType());
        assertEquals(INITIAL, result.state());
        assertEquals("start", result.transitionName());
        assertEquals(1, result.violations().size());
        assertTrue(result.violations().get(0).contains("Postcondition violated"));

        // Verify metrics
        assertEquals(1, metrics.counter("validation.violations").count());
        assertEquals(1, metrics.counter("validation.postcondition.violations").count());
    }

    @Test
    public void testValidatePostcondition_LoopbackTransition() {
        // OPERATIONAL→combine is loopback (returns null, stays in OPERATIONAL)
        var preSnapshot = new CHOAMStateSnapshot(
            true, false,
            true, "Standard",
            true, true, 100,
            true, 95, 2,
            5, false, false,
            "OPERATIONAL"
        );

        var postSnapshot = new CHOAMStateSnapshot(
            true, false,
            true, "Standard",
            true, true, 100,
            true, 95, 2,
            5, false, false,
            "OPERATIONAL"  // Still OPERATIONAL - valid for loopback
        );

        var result = validator.validatePostcondition(OPERATIONAL, "combine", preSnapshot, postSnapshot);

        assertTrue(result.valid());
        assertEquals(POSTCONDITION, result.validationType());
    }

    @Test
    public void testEntryActionValidation_Placeholder() {
        // Entry action validation not yet implemented - should always succeed
        var snapshot = new CHOAMStateSnapshot(
            true, false,
            true, "Standard",
            true, true, 100,
            true, 95, 2,
            5, false, false,
            "OPERATIONAL"
        );

        var result = validator.validateEntryAction(OPERATIONAL, snapshot);

        assertTrue(result.valid());
        assertEquals(ENTRY_ACTION, result.validationType());
    }

    @Test
    public void testExitActionValidation_Placeholder() {
        // Exit action validation not yet implemented - should always succeed
        var snapshot = new CHOAMStateSnapshot(
            true, false,
            true, "Standard",
            true, true, 100,
            true, 95, 2,
            5, false, false,
            "OPERATIONAL"
        );

        var result = validator.validateExitAction(OPERATIONAL, snapshot);

        assertTrue(result.valid());
        assertEquals(EXIT_ACTION, result.validationType());
    }

    @Test
    public void testMetricsSnapshot() {
        // Generate some validation activity
        var validSnapshot = new CHOAMStateSnapshot(
            true, false,
            true, "Standard",
            true, true, 100,
            true, 95, 2,
            5, false, false,
            "OPERATIONAL"
        );

        var invalidSnapshot = new CHOAMStateSnapshot(
            true, false,
            true, "Standard",
            true, true, 100,
            false, -1, 0,  // invalid - no view
            0, false, false,
            "OPERATIONAL"
        );

        // Run validations
        validator.validateInvariant(validSnapshot);
        validator.validateInvariant(invalidSnapshot);

        var snapshot = validator.getMetricsSnapshot();

        // Should have recorded metrics
        assertEquals(2, snapshot.invariantCount());
        assertEquals(1, snapshot.totalViolationCount());
        assertEquals(1, snapshot.invariantViolationCount());
        assertEquals(0, snapshot.preconditionViolationCount());
        assertEquals(0, snapshot.postconditionViolationCount());

        // Check SLA
        assertTrue(snapshot.isWithinSLA(), "Should be within 244μs SLA");

        // Check violation rate
        assertEquals(50.0, snapshot.getViolationRate(), 0.01);  // 1 violation out of 2 checks = 50%
    }

    @Test
    public void testNullArgumentHandling() {
        assertThrows(IllegalArgumentException.class,
                     () -> validator.validateInvariant(null));

        assertThrows(IllegalArgumentException.class,
                     () -> validator.validatePrecondition(null, "start", new CHOAMStateSnapshot(
                         false, false, false, null, false, false, -1, false, -1, 0, 0, false, false, "INITIAL")));

        assertThrows(IllegalArgumentException.class,
                     () -> validator.validatePrecondition(INITIAL, null, new CHOAMStateSnapshot(
                         false, false, false, null, false, false, -1, false, -1, 0, 0, false, false, "INITIAL")));

        assertThrows(IllegalArgumentException.class,
                     () -> validator.validatePrecondition(INITIAL, "start", null));
    }

    @Test
    public void testParseStateWithInvalidName() {
        // Snapshot with invalid state name
        var snapshot = new CHOAMStateSnapshot(
            false, false,
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "INVALID_STATE"  // Not a valid Mercantile state
        );

        // Should throw when trying to parse invalid state
        assertThrows(IllegalArgumentException.class,
                     () -> validator.validateInvariant(snapshot));
    }

    @Test
    public void testMetricsRegistration() {
        // Verify all metrics were registered
        assertNotNull(metrics.find("validation.latency").summary());
        assertNotNull(metrics.find("validation.precondition.timer").timer());
        assertNotNull(metrics.find("validation.postcondition.timer").timer());
        assertNotNull(metrics.find("validation.invariant.timer").timer());
        assertNotNull(metrics.find("validation.violations").counter());
        assertNotNull(metrics.find("validation.precondition.violations").counter());
        assertNotNull(metrics.find("validation.postcondition.violations").counter());
        assertNotNull(metrics.find("validation.invariant.violations").counter());
    }
}
