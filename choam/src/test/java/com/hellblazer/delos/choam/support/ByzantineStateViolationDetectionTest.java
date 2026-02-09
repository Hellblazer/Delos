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
import static com.hellblazer.delos.choam.support.ByzantineViolation.Severity.*;
import static com.hellblazer.delos.choam.support.ByzantineViolationType.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Byzantine violation detection through state machine validation.
 * <p>
 * Tests that {@link StateTransitionValidator} failures are correctly mapped to
 * {@link ByzantineViolation} instances via {@link ByzantineDetectionMapper}.
 * </p>
 * <p>
 * Covers all Byzantine violation types with realistic CHOAM state scenarios.
 * </p>
 *
 * @author hal.hildebrand
 */
public class ByzantineStateViolationDetectionTest {

    private StateTransitionValidator validator;
    private ByzantineDetectionMapper mapper;
    private SimpleMeterRegistry metrics;

    @BeforeEach
    public void setup() {
        var matrix = StateTransitionMatrix.getInstance();
        metrics = new SimpleMeterRegistry();
        validator = new StateTransitionValidator(matrix, metrics);
        mapper = new ByzantineDetectionMapper();
    }

    /**
     * Test: STATE_INVARIANT_VIOLATION detected for OPERATIONAL missing view
     */
    @Test
    public void testStateInvariantViolationDetection() {
        // OPERATIONAL requires: started && hasGenesis && hasCommittee && hasView
        // Create invalid OPERATIONAL snapshot (missing view)
        var invalidSnapshot = new CHOAMStateSnapshot(
            true, false,
            true, "Standard",
            true, true, 100,
            false, -1, 0,  // INVALID: no view
            0, false, false,
            "OPERATIONAL"
        );

        var result = validator.validateInvariant(invalidSnapshot);
        assertFalse(result.valid());

        var violation = mapper.mapViolation(result);
        assertNotNull(violation);
        assertEquals(STATE_INVARIANT_VIOLATION, violation.type());
        assertEquals(CRITICAL, violation.severity());  // OPERATIONAL violations are critical
        assertTrue(violation.isCritical());
        assertEquals("OPERATIONAL", violation.state());
    }

    /**
     * Test: PRECONDITION_VIOLATION detected for start when already started
     */
    @Test
    public void testPreconditionViolationDetection() {
        // INITIAL→start requires: !started
        // Create invalid snapshot (already started)
        var invalidSnapshot = new CHOAMStateSnapshot(
            true, false,  // INVALID: already started
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "INITIAL"
        );

        var result = validator.validatePrecondition(INITIAL, "start", invalidSnapshot);
        assertFalse(result.valid());

        var violation = mapper.mapViolation(result);
        assertNotNull(violation);
        assertEquals(PRECONDITION_VIOLATION, violation.type());
        assertEquals(HIGH, violation.severity());
        assertEquals("start", violation.transition());
        assertTrue(violation.remediation().contains("Reject"));
    }

    /**
     * Test: POSTCONDITION_VIOLATION detected for bootstrap without committee
     */
    @Test
    public void testPostconditionViolationDetection() {
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
        assertFalse(result.valid());

        var violation = mapper.mapViolation(result);
        assertNotNull(violation);
        assertEquals(POSTCONDITION_VIOLATION, violation.type());
        assertEquals(HIGH, violation.severity());
        assertEquals("bootstrap", violation.transition());
        assertTrue(violation.remediation().contains("Rollback"));
    }

    /**
     * Test: Multiple violations of same type accumulate
     */
    @Test
    public void testMultipleViolationsAccumulate() {
        // Inject 3 invariant violations
        for (int i = 0; i < 3; i++) {
            var invalidSnapshot = new CHOAMStateSnapshot(
                true, false,
                true, "Standard",
                false, false, -1,  // No genesis
                true, 50, 0,
                0, false, false,
                "OPERATIONAL"
            );
            var result = validator.validateInvariant(invalidSnapshot);
            mapper.mapViolation(result);
        }

        assertEquals(3, mapper.getViolationCount(STATE_INVARIANT_VIOLATION));
        assertEquals(3, mapper.getTotalViolationCount());
        assertEquals(3, mapper.getRecentViolations().size());
    }

    /**
     * Test: Mixed violation types are tracked separately
     */
    @Test
    public void testMixedViolationTypes() {
        // Invariant violation
        var invalidInvariant = new CHOAMStateSnapshot(
            true, false,
            true, "Standard",
            false, false, -1,  // No genesis
            true, 50, 0,
            0, false, false,
            "OPERATIONAL"
        );
        var result1 = validator.validateInvariant(invalidInvariant);
        mapper.mapViolation(result1);

        // Precondition violation
        var invalidPrecondition = new CHOAMStateSnapshot(
            true, false,  // Already started
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "INITIAL"
        );
        var result2 = validator.validatePrecondition(INITIAL, "start", invalidPrecondition);
        mapper.mapViolation(result2);

        assertEquals(1, mapper.getViolationCount(STATE_INVARIANT_VIOLATION));
        assertEquals(1, mapper.getViolationCount(PRECONDITION_VIOLATION));
        assertEquals(2, mapper.getTotalViolationCount());

        var recent = mapper.getRecentViolations();
        assertEquals(2, recent.size());
        assertTrue(recent.stream().anyMatch(v -> v.type() == STATE_INVARIANT_VIOLATION));
        assertTrue(recent.stream().anyMatch(v -> v.type() == PRECONDITION_VIOLATION));
    }

    /**
     * Test: BOOTSTRAPPING with wrong committee type detected
     */
    @Test
    public void testBootstrappingWrongCommitteeType() {
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
        assertFalse(result.valid());

        var violation = mapper.mapViolation(result);
        assertNotNull(violation);
        assertEquals(STATE_INVARIANT_VIOLATION, violation.type());
        assertEquals(HIGH, violation.severity());  // BOOTSTRAPPING not as critical as OPERATIONAL
    }

    /**
     * Test: CHECKPOINTING missing head detected
     */
    @Test
    public void testCheckpointingMissingHead() {
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
        assertFalse(result.valid());

        var violation = mapper.mapViolation(result);
        assertNotNull(violation);
        assertEquals(STATE_INVARIANT_VIOLATION, violation.type());
        assertEquals("CHECKPOINTING", violation.state());
    }

    /**
     * Test: SYNCHRONIZING without sync scheduled detected
     */
    @Test
    public void testSynchronizingNotScheduled() {
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
        assertFalse(result.valid());

        var violation = mapper.mapViolation(result);
        assertNotNull(violation);
        assertEquals(STATE_INVARIANT_VIOLATION, violation.type());
    }

    /**
     * Test: Non-existent transition detected
     */
    @Test
    public void testNonExistentTransition() {
        var snapshot = new CHOAMStateSnapshot(
            false, false,
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "INITIAL"
        );

        var result = validator.validatePrecondition(INITIAL, "nonexistentTransition", snapshot);
        assertFalse(result.valid());

        var violation = mapper.mapViolation(result);
        assertNotNull(violation);
        assertEquals(PRECONDITION_VIOLATION, violation.type());
        assertTrue(violation.violations().get(0).contains("not found in matrix"));
    }

    /**
     * Test: Violation context includes state and transition
     */
    @Test
    public void testViolationContextComplete() {
        var invalidSnapshot = new CHOAMStateSnapshot(
            true, false,  // Already started
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "INITIAL"
        );

        var result = validator.validatePrecondition(INITIAL, "start", invalidSnapshot);
        var violation = mapper.mapViolation(result);

        assertNotNull(violation);
        assertNotNull(violation.context());
        assertTrue(violation.context().contains("INITIAL"));
        assertTrue(violation.context().contains("start"));
        assertTrue(violation.context().contains("Violations"));
    }

    /**
     * Test: Violation timestamp is recent
     */
    @Test
    public void testViolationTimestampRecent() {
        var invalidSnapshot = new CHOAMStateSnapshot(
            true, false,
            true, "Standard",
            false, false, -1,  // No genesis
            true, 50, 0,
            0, false, false,
            "OPERATIONAL"
        );

        var before = System.currentTimeMillis();
        var result = validator.validateInvariant(invalidSnapshot);
        var after = System.currentTimeMillis();

        var violation = mapper.mapViolation(result);
        assertNotNull(violation);

        var timestamp = violation.timestamp().toEpochMilli();
        assertTrue(timestamp >= before && timestamp <= after,
                   "Violation timestamp should be within test execution window");
    }

    /**
     * Test: Recent violations list maintains order
     */
    @Test
    public void testRecentViolationsOrdering() {
        // Create violations with slight time gaps
        for (int i = 0; i < 5; i++) {
            var invalidSnapshot = new CHOAMStateSnapshot(
                true, false,
                true, "Standard",
                false, false, -1,  // No genesis
                true, 50, 0,
                0, false, false,
                "OPERATIONAL"
            );
            var result = validator.validateInvariant(invalidSnapshot);
            mapper.mapViolation(result);

            try {
                Thread.sleep(1);  // Small delay to ensure distinct timestamps
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        var recent = mapper.getRecentViolations();
        assertEquals(5, recent.size());

        // Verify chronological ordering (oldest first)
        for (int i = 0; i < recent.size() - 1; i++) {
            assertTrue(
                recent.get(i).timestamp().isBefore(recent.get(i + 1).timestamp()) ||
                recent.get(i).timestamp().equals(recent.get(i + 1).timestamp()),
                "Violations should be in chronological order"
            );
        }
    }
}
