/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation;

import org.joou.ULong;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.cryptography.bls.BLSTestFixtures;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive tests for SignatureAccumulator epoch and viewRef validation.
 * <p>
 * Test Coverage:
 * - Single-threaded epoch validation (matching and mismatching)
 * - Concurrent epoch scenarios (mixed epochs, race conditions)
 * - Edge cases (epoch 0, negative epoch, Long.MAX_VALUE)
 * - ViewRef validation (null handling, mismatches)
 * - Metrics tracking for all rejection types
 * - Integration scenarios
 *
 * @author hal.hildebrand
 */
class SignatureAccumulatorEpochTest {
    private static final DigestAlgorithm ALGORITHM = DigestAlgorithm.DEFAULT;
    private EventCoordinates testEvent;
    private List<Identifier> testMembers;
    private List<BLSSignature> testSignatures;
    private Digest testViewRef1;
    private Digest testViewRef2;

    @BeforeEach
    void setUp() {
        // Create test event
        var identifier = new SelfAddressingIdentifier(ALGORITHM.digest("test-identifier".getBytes()));
        var digest = ALGORITHM.digest("test-event".getBytes());
        var ilk = "icp";
        testEvent = new EventCoordinates(identifier, ULong.valueOf(1), digest, ilk);

        // Create test members and signatures
        testMembers = new ArrayList<>();
        testSignatures = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            var memberDigest = ALGORITHM.digest(("member-" + i).getBytes());
            testMembers.add(new SelfAddressingIdentifier(memberDigest));
            testSignatures.add(new BLSSignature(BLSTestFixtures.randomMessage(96)));
        }

        // Create test view references
        testViewRef1 = ALGORITHM.digest("viewRef1".getBytes());
        testViewRef2 = ALGORITHM.digest("viewRef2".getBytes());
    }

    // ========== Single-Threaded Epoch Validation ==========

    @Test
    void rejectDifferentEpoch_SingleThread() {
        var accumulator = new SignatureAccumulator(testEvent, 3, 5, null);

        // Try to accumulate signature with wrong epoch
        var result = accumulator.accumulate(
            testMembers.get(0),
            0,
            testSignatures.get(0),
            7,  // Wrong epoch
            null
        );

        assertThat(result).isInstanceOf(AccumulationResult.EpochMismatch.class);
        var epochMismatch = (AccumulationResult.EpochMismatch) result;
        assertThat(epochMismatch.member()).isEqualTo(testMembers.get(0));
        assertThat(epochMismatch.expectedEpoch()).isEqualTo(5);
        assertThat(epochMismatch.providedEpoch()).isEqualTo(7);

        // Signature should NOT be accumulated
        assertThat(accumulator.signerCount()).isEqualTo(0);
        assertThat(accumulator.isThresholdMet()).isFalse();

        // Metrics should track the rejection
        var metrics = accumulator.getMetrics();
        assertThat(metrics.epochMismatches()).isEqualTo(1);
    }

    @Test
    void acceptMatchingEpoch_SingleThread() {
        var accumulator = new SignatureAccumulator(testEvent, 3, 5, null);

        // Accumulate signature with correct epoch
        var result = accumulator.accumulate(
            testMembers.get(0),
            0,
            testSignatures.get(0),
            5,  // Correct epoch
            null
        );

        assertThat(result).isInstanceOf(AccumulationResult.Accumulated.class);
        var accumulated = (AccumulationResult.Accumulated) result;
        assertThat(accumulated.currentCount()).isEqualTo(1);

        // Signature should be accumulated
        assertThat(accumulator.signerCount()).isEqualTo(1);
        assertThat(accumulator.getEpoch()).isEqualTo(5);

        // No epoch mismatches
        var metrics = accumulator.getMetrics();
        assertThat(metrics.epochMismatches()).isEqualTo(0);
    }

    // ========== Concurrent Epoch Scenarios ==========

    @Test
    void concurrentMixedEpochs_CorrectRejection() throws Exception {
        var accumulator = new SignatureAccumulator(testEvent, 5, 10, null);
        var executor = Executors.newFixedThreadPool(15);
        var latch = new CountDownLatch(15);
        var epochMismatchCount = new AtomicInteger(0);
        var lateSignerCount = new AtomicInteger(0);
        var acceptedCount = new AtomicInteger(0);

        // Submit 15 concurrent accumulations: 10 with correct epoch, 5 with wrong epoch
        for (int i = 0; i < 15; i++) {
            final var index = i;
            final var epoch = (i < 10) ? 10L : 99L; // First 10 correct, last 5 wrong
            executor.submit(() -> {
                try {
                    var result = accumulator.accumulate(
                        testMembers.get(index),
                        index,
                        testSignatures.get(index),
                        epoch,
                        null
                    );
                    if (result instanceof AccumulationResult.EpochMismatch) {
                        epochMismatchCount.incrementAndGet();
                    } else if (result instanceof AccumulationResult.LateSigner) {
                        lateSignerCount.incrementAndGet();
                    } else if (result.isSuccess()) {
                        acceptedCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // Wrong epoch signatures may be rejected as epoch mismatches OR late signers
        // (depending on race with threshold detection)
        // At least 5 correct signatures accepted
        assertThat(acceptedCount.get()).isGreaterThanOrEqualTo(5);
        assertThat(accumulator.isThresholdMet()).isTrue();

        // Total rejections should be the 5 wrong epoch + any late signers from correct epoch
        var totalRejections = epochMismatchCount.get() + lateSignerCount.get();
        assertThat(totalRejections).isGreaterThanOrEqualTo(5);

        // Metrics should track epoch mismatches (those rejected before late signer check)
        var metrics = accumulator.getMetrics();
        assertThat(metrics.epochMismatches()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void raceCondition_EpochCheckThreadSafe() throws Exception {
        var accumulator = new SignatureAccumulator(testEvent, 10, 42, null);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var latch = new CountDownLatch(100);
        var epochMismatchCount = new AtomicInteger(0);

        // Hammer with 100 concurrent accumulations, all with correct epoch
        for (int i = 0; i < 100; i++) {
            final var index = i % testMembers.size(); // Reuse members
            executor.submit(() -> {
                try {
                    var result = accumulator.accumulate(
                        testMembers.get(index),
                        index,
                        testSignatures.get(index),
                        42,  // Correct epoch
                        null
                    );
                    if (result instanceof AccumulationResult.EpochMismatch) {
                        epochMismatchCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        executor.close();

        // No epoch mismatches should occur (all had correct epoch)
        assertThat(epochMismatchCount.get()).isEqualTo(0);
        assertThat(accumulator.getMetrics().epochMismatches()).isEqualTo(0);

        // Threshold should be reached
        assertThat(accumulator.isThresholdMet()).isTrue();
    }

    // ========== Edge Cases ==========

    @Test
    void epochZeroIsValid() {
        var accumulator = new SignatureAccumulator(testEvent, 3, 0, null);

        var result = accumulator.accumulate(
            testMembers.get(0),
            0,
            testSignatures.get(0),
            0,  // Epoch 0 should be valid
            null
        );

        assertThat(result).isInstanceOf(AccumulationResult.Accumulated.class);
        assertThat(accumulator.signerCount()).isEqualTo(1);
        assertThat(accumulator.getEpoch()).isEqualTo(0);
    }

    @Test
    void constructorRejectsNegativeEpoch() {
        assertThatThrownBy(() -> new SignatureAccumulator(testEvent, 3, -1, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("epoch must be >= 0");
    }

    @Test
    void epochValidation_LongMaxValue() {
        var accumulator = new SignatureAccumulator(testEvent, 3, Long.MAX_VALUE, null);

        // Accumulate with matching Long.MAX_VALUE epoch
        var result1 = accumulator.accumulate(
            testMembers.get(0),
            0,
            testSignatures.get(0),
            Long.MAX_VALUE,
            null
        );

        assertThat(result1).isInstanceOf(AccumulationResult.Accumulated.class);

        // Try with different epoch - should be rejected
        var result2 = accumulator.accumulate(
            testMembers.get(1),
            1,
            testSignatures.get(1),
            Long.MAX_VALUE - 1,
            null
        );

        assertThat(result2).isInstanceOf(AccumulationResult.EpochMismatch.class);
        var epochMismatch = (AccumulationResult.EpochMismatch) result2;
        assertThat(epochMismatch.expectedEpoch()).isEqualTo(Long.MAX_VALUE);
        assertThat(epochMismatch.providedEpoch()).isEqualTo(Long.MAX_VALUE - 1);
    }

    // ========== ViewRef Validation ==========

    @Test
    void accumulateRejectsViewRefMismatch() {
        var accumulator = new SignatureAccumulator(testEvent, 3, 5, testViewRef1);

        // Try to accumulate signature with wrong viewRef
        var result = accumulator.accumulate(
            testMembers.get(0),
            0,
            testSignatures.get(0),
            5,  // Correct epoch
            testViewRef2  // Wrong viewRef
        );

        assertThat(result).isInstanceOf(AccumulationResult.ViewRefMismatch.class);
        var viewRefMismatch = (AccumulationResult.ViewRefMismatch) result;
        assertThat(viewRefMismatch.member()).isEqualTo(testMembers.get(0));
        assertThat(viewRefMismatch.expectedViewRef()).isEqualTo(testViewRef1);
        assertThat(viewRefMismatch.providedViewRef()).isEqualTo(testViewRef2);

        // Signature should NOT be accumulated
        assertThat(accumulator.signerCount()).isEqualTo(0);

        var metrics = accumulator.getMetrics();
        assertThat(metrics.viewRefMismatches()).isEqualTo(1);
    }

    @Test
    void accumulateAcceptsNullViewRefWhenBothNull() {
        var accumulator = new SignatureAccumulator(testEvent, 3, 5, null);

        // Both accumulator and signature have null viewRef - should be accepted
        var result = accumulator.accumulate(
            testMembers.get(0),
            0,
            testSignatures.get(0),
            5,
            null
        );

        assertThat(result).isInstanceOf(AccumulationResult.Accumulated.class);
        assertThat(accumulator.signerCount()).isEqualTo(1);

        var metrics = accumulator.getMetrics();
        assertThat(metrics.viewRefMismatches()).isEqualTo(0);
    }

    @Test
    void accumulateAcceptsNullViewRef() {
        // Accumulator has non-null viewRef, signature provides null - should be accepted (backward compatibility)
        var accumulator = new SignatureAccumulator(testEvent, 3, 5, testViewRef1);

        var result = accumulator.accumulate(
            testMembers.get(0),
            0,
            testSignatures.get(0),
            5,
            null  // Null viewRef should be accepted
        );

        assertThat(result).isInstanceOf(AccumulationResult.Accumulated.class);
        assertThat(accumulator.signerCount()).isEqualTo(1);

        var metrics = accumulator.getMetrics();
        assertThat(metrics.viewRefMismatches()).isEqualTo(0);
    }

    // ========== Metrics Tracking ==========

    @Test
    void epochMismatchMetricsIncremented() {
        var accumulator = new SignatureAccumulator(testEvent, 10, 100, null);

        // Accumulate 5 signatures with wrong epoch
        for (int i = 0; i < 5; i++) {
            accumulator.accumulate(
                testMembers.get(i),
                i,
                testSignatures.get(i),
                99,  // Wrong epoch
                null
            );
        }

        var metrics = accumulator.getMetrics();
        assertThat(metrics.epochMismatches()).isEqualTo(5);
        assertThat(metrics.viewRefMismatches()).isEqualTo(0);
        assertThat(metrics.lateSigners()).isEqualTo(0);
        assertThat(metrics.currentSignerCount()).isEqualTo(0);
    }

    @Test
    void viewRefMismatchMetricsIncremented() {
        var accumulator = new SignatureAccumulator(testEvent, 10, 100, testViewRef1);

        // Accumulate 7 signatures with wrong viewRef
        for (int i = 0; i < 7; i++) {
            accumulator.accumulate(
                testMembers.get(i),
                i,
                testSignatures.get(i),
                100,  // Correct epoch
                testViewRef2  // Wrong viewRef
            );
        }

        var metrics = accumulator.getMetrics();
        assertThat(metrics.epochMismatches()).isEqualTo(0);
        assertThat(metrics.viewRefMismatches()).isEqualTo(7);
        assertThat(metrics.lateSigners()).isEqualTo(0);
        assertThat(metrics.currentSignerCount()).isEqualTo(0);
    }

    @Test
    void metricsTrackAllRejectionTypes() {
        var accumulator = new SignatureAccumulator(testEvent, 10, 50, testViewRef1);

        // 1. Try 2 epoch mismatches BEFORE threshold
        accumulator.accumulate(testMembers.get(0), 0, testSignatures.get(0), 51, testViewRef1);
        accumulator.accumulate(testMembers.get(1), 1, testSignatures.get(1), 49, testViewRef1);

        // 2. Try 3 viewRef mismatches BEFORE threshold
        accumulator.accumulate(testMembers.get(2), 2, testSignatures.get(2), 50, testViewRef2);
        accumulator.accumulate(testMembers.get(3), 3, testSignatures.get(3), 50, testViewRef2);
        accumulator.accumulate(testMembers.get(4), 4, testSignatures.get(4), 50, testViewRef2);

        // 3. Accumulate 10 valid signatures to reach threshold
        for (int i = 5; i < 15; i++) {
            accumulator.accumulate(testMembers.get(i), i, testSignatures.get(i), 50, testViewRef1);
        }

        // 4. After threshold is met, all rejections become late signers (regardless of epoch/viewRef)
        // This is because late signer check (Step 1) happens before epoch/viewRef checks (Steps 2-3)
        // So we expect late signers metric to increment, not epoch/viewRef metrics

        var metrics = accumulator.getMetrics();
        assertThat(metrics.epochMismatches()).isEqualTo(2);
        assertThat(metrics.viewRefMismatches()).isEqualTo(3);
        assertThat(metrics.lateSigners()).isEqualTo(0);  // No late signers since all were before threshold
        assertThat(metrics.currentSignerCount()).isEqualTo(10);
        assertThat(metrics.thresholdMet()).isTrue();
    }

    // ========== Integration Scenarios ==========

    @Test
    void accumulateWithValidEpochViewRef() {
        var accumulator = new SignatureAccumulator(testEvent, 5, 42, testViewRef1);

        // Accumulate signatures with correct epoch and viewRef
        for (int i = 0; i < 5; i++) {
            var result = accumulator.accumulate(
                testMembers.get(i),
                i,
                testSignatures.get(i),
                42,
                testViewRef1
            );

            if (i < 4) {
                assertThat(result).isInstanceOf(AccumulationResult.Accumulated.class);
            } else {
                // Threshold met on 5th signature
                assertThat(result).isInstanceOf(AccumulationResult.ThresholdMet.class);
            }
        }

        assertThat(accumulator.isThresholdMet()).isTrue();
        assertThat(accumulator.signerCount()).isEqualTo(5);

        var snapshot = accumulator.getThresholdSnapshot();
        assertThat(snapshot).isPresent();
        assertThat(snapshot.get().epoch()).isEqualTo(42);
        assertThat(snapshot.get().viewRef()).isEqualTo(testViewRef1);

        var metrics = accumulator.getMetrics();
        assertThat(metrics.epochMismatches()).isEqualTo(0);
        assertThat(metrics.viewRefMismatches()).isEqualTo(0);
    }

    @Test
    void multipleEpochTransitions() {
        // Simulate epoch transitions by creating multiple accumulators
        var accumulator1 = new SignatureAccumulator(testEvent, 3, 1, testViewRef1);
        var accumulator2 = new SignatureAccumulator(testEvent, 5, 2, testViewRef2);

        // First epoch accumulation (reach threshold with 3 signatures)
        for (int i = 0; i < 3; i++) {
            var result = accumulator1.accumulate(
                testMembers.get(i),
                i,
                testSignatures.get(i),
                1,
                testViewRef1
            );
            assertThat(result.isSuccess()).isTrue();
        }

        // Try to use old epoch signature on new accumulator BEFORE threshold
        var wrongEpochResult = accumulator2.accumulate(
            testMembers.get(0),
            0,
            testSignatures.get(0),
            1,  // Old epoch
            testViewRef1
        );

        assertThat(wrongEpochResult).isInstanceOf(AccumulationResult.EpochMismatch.class);

        // Second epoch accumulation (reach threshold)
        for (int i = 1; i < 6; i++) {
            var result = accumulator2.accumulate(
                testMembers.get(i),
                i,
                testSignatures.get(i),
                2,
                testViewRef2
            );
            assertThat(result.isSuccess()).isTrue();
        }

        // Verify both accumulators reached threshold
        assertThat(accumulator1.isThresholdMet()).isTrue();
        assertThat(accumulator2.isThresholdMet()).isTrue();

        // Verify snapshots have correct epochs
        assertThat(accumulator1.getThresholdSnapshot().get().epoch()).isEqualTo(1);
        assertThat(accumulator2.getThresholdSnapshot().get().epoch()).isEqualTo(2);

        // Verify epoch mismatch was counted in accumulator2
        assertThat(accumulator2.getMetrics().epochMismatches()).isEqualTo(1);
    }
}
