/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.BLSAggregate;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.aggregation.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Test TimingAnomalyDetector implementation.
 */
class TimingAnomalyDetectorTest {

    private TimingAnomalyDetector detector;
    private ByzantineDetectorConfig config;
    private Identifier memberId;
    private EventCoordinates receiptCoords;
    private ValidationResult validResult;

    @BeforeEach
    void setUp() {
        config = ByzantineDetectorConfig.defaults();
        detector = new TimingAnomalyDetector(config);
        memberId = Identifier.NONE;
        receiptCoords = mock(EventCoordinates.class);

        // Create a dummy BLSAggregate for testing (96-byte signature + 1-byte bitmap)
        var dummySignature = new BLSSignature(new byte[96]);
        var dummyBitmap = new byte[]{1};
        var dummyAggregate = new BLSAggregate(dummySignature, dummyBitmap);
        validResult = new ValidationResult.Valid(dummyAggregate);
    }

    @Test
    void shouldReturnDetectorName() {
        assertThat(detector.getDetectorName()).isEqualTo("TimingAnomalyDetector");
    }

    @Test
    void testNormalLatency() {
        // Receipt with <50ms latency should score 0.0
        detector.recordValidationResult(
            memberId,
            receiptCoords,
            validResult,
            25  // 25ms latency - normal
        );

        var score = detector.getAnomalyScore(memberId);

        assertThat(score).isEqualTo(0.0);
    }

    @Test
    void testElevatedLatency() {
        // Receipt with 100ms latency should score between 0.0 and 0.5
        detector.recordValidationResult(
            memberId,
            receiptCoords,
            validResult,
            100  // 100ms latency - elevated
        );

        var score = detector.getAnomalyScore(memberId);

        assertThat(score)
            .isGreaterThan(0.0)
            .isLessThanOrEqualTo(0.5);
    }

    @Test
    void testHighLatency() {
        // Receipt with 500ms latency should score 0.5-0.8
        detector.recordValidationResult(
            memberId,
            receiptCoords,
            validResult,
            500  // 500ms latency - high
        );

        var score = detector.getAnomalyScore(memberId);

        assertThat(score)
            .isGreaterThanOrEqualTo(0.5)
            .isLessThanOrEqualTo(0.8);
    }

    @Test
    void testExtremeLatency() {
        // Receipt with >1000ms latency should score >0.9
        detector.recordValidationResult(
            memberId,
            receiptCoords,
            validResult,
            1500  // 1500ms latency - extreme
        );

        var score = detector.getAnomalyScore(memberId);

        assertThat(score).isGreaterThan(0.9);
    }

    @Test
    void testExponentialMovingAverage() {
        // Multiple receipts should update EMA correctly with alpha=0.3

        // First receipt: 100ms latency
        detector.recordValidationResult(
            memberId,
            receiptCoords,
            validResult,
            100
        );

        var score1 = detector.getAnomalyScore(memberId);

        // Second receipt: 200ms latency (higher)
        detector.recordValidationResult(
            memberId,
            receiptCoords,
            validResult,
            200
        );

        var score2 = detector.getAnomalyScore(memberId);

        // Score should increase with higher latency
        assertThat(score2).isGreaterThan(score1);

        // Third receipt: 50ms latency (lower)
        detector.recordValidationResult(
            memberId,
            receiptCoords,
            validResult,
            50
        );

        var score3 = detector.getAnomalyScore(memberId);

        // Score should decrease with lower latency
        assertThat(score3).isLessThan(score2);
    }

    @Test
    void testExponentialMovingAverageRecency() {
        // Recent high latency should be weighted more heavily (alpha=0.3)

        // Start with several low latency samples
        for (int i = 0; i < 5; i++) {
            detector.recordValidationResult(
                memberId,
                receiptCoords,
                validResult,
                20  // Very low latency
            );
        }

        var lowLatencyScore = detector.getAnomalyScore(memberId);

        // Add one high latency sample
        detector.recordValidationResult(
            memberId,
            receiptCoords,
            validResult,
            1000  // Very high latency
        );

        var afterHighLatencyScore = detector.getAnomalyScore(memberId);

        // Score should increase significantly due to recency bias
        assertThat(afterHighLatencyScore).isGreaterThan(lowLatencyScore);
        assertThat(afterHighLatencyScore).isGreaterThan(0.5);  // Should be noticeably elevated
    }

    @Test
    void testMaxMinTracking() {
        // Track peak and minimum latencies correctly

        detector.recordValidationResult(
            memberId,
            receiptCoords,
            validResult,
            100
        );

        detector.recordValidationResult(
            memberId,
            receiptCoords,
            validResult,
            500  // Max
        );

        detector.recordValidationResult(
            memberId,
            receiptCoords,
            validResult,
            20  // Min
        );

        detector.recordValidationResult(
            memberId,
            receiptCoords,
            validResult,
            200
        );

        var stats = detector.getTimingStats(memberId);

        assertThat(stats).isNotNull();
        assertThat(stats.maxLatencyMs()).isEqualTo(500);
        assertThat(stats.minLatencyMs()).isEqualTo(20);
        assertThat(stats.sampleCount()).isEqualTo(4);
        assertThat(stats.averageLatencyMs()).isGreaterThan(0.0);
    }

    @Test
    void testMultipleMembersIndependent() {
        // Different members should be tracked independently

        var member1 = Identifier.NONE;
        var member2 = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("member2".getBytes()));

        // Member 1: low latency
        detector.recordValidationResult(
            member1,
            receiptCoords,
            validResult,
            30
        );

        // Member 2: high latency
        detector.recordValidationResult(
            member2,
            receiptCoords,
            validResult,
            1000
        );

        var score1 = detector.getAnomalyScore(member1);
        var score2 = detector.getAnomalyScore(member2);

        // Scores should be independent
        assertThat(score1).isLessThan(0.1);  // Member 1 normal
        assertThat(score2).isGreaterThan(0.9);  // Member 2 extreme
    }

    @Test
    void testConcurrentRecording() throws InterruptedException {
        // Multiple threads recording simultaneously should be thread-safe

        int threadCount = 10;
        int recordsPerThread = 100;
        var latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        var members = new ArrayList<Identifier>();
        for (int i = 0; i < threadCount; i++) {
            members.add(new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest(("member" + i).getBytes())));
        }

        try {
            for (int i = 0; i < threadCount; i++) {
                final int threadIndex = i;
                executor.submit(() -> {
                    try {
                        for (int j = 0; j < recordsPerThread; j++) {
                            detector.recordValidationResult(
                                members.get(threadIndex),
                                receiptCoords,
                                validResult,
                                (threadIndex * 10 + j) % 500  // Varying latencies
                            );
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();

            // Verify all members have expected sample counts
            for (int i = 0; i < threadCount; i++) {
                var stats = detector.getTimingStats(members.get(i));
                assertThat(stats).isNotNull();
                assertThat(stats.sampleCount()).isEqualTo(recordsPerThread);
            }

            // Verify no exceptions during concurrent access
            assertThat(detector.getDetectedAnomalies()).isNotNull();

        } finally {
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void testBoundaryConditions() {
        // Test edge cases at threshold boundaries

        // Exactly at 50ms threshold
        detector.recordValidationResult(
            memberId,
            receiptCoords,
            validResult,
            50
        );
        var score50 = detector.getAnomalyScore(memberId);

        // Just below 50ms
        var member2 = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("member2".getBytes()));
        detector.recordValidationResult(
            member2,
            receiptCoords,
            validResult,
            49
        );
        var score49 = detector.getAnomalyScore(member2);

        // Just above 50ms
        var member3 = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("member3".getBytes()));
        detector.recordValidationResult(
            member3,
            receiptCoords,
            validResult,
            51
        );
        var score51 = detector.getAnomalyScore(member3);

        // Verify smooth transition around threshold
        assertThat(score49).isEqualTo(0.0);
        assertThat(score50).isGreaterThanOrEqualTo(0.0);
        assertThat(score51).isGreaterThan(0.0);

        // Exactly at 1000ms threshold
        var member4 = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("member4".getBytes()));
        detector.recordValidationResult(
            member4,
            receiptCoords,
            validResult,
            1000
        );
        var score1000 = detector.getAnomalyScore(member4);

        // Should be high but not quite at max
        assertThat(score1000)
            .isGreaterThanOrEqualTo(0.8)
            .isLessThan(1.0);
    }

    @Test
    void testResetClearsState() {
        // Record some data
        detector.recordValidationResult(
            memberId,
            receiptCoords,
            validResult,
            500
        );

        assertThat(detector.getAnomalyScore(memberId)).isGreaterThan(0.0);
        assertThat(detector.getTimingStats(memberId)).isNotNull();

        // Reset should clear all state
        detector.reset();

        assertThat(detector.getAnomalyScore(memberId)).isEqualTo(0.0);
        assertThat(detector.getTimingStats(memberId)).isNull();
    }

    @Test
    void testZeroLatency() {
        // Edge case: zero latency
        detector.recordValidationResult(
            memberId,
            receiptCoords,
            validResult,
            0
        );

        var score = detector.getAnomalyScore(memberId);

        assertThat(score).isEqualTo(0.0);

        var stats = detector.getTimingStats(memberId);
        assertThat(stats).isNotNull();
        assertThat(stats.minLatencyMs()).isEqualTo(0);
    }

    @Test
    void testScoreAlwaysInRange() {
        // Verify scores are always clamped to [0.0, 1.0]

        var testLatencies = List.of(0L, 25L, 50L, 100L, 200L, 500L, 1000L, 2000L, 5000L, 10000L);

        for (var latency : testLatencies) {
            var testMember = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest(("member_" + latency).getBytes()));
            detector.recordValidationResult(
                testMember,
                receiptCoords,
                validResult,
                latency
            );

            var score = detector.getAnomalyScore(testMember);

            assertThat(score)
                .withFailMessage("Score for latency %dms should be in range [0.0, 1.0]", latency)
                .isBetween(0.0, 1.0);
        }
    }

    @Test
    void testGetDetectedAnomaliesForHighLatency() {
        // High latency should generate detected anomaly

        detector.recordValidationResult(
            memberId,
            receiptCoords,
            validResult,
            1500  // Extreme latency
        );

        var anomalies = detector.getDetectedAnomalies();

        // Should detect anomaly for extreme latency
        assertThat(anomalies).isNotEmpty();
        assertThat(anomalies.get(0).suspectMemberId()).isEqualTo(memberId);
        assertThat(anomalies.get(0).type()).isEqualTo(AnomalyType.TIMING_ANOMALY);
        assertThat(anomalies.get(0).anomalyScore()).isGreaterThan(0.9);
    }

    @Test
    void testNoAnomaliesForNormalLatency() {
        // Normal latency should not generate anomalies

        detector.recordValidationResult(
            memberId,
            receiptCoords,
            validResult,
            30  // Normal latency
        );

        var anomalies = detector.getDetectedAnomalies();

        assertThat(anomalies).isEmpty();
    }

    @Test
    void testRequireNonNullConfig() {
        assertThatThrownBy(() -> new TimingAnomalyDetector(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testFirstSampleInitializesEMA() {
        // First sample should initialize EMA to the latency value

        detector.recordValidationResult(
            memberId,
            receiptCoords,
            validResult,
            100
        );

        var stats = detector.getTimingStats(memberId);

        assertThat(stats).isNotNull();
        assertThat(stats.sampleCount()).isEqualTo(1);
        assertThat(stats.averageLatencyMs()).isEqualTo(100.0);
    }
}
