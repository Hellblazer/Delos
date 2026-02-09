/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.fsm.Combine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for MockBFTValidator to ensure it satisfies the BFTValidator interface contract.
 *
 * @author hal.hildebrand
 */
@DisplayName("MockBFTValidator Contract Tests")
class MockBFTValidatorContractTest {
    private MockBFTValidator validator;

    @BeforeEach
    void setUp() {
        validator = new MockBFTValidator();
    }

    @Test
    @DisplayName("mapViolation returns null for valid results")
    void mapViolation_ReturnsNull_ForValidResults() {
        var validResult = ValidationResult.success(
            Combine.Mercantile.INITIAL,
            "testTransition",
            ValidationResult.ValidationType.PRECONDITION
        );

        var violation = validator.mapViolation(validResult);

        assertThat(violation).isNull();
    }

    @Test
    @DisplayName("mapViolation detects precondition violations")
    void mapViolation_DetectsPreconditionViolations() {
        var invalidResult = ValidationResult.failure(
            Combine.Mercantile.INITIAL,
            "start",
            ValidationResult.ValidationType.PRECONDITION,
            "Already started"
        );

        var violation = validator.mapViolation(invalidResult);

        assertThat(violation).isNotNull();
        assertThat(violation.type()).isEqualTo(ByzantineViolationType.PRECONDITION_VIOLATION);
        assertThat(violation.severity()).isEqualTo(ByzantineViolation.Severity.HIGH);
    }

    @Test
    @DisplayName("mapViolation detects invariant violations as critical")
    void mapViolation_DetectsInvariantViolations_AsCritical() {
        var invalidResult = ValidationResult.failure(
            Combine.Mercantile.OPERATIONAL,
            null,
            ValidationResult.ValidationType.INVARIANT,
            "Missing genesis block"
        );

        var violation = validator.mapViolation(invalidResult);

        assertThat(violation).isNotNull();
        assertThat(violation.type()).isEqualTo(ByzantineViolationType.STATE_INVARIANT_VIOLATION);
        assertThat(violation.severity()).isEqualTo(ByzantineViolation.Severity.CRITICAL);
    }

    @Test
    @DisplayName("getViolationCount tracks violations by type")
    void getViolationCount_TracksByType() {
        // Create and record multiple violations
        var preconditionFailure = ValidationResult.failure(
            Combine.Mercantile.INITIAL,
            "start",
            ValidationResult.ValidationType.PRECONDITION,
            "Already started"
        );
        var invariantFailure = ValidationResult.failure(
            Combine.Mercantile.OPERATIONAL,
            null,
            ValidationResult.ValidationType.INVARIANT,
            "Missing genesis"
        );

        validator.mapViolation(preconditionFailure);
        validator.mapViolation(preconditionFailure);  // Second precondition violation
        validator.mapViolation(invariantFailure);

        assertThat(validator.getViolationCount(ByzantineViolationType.PRECONDITION_VIOLATION)).isEqualTo(2);
        assertThat(validator.getViolationCount(ByzantineViolationType.STATE_INVARIANT_VIOLATION)).isEqualTo(1);
        assertThat(validator.getTotalViolationCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("getRecentViolations returns violations in reverse chronological order")
    void getRecentViolations_ReturnsReverseChronological() throws InterruptedException {
        // Create violations with small time delays
        var result1 = ValidationResult.failure(
            Combine.Mercantile.INITIAL,
            "transition1",
            ValidationResult.ValidationType.PRECONDITION,
            "Violation 1"
        );
        var result2 = ValidationResult.failure(
            Combine.Mercantile.INITIAL,
            "transition2",
            ValidationResult.ValidationType.PRECONDITION,
            "Violation 2"
        );

        validator.mapViolation(result1);
        Thread.sleep(10);  // Ensure timestamp ordering
        validator.mapViolation(result2);

        var recent = validator.getRecentViolations();

        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).transition()).isEqualTo("transition2");  // Most recent first
        assertThat(recent.get(1).transition()).isEqualTo("transition1");
    }

    @Test
    @DisplayName("getRecentViolations(type) filters by type")
    void getRecentViolations_FiltersByType() {
        var preconditionFailure = ValidationResult.failure(
            Combine.Mercantile.INITIAL,
            "start",
            ValidationResult.ValidationType.PRECONDITION,
            "Already started"
        );
        var invariantFailure = ValidationResult.failure(
            Combine.Mercantile.OPERATIONAL,
            null,
            ValidationResult.ValidationType.INVARIANT,
            "Missing genesis"
        );

        validator.mapViolation(preconditionFailure);
        validator.mapViolation(invariantFailure);
        validator.mapViolation(preconditionFailure);

        var preconditionViolations = validator.getRecentViolations(ByzantineViolationType.PRECONDITION_VIOLATION);
        var invariantViolations = validator.getRecentViolations(ByzantineViolationType.STATE_INVARIANT_VIOLATION);

        assertThat(preconditionViolations).hasSize(2);
        assertThat(invariantViolations).hasSize(1);
    }

    @Test
    @DisplayName("reset clears all violation tracking")
    void reset_ClearsAllTracking() {
        var failure = ValidationResult.failure(
            Combine.Mercantile.INITIAL,
            "test",
            ValidationResult.ValidationType.PRECONDITION,
            "Test violation"
        );
        validator.mapViolation(failure);
        validator.mapViolation(failure);

        assertThat(validator.getTotalViolationCount()).isEqualTo(2);

        validator.reset();

        assertThat(validator.getTotalViolationCount()).isEqualTo(0);
        assertThat(validator.getRecentViolations()).isEmpty();
        assertThat(validator.getViolationCount(ByzantineViolationType.PRECONDITION_VIOLATION)).isEqualTo(0);
    }

    @Test
    @DisplayName("setDetectViolations(false) disables violation detection")
    void setDetectViolations_DisablesDetection() {
        validator.setDetectViolations(false);

        var failure = ValidationResult.failure(
            Combine.Mercantile.INITIAL,
            "test",
            ValidationResult.ValidationType.PRECONDITION,
            "Test violation"
        );

        var violation = validator.mapViolation(failure);

        assertThat(violation).isNull();
        assertThat(validator.getTotalViolationCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("setMaxHistorySize limits retention")
    void setMaxHistorySize_LimitsRetention() {
        validator.setMaxHistorySize(2);

        // Create 3 violations
        var failure = ValidationResult.failure(
            Combine.Mercantile.INITIAL,
            "test",
            ValidationResult.ValidationType.PRECONDITION,
            "Test violation"
        );
        validator.mapViolation(failure);
        validator.mapViolation(failure);
        validator.mapViolation(failure);

        var recent = validator.getRecentViolations();

        // Should only retain last 2
        assertThat(recent).hasSize(2);
        assertThat(validator.getTotalViolationCount()).isEqualTo(3);  // Total count not affected
    }

    @Test
    @DisplayName("thread safety: concurrent violation recording")
    void threadSafety_ConcurrentRecording() throws InterruptedException {
        var threads = new Thread[10];
        var failure = ValidationResult.failure(
            Combine.Mercantile.INITIAL,
            "test",
            ValidationResult.ValidationType.PRECONDITION,
            "Test violation"
        );

        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    validator.mapViolation(failure);
                }
            });
            threads[i].start();
        }

        for (var thread : threads) {
            thread.join();
        }

        // All 1000 violations should be recorded
        assertThat(validator.getTotalViolationCount()).isEqualTo(1000);
    }
}
