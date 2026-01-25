/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation;

import org.joou.ULong;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.cryptography.bls.BLSTestFixtures;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive concurrency and stress tests for SignatureAccumulator.
 * <p>
 * Test Coverage:
 * - Late signer rejection (single-threaded and concurrent)
 * - Memory safety (late signers not stored)
 * - Metrics tracking accuracy
 * - Virtual thread compatibility (no pinning)
 * - Race conditions at threshold boundary
 * - High-load stress testing
 * <p>
 * Performance Requirements:
 * - Late signer rejection: <0.01ms per rejection
 * - Stress test: 10,000 accumulations in <30s
 * - Virtual thread test: completes in <100ms with no pinning
 *
 * @author hal.hildebrand
 */
class SignatureAccumulatorConcurrencyTest {
    private static final DigestAlgorithm ALGORITHM = DigestAlgorithm.DEFAULT;
    private EventCoordinates testEvent;
    private List<Identifier> testMembers;
    private List<BLSSignature> testSignatures;

    @BeforeEach
    void setUp() {
        // Create test event
        var identifier = new SelfAddressingIdentifier(ALGORITHM.digest("test-identifier".getBytes()));
        var digest = ALGORITHM.digest("test-event".getBytes());
        var ilk = "icp";
        testEvent = new EventCoordinates(identifier, ULong.valueOf(1), digest, ilk);

        // Create test members and signatures (100 for stress testing)
        testMembers = new ArrayList<>();
        testSignatures = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            var memberDigest = ALGORITHM.digest(("member-" + i).getBytes());
            testMembers.add(new SelfAddressingIdentifier(memberDigest));
            testSignatures.add(new BLSSignature(BLSTestFixtures.randomMessage(96)));
        }
    }

    // ========== Single-Threaded Late Signer Rejection ==========

    @Test
    void rejectAfterThreshold_SingleThread() {
        var accumulator = new SignatureAccumulator(testEvent, 3, 0, null);

        // Reach threshold
        accumulator.accumulate(testMembers.get(0), 0, testSignatures.get(0));
        accumulator.accumulate(testMembers.get(1), 1, testSignatures.get(1));
        var thresholdResult = accumulator.accumulate(testMembers.get(2), 2, testSignatures.get(2));

        assertThat(thresholdResult).isInstanceOf(AccumulationResult.ThresholdMet.class);

        // Try to add late signers
        var late1 = accumulator.accumulate(testMembers.get(3), 3, testSignatures.get(3));
        var late2 = accumulator.accumulate(testMembers.get(4), 4, testSignatures.get(4));
        var late3 = accumulator.accumulate(testMembers.get(5), 5, testSignatures.get(5));

        // All should be rejected as LateSigner
        assertThat(late1).isInstanceOf(AccumulationResult.LateSigner.class);
        assertThat(late2).isInstanceOf(AccumulationResult.LateSigner.class);
        assertThat(late3).isInstanceOf(AccumulationResult.LateSigner.class);

        // Verify LateSigner details
        var lateSigner = (AccumulationResult.LateSigner) late1;
        assertThat(lateSigner.member()).isEqualTo(testMembers.get(3));
        assertThat(lateSigner.thresholdReached()).isEqualTo(3);
        assertThat(lateSigner.thresholdReachedAt()).isNotNull();

        // Count should remain at threshold
        assertThat(accumulator.signerCount()).isEqualTo(3);
    }

    @Test
    void lateSignerMetricsTracked() {
        var accumulator = new SignatureAccumulator(testEvent, 2, 0, null);

        // Reach threshold
        accumulator.accumulate(testMembers.get(0), 0, testSignatures.get(0));
        accumulator.accumulate(testMembers.get(1), 1, testSignatures.get(1));

        // Metrics before late signers
        var metrics1 = accumulator.getMetrics();
        assertThat(metrics1.lateSigners()).isEqualTo(0);

        // Add late signers
        accumulator.accumulate(testMembers.get(2), 2, testSignatures.get(2));
        accumulator.accumulate(testMembers.get(3), 3, testSignatures.get(3));
        accumulator.accumulate(testMembers.get(4), 4, testSignatures.get(4));

        // Metrics after late signers
        var metrics2 = accumulator.getMetrics();
        assertThat(metrics2.lateSigners()).isEqualTo(3);
        assertThat(metrics2.currentSignerCount()).isEqualTo(2); // Still at threshold
        assertThat(metrics2.thresholdMet()).isTrue();
    }

    // ========== Concurrent Late Arrivals ==========

    @Test
    void concurrentLateArrivals_NoLeaks() throws Exception {
        var accumulator = new SignatureAccumulator(testEvent, 5, 0, null);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var latch = new CountDownLatch(50);
        var lateCount = new AtomicInteger(0);

        // First, reach threshold with 5 signatures
        for (int i = 0; i < 5; i++) {
            accumulator.accumulate(testMembers.get(i), i, testSignatures.get(i));
        }

        assertThat(accumulator.isThresholdMet()).isTrue();

        // Now submit 50 concurrent late arrivals
        for (int i = 5; i < 55; i++) {
            final var index = i;
            executor.submit(() -> {
                try {
                    var result = accumulator.accumulate(
                        testMembers.get(index),
                        index,
                        testSignatures.get(index)
                    );
                    if (result instanceof AccumulationResult.LateSigner) {
                        lateCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // All 50 should be rejected as late signers
        assertThat(lateCount.get()).isEqualTo(50);
        assertThat(accumulator.signerCount()).isEqualTo(5);
        assertThat(accumulator.getMetrics().lateSigners()).isEqualTo(50);
    }

    @Test
    void raceCondition_ThresholdAndLateSigner() throws Exception {
        // This tests the race where multiple threads arrive at exactly the threshold moment
        var accumulator = new SignatureAccumulator(testEvent, 10, 0, null);
        var executor = Executors.newFixedThreadPool(20);
        var latch = new CountDownLatch(20);
        var thresholdMetCount = new AtomicInteger(0);
        var accumulatedCount = new AtomicInteger(0);
        var lateSignerCount = new AtomicInteger(0);

        // Submit 20 threads concurrently (threshold is 10)
        for (int i = 0; i < 20; i++) {
            final var index = i;
            executor.submit(() -> {
                try {
                    var result = accumulator.accumulate(
                        testMembers.get(index),
                        index,
                        testSignatures.get(index)
                    );
                    if (result instanceof AccumulationResult.ThresholdMet) {
                        thresholdMetCount.incrementAndGet();
                    } else if (result instanceof AccumulationResult.Accumulated) {
                        accumulatedCount.incrementAndGet();
                    } else if (result instanceof AccumulationResult.LateSigner) {
                        lateSignerCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // Exactly one thread should have received ThresholdMet
        assertThat(thresholdMetCount.get()).isEqualTo(1);

        // Some threads might have snuck in before threshold check (race window)
        // But total accumulated should be >= threshold
        var finalCount = accumulator.signerCount();
        assertThat(finalCount).isGreaterThanOrEqualTo(10);

        // Late signers should be rejected
        assertThat(lateSignerCount.get()).isGreaterThan(0);

        // Verify accounting: thresholdMet (1) + accumulated + lateSigner = 20
        assertThat(thresholdMetCount.get() + accumulatedCount.get() + lateSignerCount.get()).isEqualTo(20);
    }

    // ========== Memory Safety ==========

    @Test
    void lateSignersNotStoredInMemory() {
        var accumulator = new SignatureAccumulator(testEvent, 3, 0, null);

        // Reach threshold
        accumulator.accumulate(testMembers.get(0), 0, testSignatures.get(0));
        accumulator.accumulate(testMembers.get(1), 1, testSignatures.get(1));
        accumulator.accumulate(testMembers.get(2), 2, testSignatures.get(2));

        // Add 10 late signers
        for (int i = 3; i < 13; i++) {
            var result = accumulator.accumulate(testMembers.get(i), i, testSignatures.get(i));
            assertThat(result).isInstanceOf(AccumulationResult.LateSigner.class);
        }

        // Verify memory: snapshot should only contain threshold signatures
        var snapshot = accumulator.snapshot();
        assertThat(snapshot.signerCount()).isEqualTo(3);
        assertThat(snapshot.signatures()).hasSize(3);

        // Late signers should NOT be in snapshot
        for (int i = 3; i < 13; i++) {
            assertThat(snapshot.signatures()).doesNotContainKey(testMembers.get(i));
        }

        // Metrics should track rejections without storing signatures
        assertThat(accumulator.getMetrics().lateSigners()).isEqualTo(10);
    }

    @Test
    void lateSignerCheckHappensBeforeDuplicateCheck() {
        // This verifies the fail-fast optimization: late signer check is O(1) and happens first
        var accumulator = new SignatureAccumulator(testEvent, 2, 0, null);

        // Reach threshold
        accumulator.accumulate(testMembers.get(0), 0, testSignatures.get(0));
        accumulator.accumulate(testMembers.get(1), 1, testSignatures.get(1));

        // Try to add member 0 again after threshold
        var result = accumulator.accumulate(testMembers.get(0), 0, testSignatures.get(0));

        // Should be rejected as LateSigner, NOT AlreadyPresent
        // This proves late signer check happens first
        assertThat(result).isInstanceOf(AccumulationResult.LateSigner.class);

        // Metrics should show late signer, not duplicate
        assertThat(accumulator.getMetrics().lateSigners()).isEqualTo(1);
    }

    // ========== Virtual Thread Compatibility ==========

    @Test
    void virtualThreads_NoPinning() throws Exception {
        // This test verifies no thread pinning occurs (run with -Djdk.tracePinnedThreads=full to verify)
        var accumulator = new SignatureAccumulator(testEvent, 20, 0, null);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var latch = new CountDownLatch(50);
        var startTime = Instant.now();

        // Submit 50 concurrent accumulations with virtual threads
        for (int i = 0; i < 50; i++) {
            final var index = i;
            executor.submit(() -> {
                try {
                    accumulator.accumulate(
                        testMembers.get(index),
                        index,
                        testSignatures.get(index)
                    );
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        var duration = Duration.between(startTime, Instant.now());

        // Virtual threads should complete quickly (<100ms) with no pinning
        assertThat(duration).isLessThan(Duration.ofMillis(100));
        assertThat(accumulator.isThresholdMet()).isTrue();
    }

    // ========== Stress Testing ==========

    @Test
    void stressTest_50Threads_200AccumulationsEach() throws Exception {
        // 50 threads × 200 accumulations = 10,000 total operations
        // Strategy: Each accumulator gets signatures from different threads to reach threshold
        var threadCount = 50;
        var accumulationsPerThread = 200;
        var totalOperations = threadCount * accumulationsPerThread;
        var numAccumulators = 200; // Same as accumulationsPerThread
        var threshold = 10; // Each accumulator needs 10 signatures

        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var latch = new CountDownLatch(totalOperations);
        var startTime = Instant.now();

        var successCount = new AtomicLong(0);
        var errorCount = new AtomicLong(0);

        // Create 200 accumulators (each with threshold 10)
        var accumulators = new ArrayList<SignatureAccumulator>();
        for (int i = 0; i < numAccumulators; i++) {
            var eventDigest = ALGORITHM.digest(("stress-event-" + i).getBytes());
            var event = new EventCoordinates(
                new SelfAddressingIdentifier(ALGORITHM.digest("stress-id".getBytes())),
                ULong.valueOf(i),
                eventDigest,
                "icp"
            );
            accumulators.add(new SignatureAccumulator(event, threshold, 0, null));
        }

        // Submit stress test workload
        // Each thread contributes 1 signature to each accumulator (200 accumulators)
        for (int threadId = 0; threadId < threadCount; threadId++) {
            final var tid = threadId;
            executor.submit(() -> {
                for (int accIndex = 0; accIndex < accumulationsPerThread; accIndex++) {
                    try {
                        var accumulator = accumulators.get(accIndex);

                        // Each thread uses its own member ID
                        var memberIndex = tid % testMembers.size();
                        accumulator.accumulate(
                            testMembers.get(memberIndex),
                            memberIndex,
                            testSignatures.get(memberIndex)
                        );
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }

        assertThat(latch.await(30, TimeUnit.SECONDS))
            .as("Stress test should complete within 30 seconds")
            .isTrue();

        executor.shutdown();

        var duration = Duration.between(startTime, Instant.now());

        // Performance assertions
        assertThat(duration).isLessThan(Duration.ofSeconds(30));
        assertThat(successCount.get()).isEqualTo(totalOperations);
        assertThat(errorCount.get()).isEqualTo(0);

        // Verify all accumulators reached threshold
        // With 50 threads and threshold 10, all 200 accumulators should reach threshold
        var thresholdMetCount = accumulators.stream()
            .filter(SignatureAccumulator::isThresholdMet)
            .count();
        assertThat(thresholdMetCount).isEqualTo(numAccumulators); // All should reach threshold
    }

    // ========== Late Signer Timestamp Accuracy ==========

    @Test
    void lateSignerTimestampIsAccurate() throws Exception {
        var accumulator = new SignatureAccumulator(testEvent, 3, 0, null);

        // Reach threshold
        var beforeThreshold = Instant.now();
        accumulator.accumulate(testMembers.get(0), 0, testSignatures.get(0));
        accumulator.accumulate(testMembers.get(1), 1, testSignatures.get(1));
        accumulator.accumulate(testMembers.get(2), 2, testSignatures.get(2));
        var afterThreshold = Instant.now();

        // Small delay to ensure timestamp difference
        Thread.sleep(10);

        // Add late signer
        var beforeLateSigner = Instant.now();
        var result = accumulator.accumulate(testMembers.get(3), 3, testSignatures.get(3));
        var afterLateSigner = Instant.now();

        assertThat(result).isInstanceOf(AccumulationResult.LateSigner.class);
        var lateSigner = (AccumulationResult.LateSigner) result;

        // Threshold timestamp should be between beforeThreshold and afterThreshold
        assertThat(lateSigner.thresholdReachedAt())
            .isAfterOrEqualTo(beforeThreshold)
            .isBeforeOrEqualTo(afterThreshold);

        // Threshold timestamp should be BEFORE late signer was rejected
        assertThat(lateSigner.thresholdReachedAt()).isBefore(beforeLateSigner);
    }

    @Test
    void lateSignerCounterIncrements() throws Exception {
        var accumulator = new SignatureAccumulator(testEvent, 2, 0, null);

        // Reach threshold
        accumulator.accumulate(testMembers.get(0), 0, testSignatures.get(0));
        accumulator.accumulate(testMembers.get(1), 1, testSignatures.get(1));

        // Add late signers one by one, checking counter increments
        for (int i = 2; i < 10; i++) {
            accumulator.accumulate(testMembers.get(i), i, testSignatures.get(i));
            var metrics = accumulator.getMetrics();
            assertThat(metrics.lateSigners()).isEqualTo(i - 1); // i-1 because we start at index 2
        }
    }

    // ========== Late Signer Performance ==========

    @Test
    void lateSignerRejectionPerformance() {
        var accumulator = new SignatureAccumulator(testEvent, 3, 0, null);

        // Reach threshold
        accumulator.accumulate(testMembers.get(0), 0, testSignatures.get(0));
        accumulator.accumulate(testMembers.get(1), 1, testSignatures.get(1));
        accumulator.accumulate(testMembers.get(2), 2, testSignatures.get(2));

        // Measure late signer rejection time
        var rejectionTimes = new ArrayList<Long>();
        for (int i = 3; i < 53; i++) { // 50 rejections
            var start = System.nanoTime();
            var result = accumulator.accumulate(testMembers.get(i), i, testSignatures.get(i));
            var duration = System.nanoTime() - start;

            assertThat(result).isInstanceOf(AccumulationResult.LateSigner.class);
            rejectionTimes.add(duration);
        }

        // Calculate average rejection time
        var avgNanos = rejectionTimes.stream()
            .mapToLong(Long::longValue)
            .average()
            .orElse(0.0);

        var avgMicros = avgNanos / 1000.0;

        // Should be <10 microseconds per rejection (requirement: <0.01ms = 10 microseconds)
        assertThat(avgMicros)
            .as("Average late signer rejection time")
            .isLessThan(10.0);
    }

    @Test
    void concurrentLateSignerRejectionPerformance() throws Exception {
        // Verify late signer rejection remains fast under concurrent load
        var accumulator = new SignatureAccumulator(testEvent, 5, 0, null);

        // Reach threshold
        for (int i = 0; i < 5; i++) {
            accumulator.accumulate(testMembers.get(i), i, testSignatures.get(i));
        }

        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var threadCount = 100;
        var rejectionsPerThread = 100;
        var totalRejections = threadCount * rejectionsPerThread;
        var latch = new CountDownLatch(totalRejections);
        var startTime = System.nanoTime();

        // 100 threads, each performing 100 late signer rejections
        for (int t = 0; t < threadCount; t++) {
            final var threadId = t;
            executor.submit(() -> {
                for (int op = 0; op < rejectionsPerThread; op++) {
                    try {
                        var index = 5 + threadId * rejectionsPerThread + op;
                        var memberIndex = index % testMembers.size();
                        var result = accumulator.accumulate(
                            testMembers.get(memberIndex),
                            index,
                            testSignatures.get(memberIndex)
                        );
                        assertThat(result).isInstanceOf(AccumulationResult.LateSigner.class);
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        var endTime = System.nanoTime();
        var avgNanosPerRejection = (endTime - startTime) / (double) totalRejections;
        var avgMicrosPerRejection = avgNanosPerRejection / 1000.0;

        // Even under concurrent load, should be <10 microseconds per rejection
        assertThat(avgMicrosPerRejection)
            .as("Average concurrent late signer rejection time (microseconds)")
            .isLessThan(10.0);

        // Verify metrics tracked correctly
        assertThat(accumulator.getMetrics().lateSigners()).isEqualTo(totalRejections);
        assertThat(accumulator.signerCount()).isEqualTo(5); // Still at threshold
    }
}
