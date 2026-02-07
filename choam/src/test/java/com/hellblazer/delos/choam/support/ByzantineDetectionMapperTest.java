/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.choam.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static com.hellblazer.delos.choam.fsm.Combine.Mercantile.*;
import static com.hellblazer.delos.choam.support.ByzantineViolation.Severity.*;
import static com.hellblazer.delos.choam.support.ByzantineViolationType.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ByzantineDetectionMapper}.
 * <p>
 * Verifies that validation failures are correctly classified as Byzantine
 * violations with appropriate severity levels and remediation recommendations.
 * </p>
 *
 * @author hal.hildebrand
 */
public class ByzantineDetectionMapperTest {

    private ByzantineDetectionMapper mapper;

    @BeforeEach
    public void setup() {
        mapper = new ByzantineDetectionMapper();
    }

    /**
     * Test: Valid validation result produces no violation
     */
    @Test
    public void testValidResultProducesNoViolation() {
        var validResult = new ValidationResult(
            true,
            List.of(),
            OPERATIONAL,
            "combine",
            Instant.now(),
            ValidationResult.ValidationType.POSTCONDITION
        );

        var violation = mapper.mapViolation(validResult);

        assertNull(violation, "Valid result should not produce a violation");
        assertEquals(0, mapper.getTotalViolationCount());
    }

    /**
     * Test: Invariant violation is classified correctly
     */
    @Test
    public void testInvariantViolationClassification() {
        var result = new ValidationResult(
            false,
            List.of("OPERATIONAL state invariant violated: missing view"),
            OPERATIONAL,
            null,
            Instant.now(),
            ValidationResult.ValidationType.INVARIANT
        );

        var violation = mapper.mapViolation(result);

        assertNotNull(violation);
        assertEquals(STATE_INVARIANT_VIOLATION, violation.type());
        assertEquals(CRITICAL, violation.severity());  // OPERATIONAL is critical
        assertTrue(violation.isCritical());
        assertEquals(1, mapper.getViolationCount(STATE_INVARIANT_VIOLATION));
    }

    /**
     * Test: Precondition violation is classified correctly
     */
    @Test
    public void testPreconditionViolationClassification() {
        var result = new ValidationResult(
            false,
            List.of("Precondition violated: already started"),
            INITIAL,
            "start",
            Instant.now(),
            ValidationResult.ValidationType.PRECONDITION
        );

        var violation = mapper.mapViolation(result);

        assertNotNull(violation);
        assertEquals(PRECONDITION_VIOLATION, violation.type());
        assertEquals(HIGH, violation.severity());
        assertFalse(violation.isCritical());
        assertEquals("INITIAL", violation.state());
        assertEquals("start", violation.transition());
    }

    /**
     * Test: Postcondition violation is classified correctly
     */
    @Test
    public void testPostconditionViolationClassification() {
        var result = new ValidationResult(
            false,
            List.of("Postcondition violated: committee not created after bootstrap"),
            BOOTSTRAPPING,
            "bootstrap",
            Instant.now(),
            ValidationResult.ValidationType.POSTCONDITION
        );

        var violation = mapper.mapViolation(result);

        assertNotNull(violation);
        assertEquals(POSTCONDITION_VIOLATION, violation.type());
        assertEquals(HIGH, violation.severity());
        assertTrue(violation.remediation().contains("Rollback"));
    }

    /**
     * Test: State inconsistency is classified correctly
     */
    @Test
    public void testStateInconsistencyClassification() {
        var result = new ValidationResult(
            false,
            List.of("State consistency violation: hasGenesis=true but hasHead=false"),
            RECOVERING,
            null,
            Instant.now(),
            ValidationResult.ValidationType.INVARIANT
        );

        var violation = mapper.mapViolation(result);

        assertNotNull(violation);
        assertEquals(STATE_INCONSISTENCY, violation.type());
        assertEquals(MEDIUM, violation.severity());  // First occurrence
    }

    /**
     * Test: Equivocation is classified as critical
     */
    @Test
    public void testEquivocationClassification() {
        var result = new ValidationResult(
            false,
            List.of("Equivocation detected: conflicting post-states for same transition"),
            OPERATIONAL,
            "combine",
            Instant.now(),
            ValidationResult.ValidationType.POSTCONDITION
        );

        var violation = mapper.mapViolation(result);

        assertNotNull(violation);
        assertEquals(EQUIVOCATION, violation.type());
        assertEquals(CRITICAL, violation.severity());
        assertTrue(violation.remediation().contains("FAIL node"));
    }

    /**
     * Test: Timing anomaly is classified correctly
     */
    @Test
    public void testTimingAnomalyClassification() {
        var result = new ValidationResult(
            false,
            List.of("Timing anomaly: transition velocity exceeds protocol limits"),
            OPERATIONAL,
            "nextView",
            Instant.now(),
            ValidationResult.ValidationType.PRECONDITION
        );

        var violation = mapper.mapViolation(result);

        assertNotNull(violation);
        assertEquals(TIMING_ANOMALY, violation.type());
        assertEquals(MEDIUM, violation.severity());
        assertTrue(violation.remediation().contains("Rate limit"));
    }

    /**
     * Test: Unknown violations are classified as low severity
     */
    @Test
    public void testUnknownViolationClassification() {
        var result = new ValidationResult(
            false,
            List.of("Unexpected validation failure: unknown cause"),
            RECOVERING,
            null,
            Instant.now(),
            ValidationResult.ValidationType.INVARIANT
        );

        var violation = mapper.mapViolation(result);

        assertNotNull(violation);
        assertEquals(UNKNOWN, violation.type());
        assertEquals(LOW, violation.severity());
    }

    /**
     * Test: Violation counts accumulate correctly
     */
    @Test
    public void testViolationCountAccumulation() {
        // Create multiple violations of different types
        for (int i = 0; i < 3; i++) {
            mapper.mapViolation(createInvariantViolation());
        }
        for (int i = 0; i < 2; i++) {
            mapper.mapViolation(createPreconditionViolation());
        }

        assertEquals(3, mapper.getViolationCount(STATE_INVARIANT_VIOLATION));
        assertEquals(2, mapper.getViolationCount(PRECONDITION_VIOLATION));
        assertEquals(5, mapper.getTotalViolationCount());
    }

    /**
     * Test: Recent violations are tracked (FIFO)
     */
    @Test
    public void testRecentViolationsTracking() {
        // Add 5 violations
        for (int i = 0; i < 5; i++) {
            mapper.mapViolation(createInvariantViolation());
        }

        var recent = mapper.getRecentViolations();
        assertEquals(5, recent.size());

        // Verify FIFO ordering (oldest first)
        assertTrue(recent.get(0).timestamp().isBefore(recent.get(4).timestamp()) ||
                   recent.get(0).timestamp().equals(recent.get(4).timestamp()));
    }

    /**
     * Test: Recent violations bounded to MAX_RECENT_VIOLATIONS
     */
    @Test
    public void testRecentViolationsBounded() {
        // Add 150 violations (exceeds MAX_RECENT_VIOLATIONS = 100)
        for (int i = 0; i < 150; i++) {
            mapper.mapViolation(createInvariantViolation());
        }

        var recent = mapper.getRecentViolations();
        assertEquals(100, recent.size(), "Recent violations should be bounded to 100");

        // Verify oldest were dropped
        assertEquals(150, mapper.getTotalViolationCount());
    }

    /**
     * Test: Recent violations filtered by type
     */
    @Test
    public void testRecentViolationsByType() {
        // Add mixed violations
        mapper.mapViolation(createInvariantViolation());
        mapper.mapViolation(createPreconditionViolation());
        mapper.mapViolation(createInvariantViolation());
        mapper.mapViolation(createPostconditionViolation());

        var invariantViolations = mapper.getRecentViolations(STATE_INVARIANT_VIOLATION);
        assertEquals(2, invariantViolations.size());

        var preconditionViolations = mapper.getRecentViolations(PRECONDITION_VIOLATION);
        assertEquals(1, preconditionViolations.size());
    }

    /**
     * Test: Severity escalation for repeated state inconsistencies
     */
    @Test
    public void testSeverityEscalationForRepeatedInconsistencies() {
        // First few state inconsistencies are MEDIUM
        for (int i = 0; i < 5; i++) {
            var violation = mapper.mapViolation(createStateInconsistencyViolation());
            assertEquals(MEDIUM, violation.severity());
        }

        // After 10 occurrences, severity escalates to HIGH
        for (int i = 0; i < 6; i++) {
            mapper.mapViolation(createStateInconsistencyViolation());
        }

        var violation = mapper.mapViolation(createStateInconsistencyViolation());
        assertEquals(HIGH, violation.severity());
    }

    /**
     * Test: Reset clears all violation history
     */
    @Test
    public void testReset() {
        // Add violations
        mapper.mapViolation(createInvariantViolation());
        mapper.mapViolation(createPreconditionViolation());

        assertEquals(2, mapper.getTotalViolationCount());
        assertEquals(2, mapper.getRecentViolations().size());

        // Reset
        mapper.reset();

        assertEquals(0, mapper.getTotalViolationCount());
        assertEquals(0, mapper.getRecentViolations().size());
    }

    /**
     * Test: Violation summary is human-readable
     */
    @Test
    public void testViolationSummary() {
        var result = new ValidationResult(
            false,
            List.of("Precondition violated: already started", "Invalid state transition"),
            INITIAL,
            "start",
            Instant.now(),
            ValidationResult.ValidationType.PRECONDITION
        );

        var violation = mapper.mapViolation(result);
        var summary = violation.summary();

        assertTrue(summary.contains("HIGH"));
        assertTrue(summary.contains("PRECONDITION_VIOLATION"));
        assertTrue(summary.contains("INITIAL"));
        assertTrue(summary.contains("start"));
        assertTrue(summary.contains("already started"));
    }

    // ==================== Helper Methods ====================

    private ValidationResult createInvariantViolation() {
        return new ValidationResult(
            false,
            List.of("OPERATIONAL state invariant violated: missing view"),
            OPERATIONAL,
            null,
            Instant.now(),
            ValidationResult.ValidationType.INVARIANT
        );
    }

    private ValidationResult createPreconditionViolation() {
        return new ValidationResult(
            false,
            List.of("Precondition violated: already started"),
            INITIAL,
            "start",
            Instant.now(),
            ValidationResult.ValidationType.PRECONDITION
        );
    }

    private ValidationResult createPostconditionViolation() {
        return new ValidationResult(
            false,
            List.of("Postcondition violated: committee not created"),
            BOOTSTRAPPING,
            "bootstrap",
            Instant.now(),
            ValidationResult.ValidationType.POSTCONDITION
        );
    }

    private ValidationResult createStateInconsistencyViolation() {
        return new ValidationResult(
            false,
            List.of("State consistency violation: torn read detected"),
            RECOVERING,
            null,
            Instant.now(),
            ValidationResult.ValidationType.INVARIANT
        );
    }
}
