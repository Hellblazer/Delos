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

import java.time.Instant;
import java.util.List;

import static com.hellblazer.delos.choam.fsm.Combine.Mercantile.*;
import static com.hellblazer.delos.choam.support.ValidationResult.ValidationType.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ValidationResult record.
 * Verifies validation result creation, factory methods, and invariants.
 *
 * @author hal.hildebrand
 */
public class ValidationResultTest {

    @Test
    public void testSuccessFactoryMethod() {
        var result = ValidationResult.success(OPERATIONAL, "combine", PRECONDITION);

        assertTrue(result.valid());
        assertEquals(OPERATIONAL, result.state());
        assertEquals("combine", result.transitionName());
        assertEquals(PRECONDITION, result.validationType());
        assertTrue(result.violations().isEmpty());
        assertNotNull(result.timestamp());
    }

    @Test
    public void testFailureFactoryMethod_SingleViolation() {
        var result = ValidationResult.failure(
            INITIAL,
            "start",
            PRECONDITION,
            "Already started"
        );

        assertFalse(result.valid());
        assertEquals(INITIAL, result.state());
        assertEquals("start", result.transitionName());
        assertEquals(PRECONDITION, result.validationType());
        assertEquals(1, result.violations().size());
        assertEquals("Already started", result.violations().get(0));
        assertNotNull(result.timestamp());
    }

    @Test
    public void testFailureFactoryMethod_MultipleViolations() {
        var violations = List.of("Violation 1", "Violation 2", "Violation 3");
        var result = ValidationResult.failure(
            OPERATIONAL,
            "combine",
            POSTCONDITION,
            violations
        );

        assertFalse(result.valid());
        assertEquals(OPERATIONAL, result.state());
        assertEquals("combine", result.transitionName());
        assertEquals(POSTCONDITION, result.validationType());
        assertEquals(3, result.violations().size());
        assertEquals(violations, result.violations());
    }

    @Test
    public void testRecordConstructor_ValidSuccess() {
        var timestamp = Instant.now();
        var result = new ValidationResult(
            true,
            List.of(),
            RECOVERING,
            "bootstrap",
            timestamp,
            INVARIANT
        );

        assertTrue(result.valid());
        assertEquals(RECOVERING, result.state());
        assertEquals("bootstrap", result.transitionName());
        assertEquals(timestamp, result.timestamp());
        assertEquals(INVARIANT, result.validationType());
    }

    @Test
    public void testRecordConstructor_ValidFailure() {
        var timestamp = Instant.now();
        var violations = List.of("Test violation");
        var result = new ValidationResult(
            false,
            violations,
            OPERATIONAL,
            "synchd",
            timestamp,
            POSTCONDITION
        );

        assertFalse(result.valid());
        assertEquals(1, result.violations().size());
    }

    @Test
    public void testInvariant_ValidTrueRequiresNoViolations() {
        // Valid=true with violations should throw
        assertThrows(IllegalStateException.class, () -> {
            new ValidationResult(
                true,
                List.of("Should not have violations"),
                OPERATIONAL,
                "combine",
                Instant.now(),
                INVARIANT
            );
        });
    }

    @Test
    public void testInvariant_ValidFalseRequiresViolations() {
        // Valid=false without violations should throw
        assertThrows(IllegalStateException.class, () -> {
            new ValidationResult(
                false,
                List.of(),
                OPERATIONAL,
                "combine",
                Instant.now(),
                INVARIANT
            );
        });
    }

    @Test
    public void testNullHandling_ValidationTypeRequired() {
        assertThrows(IllegalArgumentException.class, () -> {
            new ValidationResult(
                true,
                List.of(),
                OPERATIONAL,
                "combine",
                Instant.now(),
                null  // Validation type cannot be null
            );
        });
    }

    @Test
    public void testNullHandling_ViolationsDefaultToEmpty() {
        var result = new ValidationResult(
            true,
            null,  // null violations become empty list
            OPERATIONAL,
            "combine",
            Instant.now(),
            INVARIANT
        );

        assertNotNull(result.violations());
        assertTrue(result.violations().isEmpty());
    }

    @Test
    public void testNullHandling_TimestampDefaultsToNow() {
        var before = Instant.now();
        var result = new ValidationResult(
            true,
            List.of(),
            OPERATIONAL,
            "combine",
            null,  // null timestamp becomes now
            INVARIANT
        );
        var after = Instant.now();

        assertNotNull(result.timestamp());
        assertTrue(!result.timestamp().isBefore(before));
        assertTrue(!result.timestamp().isAfter(after));
    }

    @Test
    public void testImmutability_ViolationsListCopied() {
        var mutableList = new java.util.ArrayList<>(List.of("Violation 1"));
        var result = ValidationResult.failure(
            OPERATIONAL,
            "combine",
            POSTCONDITION,
            mutableList
        );

        // Modify original list
        mutableList.add("Violation 2");

        // Result should not be affected
        assertEquals(1, result.violations().size());
        assertEquals("Violation 1", result.violations().get(0));
    }

    @Test
    public void testMerge_AllValid() {
        var result1 = ValidationResult.success(OPERATIONAL, "combine", PRECONDITION);
        var result2 = ValidationResult.success(OPERATIONAL, "combine", POSTCONDITION);
        var result3 = ValidationResult.success(OPERATIONAL, "combine", INVARIANT);

        var merged = ValidationResult.merge(List.of(result1, result2, result3));

        assertTrue(merged.valid());
        assertTrue(merged.violations().isEmpty());
    }

    @Test
    public void testMerge_SomeInvalid() {
        var result1 = ValidationResult.success(OPERATIONAL, "combine", PRECONDITION);
        var result2 = ValidationResult.failure(OPERATIONAL, "combine", POSTCONDITION, "Failed 1");
        var result3 = ValidationResult.failure(OPERATIONAL, "combine", INVARIANT, "Failed 2");

        var merged = ValidationResult.merge(List.of(result1, result2, result3));

        assertFalse(merged.valid());
        assertEquals(2, merged.violations().size());
        assertTrue(merged.violations().contains("Failed 1"));
        assertTrue(merged.violations().contains("Failed 2"));
    }

    @Test
    public void testMerge_EmptyList() {
        assertThrows(IllegalArgumentException.class, () -> {
            ValidationResult.merge(List.of());
        });
    }

    @Test
    public void testMerge_NullList() {
        assertThrows(IllegalArgumentException.class, () -> {
            ValidationResult.merge(null);
        });
    }

    @Test
    public void testToString_ValidResult() {
        var result = ValidationResult.success(OPERATIONAL, "combine", PRECONDITION);
        var str = result.toString();

        assertTrue(str.contains("valid"));
        assertTrue(str.contains("PRECONDITION"));
        assertTrue(str.contains("OPERATIONAL"));
        assertTrue(str.contains("combine"));
    }

    @Test
    public void testToString_InvalidResult() {
        var result = ValidationResult.failure(
            INITIAL,
            "start",
            PRECONDITION,
            "Already started"
        );
        var str = result.toString();

        assertTrue(str.contains("INVALID"));
        assertTrue(str.contains("PRECONDITION"));
        assertTrue(str.contains("INITIAL"));
        assertTrue(str.contains("start"));
        assertTrue(str.contains("violations"));
    }

    @Test
    public void testToString_NoTransitionName() {
        var result = ValidationResult.success(OPERATIONAL, null, INVARIANT);
        var str = result.toString();

        assertTrue(str.contains("N/A"));  // Transition name should show as N/A
    }

    @Test
    public void testToDetailedString() {
        var result = ValidationResult.failure(
            OPERATIONAL,
            "combine",
            POSTCONDITION,
            List.of("Violation 1", "Violation 2")
        );
        var detailed = result.toDetailedString();

        assertTrue(detailed.contains("ValidationResult:"));
        assertTrue(detailed.contains("Valid: false"));
        assertTrue(detailed.contains("Type: POSTCONDITION"));
        assertTrue(detailed.contains("State: OPERATIONAL"));
        assertTrue(detailed.contains("Transition: combine"));
        assertTrue(detailed.contains("Violations:"));
        assertTrue(detailed.contains("- Violation 1"));
        assertTrue(detailed.contains("- Violation 2"));
        assertTrue(detailed.contains("Timestamp:"));
    }

    @Test
    public void testToDetailedString_NoViolations() {
        var result = ValidationResult.success(OPERATIONAL, "combine", INVARIANT);
        var detailed = result.toDetailedString();

        assertTrue(detailed.contains("ValidationResult:"));
        assertTrue(detailed.contains("Valid: true"));
        assertFalse(detailed.contains("Violations:"));  // No violations section
    }

    @Test
    public void testAllValidationTypes() {
        // Verify all validation types can be used
        ValidationResult.success(OPERATIONAL, "combine", INVARIANT);
        ValidationResult.success(OPERATIONAL, "combine", PRECONDITION);
        ValidationResult.success(OPERATIONAL, "combine", POSTCONDITION);
        ValidationResult.success(OPERATIONAL, "combine", ENTRY_ACTION);
        ValidationResult.success(OPERATIONAL, "combine", EXIT_ACTION);
    }
}
