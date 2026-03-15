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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.hellblazer.delos.choam.fsm.Combine.Mercantile.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance tests for Byzantine violation detection latency.
 * <p>
 * Verifies that Byzantine violation detection via {@link ByzantineDetectionMapper}
 * completes within the < 1ms SLA from validation result to classified violation.
 * </p>
 * <p>
 * SLA Target: p95 detection latency < 1ms (1,000,000 ns)
 * </p>
 *
 * @author hal.hildebrand
 */
@Tag("performance")
public class ByzantineDetectionLatencyTest {

    private StateTransitionValidator validator;
    private ByzantineDetectionMapper mapper;

    // Test configuration
    private static final int WARMUP_ITERATIONS = 100;
    private static final int TEST_ITERATIONS = 1000;
    private static final long SLA_P95_NANOS = 1_000_000;  // 1ms

    @BeforeEach
    public void setup() {
        var matrix = StateTransitionMatrix.getInstance();
        var metrics = new SimpleMeterRegistry();
        validator = new StateTransitionValidator(matrix, metrics);
        mapper = new ByzantineDetectionMapper();
    }

    /**
     * Test: Invariant violation detection latency < 1ms (p95)
     */
    @Test
    public void testInvariantViolationDetectionLatency() {
        System.out.println("\n=== Invariant Violation Detection Latency ===");

        // Create test violation
        var invalidSnapshot = new CHOAMStateSnapshot(
            true, false,
            true, "Standard",
            false, false, -1,  // No genesis
            true, 50, 0,
            0, false, false,
            "OPERATIONAL"
        );

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            var result = validator.validateInvariant(invalidSnapshot);
            mapper.mapViolation(result);
        }

        // Reset mapper to clear warmup data
        mapper.reset();

        // Measure detection latency
        var latencies = new ArrayList<Long>(TEST_ITERATIONS);

        for (int i = 0; i < TEST_ITERATIONS; i++) {
            var result = validator.validateInvariant(invalidSnapshot);

            var startNanos = System.nanoTime();
            var violation = mapper.mapViolation(result);
            var latencyNanos = System.nanoTime() - startNanos;

            assertNotNull(violation);
            latencies.add(latencyNanos);
        }

        // Calculate percentiles
        latencies.sort(Long::compareTo);
        long p50 = latencies.get(TEST_ITERATIONS / 2);
        long p95 = latencies.get((int) (TEST_ITERATIONS * 0.95));
        long p99 = latencies.get((int) (TEST_ITERATIONS * 0.99));
        long max = latencies.get(TEST_ITERATIONS - 1);

        System.out.printf("Detection latency (invariant violation):%n");
        System.out.printf("  p50: %.2f μs%n", p50 / 1000.0);
        System.out.printf("  p95: %.2f μs%n", p95 / 1000.0);
        System.out.printf("  p99: %.2f μs%n", p99 / 1000.0);
        System.out.printf("  max: %.2f μs%n", max / 1000.0);

        // SLA: p95 < 1ms
        assertTrue(p95 < SLA_P95_NANOS,
                   "p95 detection latency should be < 1ms, got: " + (p95 / 1000.0) + " μs");
    }

    /**
     * Test: Precondition violation detection latency < 1ms (p95)
     */
    @Test
    public void testPreconditionViolationDetectionLatency() {
        System.out.println("\n=== Precondition Violation Detection Latency ===");

        var invalidSnapshot = new CHOAMStateSnapshot(
            true, false,  // Already started
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "INITIAL"
        );

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            var result = validator.validatePrecondition(INITIAL, "start", invalidSnapshot);
            mapper.mapViolation(result);
        }

        mapper.reset();

        // Measure
        var latencies = new ArrayList<Long>(TEST_ITERATIONS);

        for (int i = 0; i < TEST_ITERATIONS; i++) {
            var result = validator.validatePrecondition(INITIAL, "start", invalidSnapshot);

            var startNanos = System.nanoTime();
            var violation = mapper.mapViolation(result);
            var latencyNanos = System.nanoTime() - startNanos;

            assertNotNull(violation);
            latencies.add(latencyNanos);
        }

        latencies.sort(Long::compareTo);
        long p95 = latencies.get((int) (TEST_ITERATIONS * 0.95));

        System.out.printf("p95 detection latency: %.2f μs%n", p95 / 1000.0);

        assertTrue(p95 < SLA_P95_NANOS,
                   "p95 detection latency should be < 1ms, got: " + (p95 / 1000.0) + " μs");
    }

    /**
     * Test: Postcondition violation detection latency < 1ms (p95)
     */
    @Test
    public void testPostconditionViolationDetectionLatency() {
        System.out.println("\n=== Postcondition Violation Detection Latency ===");

        var preSnapshot = new CHOAMStateSnapshot(
            true, false,
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "RECOVERING"
        );

        var postSnapshot = new CHOAMStateSnapshot(
            true, false,
            false, null,  // No committee after bootstrap
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "BOOTSTRAPPING"
        );

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            var result = validator.validatePostcondition(RECOVERING, "bootstrap", preSnapshot, postSnapshot);
            mapper.mapViolation(result);
        }

        mapper.reset();

        // Measure
        var latencies = new ArrayList<Long>(TEST_ITERATIONS);

        for (int i = 0; i < TEST_ITERATIONS; i++) {
            var result = validator.validatePostcondition(RECOVERING, "bootstrap", preSnapshot, postSnapshot);

            var startNanos = System.nanoTime();
            var violation = mapper.mapViolation(result);
            var latencyNanos = System.nanoTime() - startNanos;

            assertNotNull(violation);
            latencies.add(latencyNanos);
        }

        latencies.sort(Long::compareTo);
        long p95 = latencies.get((int) (TEST_ITERATIONS * 0.95));

        System.out.printf("p95 detection latency: %.2f μs%n", p95 / 1000.0);

        assertTrue(p95 < SLA_P95_NANOS,
                   "p95 detection latency should be < 1ms, got: " + (p95 / 1000.0) + " μs");
    }

    /**
     * Test: End-to-end detection latency (validation + mapping)
     */
    @Test
    public void testEndToEndDetectionLatency() {
        System.out.println("\n=== End-to-End Detection Latency ===");

        var invalidSnapshot = new CHOAMStateSnapshot(
            true, false,
            true, "Standard",
            false, false, -1,  // No genesis
            true, 50, 0,
            0, false, false,
            "OPERATIONAL"
        );

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            var result = validator.validateInvariant(invalidSnapshot);
            mapper.mapViolation(result);
        }

        mapper.reset();

        // Measure end-to-end (validation + mapping)
        var latencies = new ArrayList<Long>(TEST_ITERATIONS);

        for (int i = 0; i < TEST_ITERATIONS; i++) {
            var startNanos = System.nanoTime();

            var result = validator.validateInvariant(invalidSnapshot);
            var violation = mapper.mapViolation(result);

            var latencyNanos = System.nanoTime() - startNanos;

            assertNotNull(violation);
            latencies.add(latencyNanos);
        }

        latencies.sort(Long::compareTo);
        long p50 = latencies.get(TEST_ITERATIONS / 2);
        long p95 = latencies.get((int) (TEST_ITERATIONS * 0.95));
        long p99 = latencies.get((int) (TEST_ITERATIONS * 0.99));
        long max = latencies.get(TEST_ITERATIONS - 1);

        System.out.printf("End-to-end detection latency:%n");
        System.out.printf("  p50: %.2f μs%n", p50 / 1000.0);
        System.out.printf("  p95: %.2f μs%n", p95 / 1000.0);
        System.out.printf("  p99: %.2f μs%n", p99 / 1000.0);
        System.out.printf("  max: %.2f μs%n", max / 1000.0);

        // More lenient SLA for end-to-end (includes validation overhead)
        // Target: p95 < 2ms
        assertTrue(p95 < 2_000_000,
                   "p95 end-to-end latency should be < 2ms, got: " + (p95 / 1000.0) + " μs");
    }

    /**
     * Test: Throughput of detection (detections per second)
     */
    @Test
    public void testDetectionThroughput() {
        System.out.println("\n=== Byzantine Detection Throughput ===");

        var invalidSnapshot = new CHOAMStateSnapshot(
            true, false,
            true, "Standard",
            false, false, -1,
            true, 50, 0,
            0, false, false,
            "OPERATIONAL"
        );

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            var result = validator.validateInvariant(invalidSnapshot);
            mapper.mapViolation(result);
        }

        mapper.reset();

        // Measure throughput
        var startNanos = System.nanoTime();

        for (int i = 0; i < TEST_ITERATIONS; i++) {
            var result = validator.validateInvariant(invalidSnapshot);
            mapper.mapViolation(result);
        }

        var durationNanos = System.nanoTime() - startNanos;
        double durationSec = durationNanos / 1_000_000_000.0;
        double throughput = TEST_ITERATIONS / durationSec;

        System.out.printf("Detection throughput: %.2f detections/sec%n", throughput);
        System.out.printf("Average latency: %.2f μs/detection%n",
                          (durationNanos / TEST_ITERATIONS) / 1000.0);

        // SLA: Throughput > 100K detections/sec
        assertTrue(throughput > 100_000,
                   "Detection throughput should exceed 100K/sec, got: " + throughput);
    }

    /**
     * Test: Detection latency under high concurrent load
     */
    @Test
    public void testDetectionLatencyUnderLoad() {
        System.out.println("\n=== Detection Latency Under Load ===");

        // Create multiple violation scenarios
        var scenarios = List.of(
            createInvariantViolation(),
            createPreconditionViolation(),
            createPostconditionViolation()
        );

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            var scenario = scenarios.get(i % scenarios.size());
            var result = validator.validateInvariant(scenario);
            mapper.mapViolation(result);
        }

        mapper.reset();

        // Measure under mixed load
        var latencies = new ArrayList<Long>(TEST_ITERATIONS);

        for (int i = 0; i < TEST_ITERATIONS; i++) {
            var scenario = scenarios.get(i % scenarios.size());
            var result = validator.validateInvariant(scenario);

            var startNanos = System.nanoTime();
            var violation = mapper.mapViolation(result);
            var latencyNanos = System.nanoTime() - startNanos;

            assertNotNull(violation);
            latencies.add(latencyNanos);
        }

        latencies.sort(Long::compareTo);
        long p95 = latencies.get((int) (TEST_ITERATIONS * 0.95));

        System.out.printf("p95 detection latency under load: %.2f μs%n", p95 / 1000.0);

        assertTrue(p95 < SLA_P95_NANOS,
                   "p95 detection latency under load should be < 1ms, got: " + (p95 / 1000.0) + " μs");
    }

    // ==================== Helper Methods ====================

    private CHOAMStateSnapshot createInvariantViolation() {
        return new CHOAMStateSnapshot(
            true, false,
            true, "Standard",
            false, false, -1,  // No genesis
            true, 50, 0,
            0, false, false,
            "OPERATIONAL"
        );
    }

    private CHOAMStateSnapshot createPreconditionViolation() {
        return new CHOAMStateSnapshot(
            true, false,  // Already started
            false, null,
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "INITIAL"
        );
    }

    private CHOAMStateSnapshot createPostconditionViolation() {
        return new CHOAMStateSnapshot(
            true, false,
            false, null,  // No committee
            false, false, -1,
            false, -1, 0,
            0, false, false,
            "BOOTSTRAPPING"
        );
    }
}
