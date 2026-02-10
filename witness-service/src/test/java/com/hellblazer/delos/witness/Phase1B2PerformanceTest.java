/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.witness.aggregation.AccumulationResult;
import com.hellblazer.delos.witness.aggregation.SignatureAccumulator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;

/**
 * Performance baseline tests for Phase 1B-2 fixes.
 * <p>
 * Tests measure the performance impact of:
 * - Epoch validation overhead (should be <0.001ms per check)
 * - Late signer rejection performance (should be <0.01ms per rejection)
 * - Concurrent accumulation throughput (10,000 accumulations should complete <30s)
 * </p>
 * <p>
 * These tests establish performance baselines to ensure the Phase 1B-2
 * correctness fixes (epoch/viewRef validation, late signer rejection) do not
 * introduce unacceptable performance regressions.
 * </p>
 */
class Phase1B2PerformanceTest {

    // CI environment detection for performance threshold adjustment
    private static final boolean IS_CI = "true".equalsIgnoreCase(System.getenv("CI"));
    private static final double CI_LATENCY_MULTIPLIER = IS_CI ? 2.0 : 1.0;

    private WitnessReceiptTestHelper testHelper;
    private SecureRandom entropy;
    private DigestAlgorithm digestAlgorithm;

    @BeforeEach
    void setUp() {
        testHelper = new WitnessReceiptTestHelper();
        entropy = new SecureRandom();
        digestAlgorithm = DigestAlgorithm.DEFAULT;
    }

    // ========================================
    // Category 1: Epoch Validation Overhead
    // ========================================

    @Test
    @DisplayName("Epoch validation overhead - <0.001ms per check")
    void testEpochValidationOverhead() {
        // Given: Accumulator with specific epoch
        var event = createTestEvent(0);
        var correctEpoch = 42L;
        var accumulator = new SignatureAccumulator(event, 5, correctEpoch, createTestViewRef(0));

        // Prepare test data: 1000 accumulations with correct epoch
        var testData = new ArrayList<TestAccumulationData>();
        for (int i = 0; i < 1000; i++) {
            var member = createTestMemberId(i);
            var signature = createTestBlsSignature(i);
            testData.add(new TestAccumulationData(member, i, signature, correctEpoch, createTestViewRef(0)));
        }

        // Warmup: 100 iterations
        var warmupAccumulator = new SignatureAccumulator(event, 1000, correctEpoch, createTestViewRef(0));
        for (int i = 0; i < 100; i++) {
            var data = testData.get(i);
            warmupAccumulator.accumulate(data.member, data.committeeIndex, data.signature, data.epoch, data.viewRef);
        }

        // When: Measure epoch validation time
        var startTime = System.nanoTime();
        for (var data : testData) {
            accumulator.accumulate(data.member, data.committeeIndex, data.signature, data.epoch, data.viewRef);
        }
        var endTime = System.nanoTime();

        // Then: Validation overhead should be <0.001ms per check
        var totalTimeMs = (endTime - startTime) / 1_000_000.0;
        var avgTimeMs = totalTimeMs / testData.size();

        assertThat(avgTimeMs)
            .describedAs("Average epoch validation time should be <0.001ms, actual: %.6fms", avgTimeMs)
            .isLessThan(0.001);

        System.out.printf("Epoch Validation Overhead: %d validations in %.2fms (avg: %.6fms per validation)%n",
                          testData.size(), totalTimeMs, avgTimeMs);
    }

    @Test
    @DisplayName("Epoch validation with mismatches - rejection overhead")
    void testEpochValidationWithMismatches() {
        // Given: Accumulator with specific epoch
        var event = createTestEvent(0);
        var correctEpoch = 42L;
        var wrongEpoch = 99L;
        var accumulator = new SignatureAccumulator(event, 5, correctEpoch, createTestViewRef(0));

        // Prepare test data: 1000 accumulations with wrong epoch
        var testData = new ArrayList<TestAccumulationData>();
        for (int i = 0; i < 1000; i++) {
            var member = createTestMemberId(i);
            var signature = createTestBlsSignature(i);
            testData.add(new TestAccumulationData(member, i, signature, wrongEpoch, createTestViewRef(0)));
        }

        // Warmup: 100 iterations
        for (int i = 0; i < 100; i++) {
            var data = testData.get(i);
            accumulator.accumulate(data.member, data.committeeIndex, data.signature, data.epoch, data.viewRef);
        }

        // When: Measure epoch mismatch rejection time
        var startTime = System.nanoTime();
        var rejectionCount = 0;
        for (var data : testData) {
            var result = accumulator.accumulate(data.member, data.committeeIndex, data.signature, data.epoch, data.viewRef);
            if (result instanceof AccumulationResult.EpochMismatch) {
                rejectionCount++;
            }
        }
        var endTime = System.nanoTime();

        // Then: All should be rejected quickly
        var totalTimeMs = (endTime - startTime) / 1_000_000.0;
        var avgTimeMs = totalTimeMs / testData.size();

        assertThat(rejectionCount).isEqualTo(testData.size());
        assertThat(avgTimeMs)
            .describedAs("Average epoch mismatch rejection should be <0.001ms, actual: %.6fms", avgTimeMs)
            .isLessThan(0.001);

        System.out.printf("Epoch Mismatch Rejection: %d rejections in %.2fms (avg: %.6fms per rejection)%n",
                          rejectionCount, totalTimeMs, avgTimeMs);
    }

    @Test
    @DisplayName("ViewRef validation overhead - <0.001ms per check")
    void testViewRefValidationOverhead() {
        // Given: Accumulator with specific viewRef
        var event = createTestEvent(0);
        var correctViewRef = createTestViewRef(42);
        var accumulator = new SignatureAccumulator(event, 5, 1L, correctViewRef);

        // Prepare test data: 1000 accumulations with correct viewRef
        var testData = new ArrayList<TestAccumulationData>();
        for (int i = 0; i < 1000; i++) {
            var member = createTestMemberId(i);
            var signature = createTestBlsSignature(i);
            testData.add(new TestAccumulationData(member, i, signature, 1L, correctViewRef));
        }

        // Warmup: 100 iterations
        var warmupAccumulator = new SignatureAccumulator(event, 1000, 1L, correctViewRef);
        for (int i = 0; i < 100; i++) {
            var data = testData.get(i);
            warmupAccumulator.accumulate(data.member, data.committeeIndex, data.signature, data.epoch, data.viewRef);
        }

        // When: Measure viewRef validation time
        var startTime = System.nanoTime();
        for (var data : testData) {
            accumulator.accumulate(data.member, data.committeeIndex, data.signature, data.epoch, data.viewRef);
        }
        var endTime = System.nanoTime();

        // Then: Validation overhead should be <0.001ms per check
        var totalTimeMs = (endTime - startTime) / 1_000_000.0;
        var avgTimeMs = totalTimeMs / testData.size();

        assertThat(avgTimeMs)
            .describedAs("Average viewRef validation time should be <0.001ms, actual: %.6fms", avgTimeMs)
            .isLessThan(0.001);

        System.out.printf("ViewRef Validation Overhead: %d validations in %.2fms (avg: %.6fms per validation)%n",
                          testData.size(), totalTimeMs, avgTimeMs);
    }

    // ========================================
    // Category 2: Late Signer Rejection Performance
    // ========================================

    @Test
    @DisplayName("Late signer rejection performance - 1000 accumulations to threshold, then 1000 late signers <0.01ms")
    void testLateSignerRejectionPerformance() {
        // Given: Accumulator with threshold of 5
        var event = createTestEvent(0);
        var threshold = 5;
        var epoch = 1L;
        var viewRef = createTestViewRef(0);
        var accumulator = new SignatureAccumulator(event, threshold, epoch, viewRef);

        // Step 1: Accumulate to threshold
        for (int i = 0; i < threshold; i++) {
            var member = createTestMemberId(i);
            var signature = createTestBlsSignature(i);
            var result = accumulator.accumulate(member, i, signature, epoch, viewRef);
            if (i == threshold - 1) {
                assertThat(result).isInstanceOf(AccumulationResult.ThresholdMet.class);
            }
        }

        assertThat(accumulator.isThresholdMet()).isTrue();

        // Step 2: Prepare 1000 late signers
        var lateSigners = new ArrayList<TestAccumulationData>();
        for (int i = threshold; i < threshold + 1000; i++) {
            var member = createTestMemberId(i);
            var signature = createTestBlsSignature(i);
            lateSigners.add(new TestAccumulationData(member, i, signature, epoch, viewRef));
        }

        // Warmup: 100 late signer rejections
        for (int i = 0; i < 100; i++) {
            var data = lateSigners.get(i);
            accumulator.accumulate(data.member, data.committeeIndex, data.signature, data.epoch, data.viewRef);
        }

        // When: Measure late signer rejection time
        var startTime = System.nanoTime();
        var rejectionCount = 0;
        for (var data : lateSigners) {
            var result = accumulator.accumulate(data.member, data.committeeIndex, data.signature, data.epoch, data.viewRef);
            if (result instanceof AccumulationResult.LateSigner) {
                rejectionCount++;
            }
        }
        var endTime = System.nanoTime();

        // Then: All late signers should be rejected quickly (<0.01ms each)
        var totalTimeMs = (endTime - startTime) / 1_000_000.0;
        var avgTimeMs = totalTimeMs / lateSigners.size();

        assertThat(rejectionCount).isEqualTo(lateSigners.size());
        assertThat(avgTimeMs)
            .describedAs("Average late signer rejection should be <0.01ms, actual: %.6fms", avgTimeMs)
            .isLessThan(0.01);

        System.out.printf("Late Signer Rejection: %d rejections in %.2fms (avg: %.6fms per rejection)%n",
                          rejectionCount, totalTimeMs, avgTimeMs);
    }

    @Test
    @DisplayName("Late signer rejection under concurrent load - fast fail-fast path")
    void testLateSignerRejectionUnderConcurrentLoad() throws InterruptedException {
        // Given: Accumulator that has already reached threshold
        var event = createTestEvent(0);
        var threshold = 5;
        var epoch = 1L;
        var viewRef = createTestViewRef(0);
        var accumulator = new SignatureAccumulator(event, threshold, epoch, viewRef);

        // Reach threshold
        for (int i = 0; i < threshold; i++) {
            var member = createTestMemberId(i);
            var signature = createTestBlsSignature(i);
            accumulator.accumulate(member, i, signature, epoch, viewRef);
        }

        assertThat(accumulator.isThresholdMet()).isTrue();

        // When: 10 threads each try to add 100 late signers concurrently
        var threadCount = 10;
        var signersPerThread = 100;
        var totalLateSIgners = threadCount * signersPerThread;

        var latch = new CountDownLatch(threadCount);
        var rejectionCount = new AtomicInteger(0);
        var startTime = System.nanoTime();

        var threads = IntStream.range(0, threadCount)
            .mapToObj(threadId -> new Thread(() -> {
                for (int i = 0; i < signersPerThread; i++) {
                    var signerIndex = threshold + threadId * signersPerThread + i;
                    var member = createTestMemberId(signerIndex);
                    var signature = createTestBlsSignature(signerIndex);
                    var result = accumulator.accumulate(member, signerIndex, signature, epoch, viewRef);
                    if (result instanceof AccumulationResult.LateSigner) {
                        rejectionCount.incrementAndGet();
                    }
                }
                latch.countDown();
            }))
            .peek(Thread::start)
            .toList();

        // Then: All threads complete quickly
        assertThat(latch.await(10, TimeUnit.SECONDS))
            .describedAs("All threads should complete within timeout")
            .isTrue();

        var endTime = System.nanoTime();
        var totalTimeMs = (endTime - startTime) / 1_000_000.0;
        var avgTimeMs = totalTimeMs / totalLateSIgners;

        assertThat(rejectionCount.get()).isEqualTo(totalLateSIgners);
        assertThat(avgTimeMs)
            .describedAs("Concurrent late signer rejection avg should be <0.1ms (100 microseconds, 200 microseconds on CI), actual: %.6fms", avgTimeMs)
            .isLessThan(0.1 * CI_LATENCY_MULTIPLIER);

        System.out.printf("Concurrent Late Signer Rejection: %d rejections on %d threads in %.2fms%n",
                          totalLateSIgners, threadCount, totalTimeMs);
    }

    // ========================================
    // Category 3: Concurrent Accumulation Throughput
    // ========================================

    @Test
    @DisplayName("Concurrent accumulation throughput - 10,000 concurrent accumulations <30s")
    void testConcurrentAccumulationThroughput() throws InterruptedException {
        // Given: 50 threads, 200 accumulations each (10,000 total)
        var threadCount = 50;
        var accumulationsPerThread = 200;
        var totalAccumulations = threadCount * accumulationsPerThread;

        // Create accumulators for each thread (distinct events to avoid contention)
        var accumulators = new ArrayList<SignatureAccumulator>();
        for (int i = 0; i < threadCount; i++) {
            var event = createTestEvent(i);
            var accumulator = new SignatureAccumulator(event, accumulationsPerThread, 1L, createTestViewRef(i));
            accumulators.add(accumulator);
        }

        var latch = new CountDownLatch(threadCount);
        var successCount = new AtomicInteger(0);

        // Warmup: 100 accumulations per thread
        for (int threadId = 0; threadId < threadCount; threadId++) {
            var warmupAccumulator = accumulators.get(threadId);
            var warmupEvent = createTestEvent(1000 + threadId); // Distinct from test events
            var warmupAcc = new SignatureAccumulator(warmupEvent, 100, 1L, createTestViewRef(1000 + threadId));
            for (int i = 0; i < 100; i++) {
                var member = createTestMemberId(10000 + threadId * 100 + i);
                var signature = createTestBlsSignature(10000 + threadId * 100 + i);
                warmupAcc.accumulate(member, i, signature, 1L, createTestViewRef(1000 + threadId));
            }
        }

        // When: Accumulate concurrently from multiple threads
        var startTime = System.nanoTime();

        var threads = IntStream.range(0, threadCount)
            .mapToObj(threadId -> new Thread(() -> {
                try {
                    var accumulator = accumulators.get(threadId);
                    var epoch = 1L;
                    var viewRef = createTestViewRef(threadId);

                    for (int i = 0; i < accumulationsPerThread; i++) {
                        var signerIndex = threadId * accumulationsPerThread + i;
                        var member = createTestMemberId(signerIndex);
                        var signature = createTestBlsSignature(signerIndex);
                        var result = accumulator.accumulate(member, i, signature, epoch, viewRef);

                        if (result.isSuccess()) {
                            successCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            }))
            .peek(Thread::start)
            .toList();

        // Then: All accumulations complete within 30s
        assertThat(latch.await(30, TimeUnit.SECONDS))
            .describedAs("All threads should complete within 30s timeout")
            .isTrue();

        var endTime = System.nanoTime();
        var totalTimeMs = (endTime - startTime) / 1_000_000.0;
        var throughputPerSec = (totalAccumulations / totalTimeMs) * 1000;

        assertThat(successCount.get()).isEqualTo(totalAccumulations);
        assertThat(totalTimeMs)
            .describedAs("Total time should be <30s (30000ms), actual: %.2fms", totalTimeMs)
            .isLessThan(30000.0);

        System.out.printf("Concurrent Accumulation Throughput: %d accumulations on %d threads in %.2fms (%.0f ops/sec)%n",
                          totalAccumulations, threadCount, totalTimeMs, throughputPerSec);
    }

    @Test
    @DisplayName("Concurrent accumulation with validation failures - mixed workload")
    void testConcurrentAccumulationWithValidationFailures() throws InterruptedException {
        // Given: 10 threads, each with a mix of valid and invalid accumulations
        var threadCount = 10;
        var accumulationsPerThread = 100;
        var totalAccumulations = threadCount * accumulationsPerThread;

        var event = createTestEvent(0);
        var correctEpoch = 1L;
        var correctViewRef = createTestViewRef(0);
        var accumulator = new SignatureAccumulator(event, totalAccumulations, correctEpoch, correctViewRef);

        var latch = new CountDownLatch(threadCount);
        var successCount = new AtomicInteger(0);
        var epochMismatchCount = new AtomicInteger(0);
        var viewRefMismatchCount = new AtomicInteger(0);

        var startTime = System.nanoTime();

        // When: Each thread submits mix of valid (50%), wrong epoch (25%), wrong viewRef (25%)
        var threads = IntStream.range(0, threadCount)
            .mapToObj(threadId -> new Thread(() -> {
                try {
                    for (int i = 0; i < accumulationsPerThread; i++) {
                        var signerIndex = threadId * accumulationsPerThread + i;
                        var member = createTestMemberId(signerIndex);
                        var signature = createTestBlsSignature(signerIndex);

                        // Mix validation cases
                        var epoch = (i % 4 == 1) ? 99L : correctEpoch; // 25% wrong epoch
                        var viewRef = (i % 4 == 2) ? createTestViewRef(99) : correctViewRef; // 25% wrong viewRef

                        var result = accumulator.accumulate(member, signerIndex, signature, epoch, viewRef);

                        if (result instanceof AccumulationResult.Accumulated || result instanceof AccumulationResult.ThresholdMet) {
                            successCount.incrementAndGet();
                        } else if (result instanceof AccumulationResult.EpochMismatch) {
                            epochMismatchCount.incrementAndGet();
                        } else if (result instanceof AccumulationResult.ViewRefMismatch) {
                            viewRefMismatchCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            }))
            .peek(Thread::start)
            .toList();

        // Then: All threads complete quickly despite validation failures
        assertThat(latch.await(10, TimeUnit.SECONDS))
            .describedAs("All threads should complete within timeout")
            .isTrue();

        var endTime = System.nanoTime();
        var totalTimeMs = (endTime - startTime) / 1_000_000.0;

        // Verify expected distribution
        var expectedEpochMismatches = totalAccumulations / 4; // 25%
        var expectedViewRefMismatches = totalAccumulations / 4; // 25%
        var expectedSuccesses = totalAccumulations / 2; // 50%

        assertThat(epochMismatchCount.get()).isCloseTo(expectedEpochMismatches, within(50));
        assertThat(viewRefMismatchCount.get()).isCloseTo(expectedViewRefMismatches, within(50));
        assertThat(successCount.get()).isCloseTo(expectedSuccesses, within(50));

        System.out.printf("Concurrent Mixed Workload: %d total (%d success, %d epoch mismatch, %d viewRef mismatch) in %.2fms%n",
                          totalAccumulations, successCount.get(), epochMismatchCount.get(),
                          viewRefMismatchCount.get(), totalTimeMs);
    }

    // ========================================
    // Test Helpers
    // ========================================

    private EventCoordinates createTestEvent(int index) {
        var digest = digestAlgorithm.digest(("event-" + index).getBytes());
        var identifier = createTestMemberId(index);
        return new EventCoordinates(identifier, org.joou.ULong.valueOf(index), digest, "icp");
    }

    private Identifier createTestMemberId(int index) {
        return testHelper.createTestSigner("member-" + index).getIdentifier();
    }

    private com.hellblazer.delos.cryptography.Digest createTestViewRef(int index) {
        return digestAlgorithm.digest(("viewRef-" + index).getBytes());
    }

    private BLSSignature createTestBlsSignature(int index) {
        // Create 96-byte BLS signature (G2 point compressed)
        var signatureBytes = new byte[BLSSignature.COMPRESSED_SIZE];
        entropy.nextBytes(signatureBytes);
        // Ensure first byte has valid BLS12-381 G2 point compression flag
        signatureBytes[0] = (byte) (signatureBytes[0] | 0x80); // Set compression flag
        return new BLSSignature(signatureBytes);
    }

    /**
     * Helper record for organizing test accumulation data.
     */
    private record TestAccumulationData(
        Identifier member,
        int committeeIndex,
        BLSSignature signature,
        long epoch,
        com.hellblazer.delos.cryptography.Digest viewRef
    ) {}
}
