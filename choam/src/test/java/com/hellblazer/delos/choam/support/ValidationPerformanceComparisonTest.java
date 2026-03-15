/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.fsm.Combine;
import java.security.SecureRandom;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static com.hellblazer.delos.choam.fsm.Combine.Mercantile.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance comparison tests for state machine validation.
 * Compares validation overhead across different scenarios:
 * 1. Invariant validation overhead
 * 2. Precondition validation overhead
 * 3. Postcondition validation overhead
 * 4. End-to-end transition validation overhead
 *
 * SLA Targets (from Phase 0 baseline):
 * - p95 latency increase < 5%
 * - p99 latency increase < 10%
 * - Throughput decrease < 10%
 *
 * @author hal.hildebrand
 */
@Tag("performance")
public class ValidationPerformanceComparisonTest {
    private static final boolean IS_CI = "true".equalsIgnoreCase(System.getenv("CI"));

    private SecureRandom random;

    // Test configuration
    private static final int WARMUP_ITERATIONS = 1000;
    private static final int TEST_ITERATIONS = 10_000;

    @BeforeEach
    public void setup() {
        random = new SecureRandom();
        random.setSeed("performance-test-seed".getBytes());
    }

    /**
     * Test: Invariant validation overhead
     */
    @Test
    public void testInvariantValidationOverhead() {
        System.out.println("\n=== Invariant Validation Overhead ===");

        // Create test snapshots
        var snapshots = createTestSnapshots(TEST_ITERATIONS);

        // Create warmup validator
        var matrix = StateTransitionMatrix.getInstance();
        var warmupValidator = new StateTransitionValidator(matrix, new SimpleMeterRegistry());

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            warmupValidator.validateInvariant(snapshots.get(i % snapshots.size()));
        }

        // Create fresh validator for metrics
        var metrics = new SimpleMeterRegistry();
        var validator = new StateTransitionValidator(matrix, metrics);

        // Measure with validation
        var startWithValidation = System.nanoTime();
        for (var snapshot : snapshots) {
            validator.validateInvariant(snapshot);
        }
        var durationWithValidation = System.nanoTime() - startWithValidation;

        // Measure without validation (just snapshot access)
        var startWithoutValidation = System.nanoTime();
        for (var snapshot : snapshots) {
            // Simulate equivalent work without validation
            var _ = snapshot.started();
            var _ = snapshot.hasCommittee();
            var _ = snapshot.hasGenesis();
            var _ = snapshot.hasView();
        }
        var durationWithoutValidation = System.nanoTime() - startWithoutValidation;

        // Calculate overhead
        double overheadNs = (double) (durationWithValidation - durationWithoutValidation) / TEST_ITERATIONS;
        double overheadPercent = ((double) durationWithValidation / durationWithoutValidation - 1.0) * 100;

        System.out.printf("Without validation: %.2f μs total (%.2f ns/validation)%n",
                         durationWithoutValidation / 1000.0,
                         (double) durationWithoutValidation / TEST_ITERATIONS);
        System.out.printf("With validation: %.2f μs total (%.2f ns/validation)%n",
                         durationWithValidation / 1000.0,
                         (double) durationWithValidation / TEST_ITERATIONS);
        System.out.printf("Overhead: %.2f ns/validation (%.2f%%)%n", overheadNs, overheadPercent);

        // Verify metrics
        var metricsSnapshot = validator.getMetricsSnapshot();
        assertTrue(metricsSnapshot.isWithinSLA(), "Validation should meet p95 < 244μs SLA");
        assertEquals(TEST_ITERATIONS, metricsSnapshot.invariantCount());

        // SLA: Overhead should be reasonable (< 500ns per validation)
        assertTrue(overheadNs < 500, "Invariant validation overhead should be < 500ns, got: " + overheadNs + "ns");
    }

    /**
     * Test: Precondition validation overhead
     */
    @Test
    public void testPreconditionValidationOverhead() {
        System.out.println("\n=== Precondition Validation Overhead ===");

        var snapshot = createInitialSnapshot();
        var matrix = StateTransitionMatrix.getInstance();

        // Warmup
        var warmupValidator = new StateTransitionValidator(matrix, new SimpleMeterRegistry());
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            warmupValidator.validatePrecondition(INITIAL, "start", snapshot);
        }

        // Create fresh validator for metrics
        var metrics = new SimpleMeterRegistry();
        var validator = new StateTransitionValidator(matrix, metrics);

        // Measure with validation
        var startWith = System.nanoTime();
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            validator.validatePrecondition(INITIAL, "start", snapshot);
        }
        var durationWith = System.nanoTime() - startWith;

        // Measure without validation (just map lookup)
        var startWithout = System.nanoTime();
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            var _ = matrix.getTransition(INITIAL, "start");
        }
        var durationWithout = System.nanoTime() - startWithout;

        double overheadNs = (double) (durationWith - durationWithout) / TEST_ITERATIONS;
        double overheadPercent = ((double) durationWith / durationWithout - 1.0) * 100;

        System.out.printf("Without validation: %.2f μs total (%.2f ns/validation)%n",
                         durationWithout / 1000.0,
                         (double) durationWithout / TEST_ITERATIONS);
        System.out.printf("With validation: %.2f μs total (%.2f ns/validation)%n",
                         durationWith / 1000.0,
                         (double) durationWith / TEST_ITERATIONS);
        System.out.printf("Overhead: %.2f ns/validation (%.2f%%)%n", overheadNs, overheadPercent);

        var metricsSnapshot = validator.getMetricsSnapshot();
        assertEquals(TEST_ITERATIONS, metricsSnapshot.preconditionCount());

        // SLA: Overhead should be reasonable (< 1000ns per validation)
        assertTrue(overheadNs < 1000, "Precondition validation overhead should be < 1000ns, got: " + overheadNs + "ns");
    }

    /**
     * Test: Postcondition validation overhead
     */
    @Test
    public void testPostconditionValidationOverhead() {
        System.out.println("\n=== Postcondition Validation Overhead ===");

        var preSnapshot = createInitialSnapshot();
        var postSnapshot = createRecoveringSnapshot();
        var matrix = StateTransitionMatrix.getInstance();

        // Warmup
        var warmupValidator = new StateTransitionValidator(matrix, new SimpleMeterRegistry());
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            warmupValidator.validatePostcondition(INITIAL, "start", preSnapshot, postSnapshot);
        }

        // Create fresh validator for metrics
        var metrics = new SimpleMeterRegistry();
        var validator = new StateTransitionValidator(matrix, metrics);

        // Measure with validation
        var startWith = System.nanoTime();
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            validator.validatePostcondition(INITIAL, "start", preSnapshot, postSnapshot);
        }
        var durationWith = System.nanoTime() - startWith;

        // Measure without validation (just map lookup + snapshot access)
        var startWithout = System.nanoTime();
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            var _ = matrix.getTransition(INITIAL, "start");
            var _ = preSnapshot.started();
            var _ = postSnapshot.started();
        }
        var durationWithout = System.nanoTime() - startWithout;

        double overheadNs = (double) (durationWith - durationWithout) / TEST_ITERATIONS;
        double overheadPercent = ((double) durationWith / durationWithout - 1.0) * 100;

        System.out.printf("Without validation: %.2f μs total (%.2f ns/validation)%n",
                         durationWithout / 1000.0,
                         (double) durationWithout / TEST_ITERATIONS);
        System.out.printf("With validation: %.2f μs total (%.2f ns/validation)%n",
                         durationWith / 1000.0,
                         (double) durationWith / TEST_ITERATIONS);
        System.out.printf("Overhead: %.2f ns/validation (%.2f%%)%n", overheadNs, overheadPercent);

        var metricsSnapshot = validator.getMetricsSnapshot();
        assertEquals(TEST_ITERATIONS, metricsSnapshot.postconditionCount());

        // SLA: Overhead should be reasonable (< 1500ns per validation)
        assertTrue(overheadNs < 1500, "Postcondition validation overhead should be < 1500ns, got: " + overheadNs + "ns");
    }

    /**
     * Test: End-to-end validation overhead (pre + post + invariants)
     */
    @Test
    public void testEndToEndValidationOverhead() {
        System.out.println("\n=== End-to-End Validation Overhead ===");

        var preSnapshot = createInitialSnapshot();
        var postSnapshot = createRecoveringSnapshot();
        var matrix = StateTransitionMatrix.getInstance();

        // Warmup
        var warmupValidator = new StateTransitionValidator(matrix, new SimpleMeterRegistry());
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            performFullValidation(warmupValidator, preSnapshot, postSnapshot);
        }

        // Create fresh validator for metrics
        var metrics = new SimpleMeterRegistry();
        var validator = new StateTransitionValidator(matrix, metrics);

        // Measure with full validation
        var startWith = System.nanoTime();
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            performFullValidation(validator, preSnapshot, postSnapshot);
        }
        var durationWith = System.nanoTime() - startWith;

        // Measure without validation (just snapshot access)
        var startWithout = System.nanoTime();
        for (int i = 0; i < TEST_ITERATIONS; i++) {
            // Simulate equivalent snapshot reads
            var _ = preSnapshot.started();
            var _ = preSnapshot.hasCommittee();
            var _ = postSnapshot.started();
            var _ = postSnapshot.hasCommittee();
        }
        var durationWithout = System.nanoTime() - startWithout;

        double avgLatencyWith = (double) durationWith / TEST_ITERATIONS;
        double avgLatencyWithout = (double) durationWithout / TEST_ITERATIONS;
        double overheadNs = avgLatencyWith - avgLatencyWithout;
        double overheadPercent = (avgLatencyWith / avgLatencyWithout - 1.0) * 100;

        System.out.printf("Without validation: %.2f μs total (%.2f ns/validation)%n",
                         durationWithout / 1000.0, avgLatencyWithout);
        System.out.printf("With validation: %.2f μs total (%.2f ns/validation)%n",
                         durationWith / 1000.0, avgLatencyWith);
        System.out.printf("Overhead: %.2f ns/validation (%.2f%%)%n", overheadNs, overheadPercent);

        // Verify all validations recorded
        var metricsSnapshot = validator.getMetricsSnapshot();
        assertEquals(TEST_ITERATIONS * 2, metricsSnapshot.invariantCount()); // pre + post invariants
        assertEquals(TEST_ITERATIONS, metricsSnapshot.preconditionCount());
        assertEquals(TEST_ITERATIONS, metricsSnapshot.postconditionCount());

        // SLA: Total overhead should be reasonable (< 2500ns per full validation)
        assertTrue(overheadNs < 2500, "End-to-end validation overhead should be < 2500ns, got: " + overheadNs + "ns");

        // SLA: p95 latency should be within threshold (skip on CI where performance is variable)
        if (!IS_CI) {
            assertTrue(metricsSnapshot.isWithinSLA(), "Validation should meet p95 < 244μs SLA");
        }
    }

    /**
     * Test: Validation throughput comparison
     */
    @Test
    public void testValidationThroughputComparison() {
        System.out.println("\n=== Validation Throughput Comparison ===");

        var snapshots = createTestSnapshots(TEST_ITERATIONS);
        var matrix = StateTransitionMatrix.getInstance();

        // Warmup
        var warmupValidator = new StateTransitionValidator(matrix, new SimpleMeterRegistry());
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            warmupValidator.validateInvariant(snapshots.get(i % snapshots.size()));
        }

        // Create fresh validator for metrics
        var metrics = new SimpleMeterRegistry();
        var validator = new StateTransitionValidator(matrix, metrics);

        // Measure throughput WITH validation (in nanoseconds)
        var startWith = System.nanoTime();
        for (var snapshot : snapshots) {
            validator.validateInvariant(snapshot);
        }
        var durationWithNs = System.nanoTime() - startWith;

        double throughputWith = (TEST_ITERATIONS * 1_000_000_000.0) / durationWithNs; // validations/sec

        // Measure throughput WITHOUT validation (in nanoseconds)
        var startWithout = System.nanoTime();
        for (var snapshot : snapshots) {
            var _ = snapshot.started();
            var _ = snapshot.hasCommittee();
        }
        var durationWithoutNs = System.nanoTime() - startWithout;

        double throughputWithout = (TEST_ITERATIONS * 1_000_000_000.0) / durationWithoutNs;

        double throughputDecrease = (1.0 - throughputWith / throughputWithout) * 100;

        System.out.printf("Without validation: %.2f validations/sec%n", throughputWithout);
        System.out.printf("With validation: %.2f validations/sec%n", throughputWith);
        System.out.printf("Throughput decrease: %.2f%%%n", throughputDecrease);

        // SLA: Absolute throughput should exceed 1M validations/sec
        // (Relative decrease is not meaningful when comparing against trivial field access)
        assertTrue(throughputWith > 1_000_000,
                  "Validation throughput should exceed 1M validations/sec, got: " + throughputWith);
    }

    // ==================== Helper Methods ====================

    private void performFullValidation(StateTransitionValidator validator,
                                      CHOAMStateSnapshot preSnapshot,
                                      CHOAMStateSnapshot postSnapshot) {
        // Validate pre-invariant
        validator.validateInvariant(preSnapshot);

        // Validate precondition
        validator.validatePrecondition(INITIAL, "start", preSnapshot);

        // Validate postcondition
        validator.validatePostcondition(INITIAL, "start", preSnapshot, postSnapshot);

        // Validate post-invariant
        validator.validateInvariant(postSnapshot);
    }

    private List<CHOAMStateSnapshot> createTestSnapshots(int count) {
        var snapshots = new ArrayList<CHOAMStateSnapshot>(count);
        var states = List.of(INITIAL, RECOVERING, OPERATIONAL, BOOTSTRAPPING, SYNCHRONIZING);

        IntStream.range(0, count).forEach(i -> {
            var state = states.get(random.nextInt(states.size()));
            snapshots.add(createValidSnapshotForState(state));
        });

        return snapshots;
    }

    private CHOAMStateSnapshot createValidSnapshotForState(Combine.Mercantile state) {
        return switch (state) {
            case INITIAL -> createInitialSnapshot();
            case RECOVERING -> createRecoveringSnapshot();
            case OPERATIONAL -> createOperationalSnapshot();
            case BOOTSTRAPPING -> createBootstrappingSnapshot();
            case SYNCHRONIZING -> createSynchronizingSnapshot();
            default -> createInitialSnapshot();
        };
    }

    private CHOAMStateSnapshot createInitialSnapshot() {
        return new CHOAMStateSnapshot(
            false, false,
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "INITIAL"
        );
    }

    private CHOAMStateSnapshot createRecoveringSnapshot() {
        return new CHOAMStateSnapshot(
            true, false,
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "RECOVERING"
        );
    }

    private CHOAMStateSnapshot createBootstrappingSnapshot() {
        return new CHOAMStateSnapshot(
            true, false,
            true, "GenesisFormation",
            false, false, -1,
            false, -1, 0,
            0, true, false,
            "BOOTSTRAPPING"
        );
    }

    private CHOAMStateSnapshot createSynchronizingSnapshot() {
        return new CHOAMStateSnapshot(
            true, false,
            true, "Standard",
            false, false, -1,
            false, -1, 0,
            0, false, true,
            "SYNCHRONIZING"
        );
    }

    private CHOAMStateSnapshot createOperationalSnapshot() {
        return new CHOAMStateSnapshot(
            true, false,
            true, "Standard",
            true, true, random.nextInt(1, 1000),
            true, random.nextInt(1, 1000), random.nextInt(0, 5),
            random.nextInt(0, 3), false, false,
            "OPERATIONAL"
        );
    }
}
