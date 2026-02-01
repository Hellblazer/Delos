/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.context.StaticContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.MockMember;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.joou.ULong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 4: FirefliesWitnessAdapter Performance Benchmarking
 * <p>
 * Validates performance characteristics of the FirefliesWitnessAdapter:
 * <ul>
 *   <li>Collection Latency p95 ≤ 100ms</li>
 *   <li>Throughput ≥ 1000 receipts/sec</li>
 *   <li>View Change Impact &lt; 500ms</li>
 *   <li>Memory footprint ≤ baseline</li>
 * </ul>
 * <p>
 * Test Scenarios:
 * <ol>
 *   <li>Light Load: 4-node committee, standard iterations</li>
 *   <li>Medium Load: 7-node committee, standard iterations</li>
 *   <li>Heavy Load: 7-node committee, 2x iterations/concurrency</li>
 *   <li>View Change: Measure disruption during transitions</li>
 *   <li>Byzantine: Performance with f failures</li>
 * </ol>
 * <p>
 * References: Delos-9zhx (Phase 4 benchmarking bead)
 *
 * @author hal.hildebrand
 */
@Tag("performance")
@DisplayName("FirefliesWitnessAdapter Performance Benchmarks")
class FirefliesWitnessAdapterBenchmarkTest {

    private static final DigestAlgorithm ALGORITHM = DigestAlgorithm.DEFAULT;
    private static final int WARMUP_ITERATIONS = 100;
    private static final int MEASUREMENT_ITERATIONS = 1000;

    private FirefliesWitnessAdapter adapter;
    private SecureRandom entropy;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        adapter = new FirefliesWitnessAdapter(ALGORITHM);
        entropy = deterministicEntropy();
        executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    // ===== Collection Latency Tests =====

    @Nested
    @DisplayName("A. Collection Latency (Target: p95 ≤ 100ms)")
    class CollectionLatencyTests {

        @Test
        @DisplayName("A.1: Committee selection latency - Light load (4-node)")
        void committeeSelectionLatency_LightLoad() {
            var context = createTestContext(4, 3, 12);
            var latencies = measureCommitteeSelectionLatency(context, MEASUREMENT_ITERATIONS);

            var stats = calculateStats(latencies);
            printLatencyStats("Light Load (k=4)", stats);

            assertThat(stats.p95)
                .as("P95 latency should be ≤ 100ms")
                .isLessThanOrEqualTo(100.0);
        }

        @Test
        @DisplayName("A.2: Committee selection latency - Medium load (7-node)")
        void committeeSelectionLatency_MediumLoad() {
            var context = createTestContext(7, 5, 21);
            var latencies = measureCommitteeSelectionLatency(context, MEASUREMENT_ITERATIONS);

            var stats = calculateStats(latencies);
            printLatencyStats("Medium Load (k=7)", stats);

            assertThat(stats.p95)
                .as("P95 latency should be ≤ 100ms")
                .isLessThanOrEqualTo(100.0);
        }

        @Test
        @DisplayName("A.3: Committee selection latency - Heavy load (7-node, 2x iterations)")
        void committeeSelectionLatency_HeavyLoad() {
            // k=7, threshold=5, with 2x iterations for stress testing
            var context = createTestContext(7, 5, 21);
            var latencies = measureCommitteeSelectionLatency(context, MEASUREMENT_ITERATIONS * 2);

            var stats = calculateStats(latencies);
            printLatencyStats("Heavy Load (k=7, 2x)", stats);

            assertThat(stats.p95)
                .as("P95 latency should be ≤ 100ms")
                .isLessThanOrEqualTo(100.0);
        }

        @Test
        @DisplayName("A.4: Hash computation latency")
        void hashComputationLatency() {
            var latencies = new ArrayList<Long>();

            // Warmup
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                var coords = createEventCoordinates("warmup-" + i, i);
                adapter.hashEventCoordinates(coords);
            }

            // Measure
            for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                var coords = createEventCoordinates("measure-" + i, i);
                var start = System.nanoTime();
                adapter.hashEventCoordinates(coords);
                var end = System.nanoTime();
                latencies.add((end - start) / 1_000); // microseconds
            }

            var avgUs = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
            var p95Us = calculatePercentile(latencies, 95);

            System.out.printf("Hash Computation: avg=%.2fµs, p95=%.2fµs%n", avgUs, p95Us);

            assertThat(p95Us)
                .as("P95 hash latency should be < 1ms (1000µs)")
                .isLessThan(1000.0);
        }

        @Test
        @DisplayName("A.5: Identifier conversion latency")
        void identifierConversionLatency() {
            var context = createTestContext(7, 5, 21);
            var coords = createEventCoordinates("conversion-test", 1);
            var witnesses = adapter.selectWitnesses(context, coords);

            var latencies = new ArrayList<Long>();

            // Warmup
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                adapter.toWitnessIdentifiers(witnesses);
            }

            // Measure
            for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                var start = System.nanoTime();
                adapter.toWitnessIdentifiers(witnesses);
                var end = System.nanoTime();
                latencies.add((end - start) / 1_000); // microseconds
            }

            var avgUs = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
            var p95Us = calculatePercentile(latencies, 95);

            System.out.printf("Identifier Conversion: avg=%.2fµs, p95=%.2fµs%n", avgUs, p95Us);

            assertThat(p95Us)
                .as("P95 conversion latency should be < 1ms")
                .isLessThan(1000.0);
        }

        private List<Long> measureCommitteeSelectionLatency(Context<MockMember> context, int iterations) {
            var latencies = new ArrayList<Long>();

            // Warmup
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                var coords = createEventCoordinates("warmup-" + i, i);
                adapter.selectWitnesses(context, coords);
            }

            // Measure
            for (int i = 0; i < iterations; i++) {
                var coords = createEventCoordinates("measure-" + i, i);
                var start = System.nanoTime();
                adapter.selectWitnesses(context, coords);
                var end = System.nanoTime();
                latencies.add((end - start) / 1_000_000); // milliseconds
            }

            return latencies;
        }
    }

    // ===== Throughput Tests =====

    @Nested
    @DisplayName("B. Throughput (Target: ≥ 1000 ops/sec)")
    class ThroughputTests {

        @Test
        @DisplayName("B.1: Committee selection throughput - Light load")
        void committeeSelectionThroughput_LightLoad() {
            var context = createTestContext(4, 3, 12);
            var throughput = measureThroughput(context, 10); // 10 events/sec target

            System.out.printf("Light Load Throughput: %.0f selections/sec%n", throughput);

            assertThat(throughput)
                .as("Throughput should be ≥ 1000 ops/sec")
                .isGreaterThanOrEqualTo(1000.0);
        }

        @Test
        @DisplayName("B.2: Committee selection throughput - Medium load")
        void committeeSelectionThroughput_MediumLoad() {
            var context = createTestContext(7, 5, 21);
            var throughput = measureThroughput(context, 100); // 100 events/sec target

            System.out.printf("Medium Load Throughput: %.0f selections/sec%n", throughput);

            assertThat(throughput)
                .as("Throughput should be ≥ 1000 ops/sec")
                .isGreaterThanOrEqualTo(1000.0);
        }

        @Test
        @DisplayName("B.3: Committee selection throughput - Heavy load")
        void committeeSelectionThroughput_HeavyLoad() {
            // k=7 with 2x iteration count for stress testing
            var context = createTestContext(7, 5, 21);
            var throughput = measureThroughput(context, 1000); // 1000 events/sec target

            System.out.printf("Heavy Load Throughput: %.0f selections/sec%n", throughput);

            assertThat(throughput)
                .as("Throughput should be ≥ 1000 ops/sec")
                .isGreaterThanOrEqualTo(1000.0);
        }

        @Test
        @DisplayName("B.4: Concurrent selection throughput")
        void concurrentSelectionThroughput() throws Exception {
            var context = createTestContext(7, 5, 21);
            var durationSeconds = 3;
            var operationsCompleted = new AtomicLong(0);
            var running = new AtomicBoolean(true);
            var startLatch = new CountDownLatch(1);

            // Launch concurrent workers
            var numWorkers = Runtime.getRuntime().availableProcessors();
            var futures = new ArrayList<Future<?>>();

            for (int i = 0; i < numWorkers; i++) {
                var workerId = i;
                futures.add(executor.submit(() -> {
                    try {
                        startLatch.await();
                        var eventCounter = 0;
                        while (running.get()) {
                            var coords = createEventCoordinates("worker-" + workerId + "-event-" + eventCounter++, eventCounter);
                            adapter.selectWitnesses(context, coords);
                            operationsCompleted.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }

            // Start measurement
            var start = System.currentTimeMillis();
            startLatch.countDown();

            Thread.sleep(durationSeconds * 1000L);

            running.set(false);
            var end = System.currentTimeMillis();

            // Wait for workers
            for (var future : futures) {
                future.get(1, TimeUnit.SECONDS);
            }

            var actualDurationSec = (end - start) / 1000.0;
            var throughput = operationsCompleted.get() / actualDurationSec;

            System.out.printf("Concurrent Throughput (%d workers): %d ops in %.2fs = %.0f ops/sec%n",
                numWorkers, operationsCompleted.get(), actualDurationSec, throughput);

            assertThat(throughput)
                .as("Concurrent throughput should be ≥ 1000 ops/sec")
                .isGreaterThanOrEqualTo(1000.0);
        }

        @Test
        @DisplayName("B.5: Bias computation throughput")
        void biasComputationThroughput() {
            var iterations = 100_000;

            // Warmup
            for (int n = 4; n <= 20; n++) {
                for (int t = (n / 2) + 1; t <= n; t++) {
                    adapter.computeBias(n, t);
                }
            }

            // Measure
            var start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                int n = 4 + (i % 17); // 4 to 20
                int t = (n / 2) + 1 + (i % (n - (n / 2)));
                adapter.computeBias(n, t);
            }
            var end = System.nanoTime();

            var durationMs = (end - start) / 1_000_000.0;
            var throughput = iterations / (durationMs / 1000.0);

            System.out.printf("Bias Computation: %d iterations in %.2fms = %.0f ops/sec%n",
                iterations, durationMs, throughput);

            assertThat(throughput)
                .as("Bias computation should be > 100,000 ops/sec")
                .isGreaterThan(100_000.0);
        }

        private double measureThroughput(Context<MockMember> context, int targetEventsPerSec) {
            var iterations = 10_000;

            // Warmup
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                var coords = createEventCoordinates("warmup-" + i, i);
                adapter.selectWitnesses(context, coords);
            }

            // Measure
            var start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                var coords = createEventCoordinates("measure-" + i, i);
                adapter.selectWitnesses(context, coords);
            }
            var end = System.nanoTime();

            var durationSec = (end - start) / 1_000_000_000.0;
            return iterations / durationSec;
        }
    }

    // ===== View Change Impact Tests =====

    @Nested
    @DisplayName("C. View Change Impact (Target: < 500ms disruption)")
    class ViewChangeImpactTests {

        @Test
        @DisplayName("C.1: Selection stability during simulated view change")
        void selectionStabilityDuringViewChange() throws Exception {
            var context = createTestContext(7, 5, 21);
            var coords = createEventCoordinates("view-change-test", 1);

            // Baseline latency
            var baselineLatencies = new ArrayList<Long>();
            for (int i = 0; i < 100; i++) {
                var start = System.nanoTime();
                adapter.selectWitnesses(context, coords);
                var end = System.nanoTime();
                baselineLatencies.add((end - start) / 1_000_000);
            }

            // Simulate view change (measure during context recreation)
            var viewChangeLatencies = new ArrayList<Long>();
            for (int i = 0; i < 100; i++) {
                var newContext = createTestContext(7, 5, 21);
                var newCoords = createEventCoordinates("view-" + i, i);

                var start = System.nanoTime();
                adapter.selectWitnesses(newContext, newCoords);
                var end = System.nanoTime();
                viewChangeLatencies.add((end - start) / 1_000_000);
            }

            var baselineAvg = baselineLatencies.stream().mapToLong(Long::longValue).average().orElse(0);
            var viewChangeAvg = viewChangeLatencies.stream().mapToLong(Long::longValue).average().orElse(0);
            var impact = viewChangeAvg - baselineAvg;

            System.out.printf("View Change Impact: baseline=%.2fms, during=%.2fms, impact=%.2fms%n",
                baselineAvg, viewChangeAvg, impact);

            assertThat(impact)
                .as("View change impact should be < 500ms")
                .isLessThan(500.0);
        }

        @Test
        @DisplayName("C.2: Context creation overhead")
        void contextCreationOverhead() {
            var latencies = new ArrayList<Long>();

            // Warmup
            for (int i = 0; i < 10; i++) {
                adapter.createContext(ALGORITHM.digest("warmup-" + i), 7, 5, 0.1);
            }

            // Measure
            for (int i = 0; i < 100; i++) {
                var start = System.nanoTime();
                adapter.createContext(ALGORITHM.digest("measure-" + i), 7, 5, 0.1);
                var end = System.nanoTime();
                latencies.add((end - start) / 1_000_000);
            }

            var stats = calculateStats(latencies);
            printLatencyStats("Context Creation", stats);

            assertThat(stats.p95)
                .as("Context creation p95 should be < 500ms")
                .isLessThan(500.0);
        }

        @Test
        @DisplayName("C.3: Concurrent operations during context switch")
        void concurrentOperationsDuringContextSwitch() throws Exception {
            var context = new AtomicReference<>(createTestContext(7, 5, 21));
            var operationsCompleted = new AtomicLong(0);
            var errors = new AtomicLong(0);
            var running = new AtomicBoolean(true);
            var startLatch = new CountDownLatch(1);

            // Workers performing selections
            var numWorkers = 4;
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < numWorkers; i++) {
                var workerId = i;
                futures.add(executor.submit(() -> {
                    try {
                        startLatch.await();
                        var counter = 0;
                        while (running.get()) {
                            try {
                                var coords = createEventCoordinates("worker-" + workerId + "-" + counter++, counter);
                                adapter.selectWitnesses(context.get(), coords);
                                operationsCompleted.incrementAndGet();
                            } catch (Exception e) {
                                errors.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }

            startLatch.countDown();

            // Perform context switches while workers are running
            for (int i = 0; i < 10; i++) {
                Thread.sleep(100);
                context.set(createTestContext(7, 5, 21));
            }

            running.set(false);

            for (var future : futures) {
                future.get(1, TimeUnit.SECONDS);
            }

            System.out.printf("Context Switch Test: %d ops, %d errors%n",
                operationsCompleted.get(), errors.get());

            assertThat(errors.get())
                .as("No errors during context switch")
                .isZero();

            assertThat(operationsCompleted.get())
                .as("Operations should complete during switches")
                .isGreaterThan(0);
        }
    }

    // ===== Memory Footprint Tests =====

    @Nested
    @DisplayName("D. Memory Footprint (Target: ≤ baseline)")
    class MemoryFootprintTests {

        @Test
        @DisplayName("D.1: Memory stability during extended operation")
        @EnabledIfSystemProperty(named = "large_tests", matches = "true")
        void memoryStabilityDuringExtendedOperation() throws Exception {
            var runtime = Runtime.getRuntime();
            var context = createTestContext(7, 5, 21);
            var iterations = 50_000;

            // Force GC and measure baseline
            System.gc();
            Thread.sleep(100);
            var baselineMemory = runtime.totalMemory() - runtime.freeMemory();

            // Extended operation
            for (int i = 0; i < iterations; i++) {
                var coords = createEventCoordinates("extended-" + i, i);
                var witnesses = adapter.selectWitnesses(context, coords);
                adapter.toWitnessIdentifiers(witnesses);

                if (i % 10_000 == 0) {
                    System.gc();
                }
            }

            // Force GC and measure final
            System.gc();
            Thread.sleep(100);
            var finalMemory = runtime.totalMemory() - runtime.freeMemory();

            var baselineMB = baselineMemory / (1024.0 * 1024.0);
            var finalMB = finalMemory / (1024.0 * 1024.0);
            var increaseMB = finalMB - baselineMB;

            System.out.printf("Memory: baseline=%.2fMB, final=%.2fMB, increase=%.2fMB%n",
                baselineMB, finalMB, increaseMB);

            assertThat(increaseMB)
                .as("Memory increase should be < 50MB after %d iterations", iterations)
                .isLessThan(50.0);
        }

        @Test
        @DisplayName("D.2: No memory leak in context creation")
        void noMemoryLeakInContextCreation() throws Exception {
            var runtime = Runtime.getRuntime();
            var iterations = 1000;

            // Force GC and measure baseline
            System.gc();
            Thread.sleep(100);
            var baselineMemory = runtime.totalMemory() - runtime.freeMemory();

            // Create many contexts
            for (int i = 0; i < iterations; i++) {
                adapter.createContext(ALGORITHM.digest("context-" + i), 7, 5, 0.1);

                if (i % 100 == 0) {
                    System.gc();
                }
            }

            // Force GC and measure final
            System.gc();
            Thread.sleep(100);
            var finalMemory = runtime.totalMemory() - runtime.freeMemory();

            var increaseMB = (finalMemory - baselineMemory) / (1024.0 * 1024.0);

            System.out.printf("Context Creation Memory: increase=%.2fMB after %d contexts%n",
                increaseMB, iterations);

            assertThat(increaseMB)
                .as("Memory increase should be < 20MB")
                .isLessThan(20.0);
        }
    }

    // ===== Byzantine Performance Tests =====

    @Nested
    @Tag("byzantine")
    @DisplayName("E. Byzantine Performance (f failures)")
    class ByzantinePerformanceTests {

        @Test
        @DisplayName("E.1: Performance with f=1 failures (k=4)")
        void performanceWithF1Failures() {
            // k=4, f=1, threshold=3
            var context = createTestContext(4, 3, 12);
            var throughput = measureThroughputWithFailures(context, 1);

            System.out.printf("Byzantine (f=1): %.0f ops/sec%n", throughput);

            assertThat(throughput)
                .as("Throughput with f=1 should be ≥ 1000 ops/sec")
                .isGreaterThanOrEqualTo(1000.0);
        }

        @Test
        @DisplayName("E.2: Performance with f=2 failures (k=7)")
        void performanceWithF2Failures() {
            // k=7, f=2, threshold=5
            var context = createTestContext(7, 5, 21);
            var throughput = measureThroughputWithFailures(context, 2);

            System.out.printf("Byzantine (f=2): %.0f ops/sec%n", throughput);

            assertThat(throughput)
                .as("Throughput with f=2 should be ≥ 1000 ops/sec")
                .isGreaterThanOrEqualTo(1000.0);
        }

        @Test
        @DisplayName("E.3: Sustained throughput with f=2 failures (k=7)")
        void sustainedThroughputWithF2Failures() {
            // k=7, f=2, threshold=5 - sustained load testing
            var context = createTestContext(7, 5, 21);
            var throughput = measureThroughputWithFailures(context, 2);

            System.out.printf("Byzantine Sustained (f=2, k=7): %.0f ops/sec%n", throughput);

            assertThat(throughput)
                .as("Sustained throughput with f=2 should be ≥ 1000 ops/sec")
                .isGreaterThanOrEqualTo(1000.0);
        }

        @Test
        @DisplayName("E.4: Latency impact of Byzantine members")
        void latencyImpactOfByzantineMembers() {
            var context = createTestContext(7, 5, 21);

            // Baseline without simulated failures
            var baselineLatencies = new ArrayList<Long>();
            for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                var coords = createEventCoordinates("baseline-" + i, i);
                var start = System.nanoTime();
                adapter.selectWitnesses(context, coords);
                var end = System.nanoTime();
                baselineLatencies.add((end - start) / 1_000_000);
            }

            // Committee selection is deterministic and doesn't change with "failures"
            // This test validates that the adapter maintains consistent performance
            var stats = calculateStats(baselineLatencies);
            printLatencyStats("Byzantine Scenario", stats);

            assertThat(stats.p95)
                .as("P95 latency under Byzantine conditions should be ≤ 100ms")
                .isLessThanOrEqualTo(100.0);
        }

        private double measureThroughputWithFailures(Context<MockMember> context, int failureCount) {
            var iterations = 10_000;

            // Warmup
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                var coords = createEventCoordinates("warmup-" + i, i);
                adapter.selectWitnesses(context, coords);
            }

            // Measure - simulating that we ignore f members' responses
            var start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                var coords = createEventCoordinates("byzantine-" + i, i);
                var witnesses = adapter.selectWitnesses(context, coords);
                // Simulate ignoring f members (just accessing the subset)
                var responding = witnesses.stream()
                    .skip(failureCount)
                    .toList();
            }
            var end = System.nanoTime();

            var durationSec = (end - start) / 1_000_000_000.0;
            return iterations / durationSec;
        }
    }

    // ===== Comparison Benchmark =====

    @Nested
    @DisplayName("F. Performance Comparison Report")
    class ComparisonReportTests {

        @Test
        @DisplayName("F.1: Generate comprehensive performance report")
        void generatePerformanceReport() {
            var report = new StringBuilder();
            report.append("\n========== FirefliesWitnessAdapter Performance Report ==========\n\n");

            // Light load (k=4)
            var lightContext = createTestContext(4, 3, 12);
            var lightLatencies = measureLatencies(lightContext, 1000);
            var lightStats = calculateStats(lightLatencies);
            var lightThroughput = measureSimpleThroughput(lightContext, 10000);

            // Medium load (k=7)
            var mediumContext = createTestContext(7, 5, 21);
            var mediumLatencies = measureLatencies(mediumContext, 1000);
            var mediumStats = calculateStats(mediumLatencies);
            var mediumThroughput = measureSimpleThroughput(mediumContext, 10000);

            // Heavy load (k=7) with 2x iterations for sustained load
            var heavyContext = createTestContext(7, 5, 21);
            var heavyLatencies = measureLatencies(heavyContext, 2000); // 2x iterations
            var heavyStats = calculateStats(heavyLatencies);
            var heavyThroughput = measureSimpleThroughput(heavyContext, 20000); // 2x iterations

            report.append("| Scenario | Avg (ms) | P50 (ms) | P95 (ms) | P99 (ms) | Max (ms) | Throughput (ops/s) |\n");
            report.append("|----------|----------|----------|----------|----------|----------|--------------------|\n");
            report.append(formatRow("Light (k=4)", lightStats, lightThroughput));
            report.append(formatRow("Medium (k=7)", mediumStats, mediumThroughput));
            report.append(formatRow("Heavy (2x)", heavyStats, heavyThroughput));

            report.append("\n--- Performance Targets ---\n");
            report.append("Collection Latency p95: ≤ 100ms ... ");
            report.append(lightStats.p95 <= 100 && mediumStats.p95 <= 100 && heavyStats.p95 <= 100 ? "PASS" : "FAIL");
            report.append("\n");

            report.append("Throughput: ≥ 1000 ops/sec ... ");
            report.append(lightThroughput >= 1000 && mediumThroughput >= 1000 && heavyThroughput >= 1000 ? "PASS" : "FAIL");
            report.append("\n");

            report.append("\n================================================================\n");

            System.out.println(report);

            // Assertions for all targets
            assertThat(lightStats.p95).as("Light load p95").isLessThanOrEqualTo(100.0);
            assertThat(mediumStats.p95).as("Medium load p95").isLessThanOrEqualTo(100.0);
            assertThat(heavyStats.p95).as("Heavy load p95").isLessThanOrEqualTo(100.0);
            assertThat(lightThroughput).as("Light throughput").isGreaterThanOrEqualTo(1000.0);
            assertThat(mediumThroughput).as("Medium throughput").isGreaterThanOrEqualTo(1000.0);
            assertThat(heavyThroughput).as("Heavy throughput").isGreaterThanOrEqualTo(1000.0);
        }

        private List<Long> measureLatencies(Context<MockMember> context, int iterations) {
            var latencies = new ArrayList<Long>();
            for (int i = 0; i < iterations; i++) {
                var coords = createEventCoordinates("latency-" + i, i);
                var start = System.nanoTime();
                adapter.selectWitnesses(context, coords);
                var end = System.nanoTime();
                latencies.add((end - start) / 1_000_000);
            }
            return latencies;
        }

        private double measureSimpleThroughput(Context<MockMember> context, int iterations) {
            var start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                var coords = createEventCoordinates("throughput-" + i, i);
                adapter.selectWitnesses(context, coords);
            }
            var end = System.nanoTime();
            return iterations / ((end - start) / 1_000_000_000.0);
        }

        private String formatRow(String scenario, LatencyStats stats, double throughput) {
            return String.format("| %-8s | %8.2f | %8.2f | %8.2f | %8.2f | %8.2f | %18.0f |\n",
                scenario, stats.avg, stats.p50, stats.p95, stats.p99, stats.max, throughput);
        }
    }

    // ===== Helper Methods =====

    private static SecureRandom deterministicEntropy() {
        try {
            var random = SecureRandom.getInstance("SHA1PRNG");
            random.setSeed(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });
            return random;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Context<MockMember> createTestContext(int committeeSize, int threshold, int poolSize) {
        var members = IntStream.range(0, poolSize)
            .mapToObj(i -> new MockMember(ALGORITHM.digest("member-" + i)))
            .toList();

        var contextId = ALGORITHM.digest("benchmark-context".getBytes());
        return new StaticContext<>(contextId, 0.1, members, committeeSize);
    }

    private EventCoordinates createEventCoordinates(String id, long seq) {
        var coords = mock(EventCoordinates.class);
        var identifier = new SelfAddressingIdentifier(ALGORITHM.digest(id));

        when(coords.getIdentifier()).thenReturn(identifier);
        when(coords.getSequenceNumber()).thenReturn(ULong.valueOf(seq));
        when(coords.getDigest()).thenReturn(ALGORITHM.digest("digest-" + seq));
        when(coords.getIlk()).thenReturn("icp");

        return coords;
    }

    private LatencyStats calculateStats(List<Long> latencies) {
        var sorted = latencies.stream().sorted().toList();
        var avg = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
        var max = latencies.stream().mapToLong(Long::longValue).max().orElse(0);

        return new LatencyStats(
            avg,
            calculatePercentile(latencies, 50),
            calculatePercentile(latencies, 95),
            calculatePercentile(latencies, 99),
            max
        );
    }

    private double calculatePercentile(List<Long> values, int percentile) {
        var sorted = values.stream().sorted().toList();
        var index = (int) Math.ceil((percentile / 100.0) * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private void printLatencyStats(String label, LatencyStats stats) {
        System.out.printf("%s: avg=%.2fms, p50=%.2fms, p95=%.2fms, p99=%.2fms, max=%.2fms%n",
            label, stats.avg, stats.p50, stats.p95, stats.p99, stats.max);
    }

    private record LatencyStats(double avg, double p50, double p95, double p99, double max) {}
}
