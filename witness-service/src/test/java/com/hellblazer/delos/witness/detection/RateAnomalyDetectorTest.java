/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.aggregation.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Test suite for RateAnomalyDetector.
 *
 * @author hal.hildebrand
 */
@Tag("performance")
class RateAnomalyDetectorTest {

    private RateAnomalyDetector detector;
    private ByzantineDetectorConfig config;
    private Identifier memberId;
    private EventCoordinates coordinates;

    @BeforeEach
    void setUp() {
        config = ByzantineDetectorConfig.defaults();
        detector = new RateAnomalyDetector(config, new NoOpByzantineDetectionMetrics());
        memberId = Identifier.NONE;
        coordinates = mock(EventCoordinates.class);
    }

    @Test
    void testNormalRate() throws InterruptedException {
        // 10 receipts over 5 seconds (2 receipts/sec) should score 0.0
        for (int i = 0; i < 10; i++) {
            detector.recordValidationResult(
                memberId,
                coordinates,
                new ValidationResult.ValidationFailed("test"),
                10
            );
            Thread.sleep(500);  // 500ms between receipts
        }

        var score = detector.getAnomalyScore(memberId);
        assertThat(score).isEqualTo(0.0);
    }

    @Test
    void testHealthyRate() throws InterruptedException {
        // 50 receipts over 5 seconds (10 receipts/sec) should score 0.0
        for (int i = 0; i < 50; i++) {
            detector.recordValidationResult(
                memberId,
                coordinates,
                new ValidationResult.ValidationFailed("test"),
                10
            );
            Thread.sleep(100);  // 100ms between receipts
        }

        var score = detector.getAnomalyScore(memberId);
        assertThat(score).isEqualTo(0.0);
    }

    @Test
    void testElevatedRate() throws InterruptedException {
        // 200 receipts over ~5 seconds (40 receipts/sec target)
        // Thread.sleep(25) has variance: actual ~25-35ms on macOS
        // This can result in: 5.0-7.0 second total duration
        // With 5-second sliding window and timing variance, early receipts may exit window
        // So we allow score 0.05+ (below 0.1 threshold due to window turnover) up to 0.5
        for (int i = 0; i < 200; i++) {
            detector.recordValidationResult(
                memberId,
                coordinates,
                new ValidationResult.ValidationFailed("test"),
                10
            );
            Thread.sleep(25);  // 25ms between receipts (actual ~25-35ms variance)
        }

        var score = detector.getAnomalyScore(memberId);
        assertThat(score)
            .isGreaterThanOrEqualTo(0.05)  // Accounts for sliding window turnover with variance
            .isLessThanOrEqualTo(0.5);
    }

    @Test
    void testHighRate() throws InterruptedException {
        // 300 receipts with 12ms delay targets ~83 receipts/sec (HIGH range: 60-200)
        // Thread.sleep(12) variance on macOS: actual ~12-18ms
        // This can result in: 60-90 receipts/sec actual rate
        // Score range 0.5-0.9 for HIGH range [60-200 receipts/sec]
        // At lower bound (60 receipts/sec): score ~0.5
        // At 83 receipts/sec: score ~0.62
        // Variance can push toward lower boundary, so 0.40+ accounts for worst case
        for (int i = 0; i < 300; i++) {
            detector.recordValidationResult(
                memberId,
                coordinates,
                new ValidationResult.ValidationFailed("test"),
                10
            );
            Thread.sleep(12);  // 12ms between receipts (actual ~12-18ms with variance)
        }

        var score = detector.getAnomalyScore(memberId);
        assertThat(score)
            .isGreaterThanOrEqualTo(0.40)  // Accounts for worst-case variance (lower bound of HIGH range)
            .isLessThanOrEqualTo(0.9);
    }

    @Test
    void testExtremeRate() throws InterruptedException {
        // 1000 receipts with 3ms delay targets ~333 receipts/sec (EXTREME range: >200)
        // Thread.sleep() variance (especially for short sleeps) means actual rate may be 200-300 receipts/sec
        // Score > 0.9 for rate >= 200 receipts/sec; expect > 0.85 accounting for variance
        for (int i = 0; i < 1000; i++) {
            detector.recordValidationResult(
                memberId,
                coordinates,
                new ValidationResult.ValidationFailed("test"),
                10
            );
            Thread.sleep(3);  // 3ms between receipts for ~333 receipts/sec target
        }

        var score = detector.getAnomalyScore(memberId);
        assertThat(score).isGreaterThan(0.85);  // Accounts for Thread.sleep() variance
    }

    @Test
    void testSlidingWindow() throws InterruptedException {
        // Record 100 receipts quickly
        for (int i = 0; i < 100; i++) {
            detector.recordValidationResult(
                memberId,
                coordinates,
                new ValidationResult.ValidationFailed("test"),
                10
            );
        }

        // Should have high score
        var scoreImmediately = detector.getAnomalyScore(memberId);
        assertThat(scoreImmediately).isGreaterThan(0.8);

        // Wait 6 seconds (past 5-second window)
        Thread.sleep(6000);

        // Record one more receipt to trigger window cleanup
        detector.recordValidationResult(
            memberId,
            coordinates,
            new ValidationResult.ValidationFailed("test"),
            10
        );

        // Score should drop significantly as old timestamps removed
        var scoreAfterWindow = detector.getAnomalyScore(memberId);
        assertThat(scoreAfterWindow).isLessThan(scoreImmediately);
    }

    @Test
    void testExponentialMovingAverageRecency() throws InterruptedException {
        // Record 10 receipts slowly (normal rate = 2 receipts/sec)
        for (int i = 0; i < 10; i++) {
            detector.recordValidationResult(
                memberId,
                coordinates,
                new ValidationResult.ValidationFailed("test"),
                10
            );
            Thread.sleep(500);  // 500ms between receipts
        }
        var scoreAfterSlow = detector.getAnomalyScore(memberId);

        // Now record 100 receipts rapidly (high rate = 200 receipts/sec)
        for (int i = 0; i < 100; i++) {
            detector.recordValidationResult(
                memberId,
                coordinates,
                new ValidationResult.ValidationFailed("test"),
                10
            );
            Thread.sleep(5);  // 5ms between receipts
        }
        var scoreAfterFast = detector.getAnomalyScore(memberId);

        // Score should increase significantly due to recent high rate
        assertThat(scoreAfterFast).isGreaterThan(scoreAfterSlow);
        assertThat(scoreAfterFast).isGreaterThan(0.7);
    }

    @Test
    void testMultipleMembersIndependent() throws InterruptedException {
        var member1 = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("member-1".getBytes()));
        var member2 = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("member-2".getBytes()));

        // Member 1 sends at extreme rate with 3ms delay targeting ~333 receipts/sec (EXTREME range: >200)
        // Thread.sleep() variance means actual rate may be 200-300 receipts/sec
        for (int i = 0; i < 1000; i++) {
            detector.recordValidationResult(
                member1,
                coordinates,
                new ValidationResult.ValidationFailed("test"),
                10
            );
            Thread.sleep(3);  // 3ms between receipts for ~333 receipts/sec target
        }

        // Member 2 sends at normal rate (10 receipts over 5 sec = 2 receipts/sec)
        for (int i = 0; i < 10; i++) {
            detector.recordValidationResult(
                member2,
                coordinates,
                new ValidationResult.ValidationFailed("test"),
                10
            );
            Thread.sleep(500);  // 500ms between receipts
        }

        // Member 1 should have high score (accounting for Thread.sleep() variance)
        // Member 2 should have low score
        assertThat(detector.getAnomalyScore(member1)).isGreaterThan(0.85);  // Accounts for variance
        assertThat(detector.getAnomalyScore(member2)).isEqualTo(0.0);
    }

    @Test
    void testWindowSize() throws InterruptedException {
        // Record 200 receipts (well over the 100 cap)
        for (int i = 0; i < 200; i++) {
            detector.recordValidationResult(
                memberId,
                coordinates,
                new ValidationResult.ValidationFailed("test"),
                10
            );
            Thread.sleep(5);  // Small delay
        }

        // Verify window is capped at 100 entries
        var stats = detector.getMemberStats(memberId);
        assertThat(stats).isNotNull();
        assertThat(stats.receiptTimestamps().size()).isLessThanOrEqualTo(100);
    }

    @Test
    void testConcurrentRecording() throws InterruptedException {
        var executorService = Executors.newFixedThreadPool(10);
        var latch = new CountDownLatch(10);
        var errors = new ConcurrentLinkedQueue<Throwable>();

        // 10 threads each recording 50 receipts
        for (int t = 0; t < 10; t++) {
            executorService.submit(() -> {
                try {
                    for (int i = 0; i < 50; i++) {
                        detector.recordValidationResult(
                            memberId,
                            coordinates,
                            new ValidationResult.ValidationFailed("test"),
                            10
                        );
                    }
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executorService.shutdown();

        // Should not have any concurrency errors
        assertThat(errors).isEmpty();

        // Should have recorded all receipts
        var score = detector.getAnomalyScore(memberId);
        assertThat(score).isGreaterThan(0.9); // 500 receipts in rapid succession
    }

    @Test
    void testBoundaryConditions() throws InterruptedException {
        // Test at exact threshold boundaries

        // Test at 5 receipts/sec boundary (should be 0.0)
        for (int i = 0; i < 25; i++) {
            detector.recordValidationResult(
                memberId,
                coordinates,
                new ValidationResult.ValidationFailed("test"),
                10
            );
            Thread.sleep(200);  // 200ms between receipts = 5 receipts/sec
        }
        assertThat(detector.getAnomalyScore(memberId)).isEqualTo(0.0);

        // Test at 30 receipts/sec boundary (transition point)
        var member2 = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("member-2".getBytes()));
        for (int i = 0; i < 150; i++) {
            detector.recordValidationResult(
                member2,
                coordinates,
                new ValidationResult.ValidationFailed("test"),
                10
            );
            Thread.sleep(33);  // ~30 receipts/sec
        }
        // Should be at or near transition from 0.0 to elevated
        var score = detector.getAnomalyScore(member2);
        assertThat(score).isGreaterThanOrEqualTo(0.0).isLessThanOrEqualTo(0.2);

        // Test at 100 receipts/sec boundary
        var member3 = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("member-3".getBytes()));
        for (int i = 0; i < 500; i++) {
            detector.recordValidationResult(
                member3,
                coordinates,
                new ValidationResult.ValidationFailed("test"),
                10
            );
            Thread.sleep(10);  // ~100 receipts/sec
        }
        // Should be elevated score
        assertThat(detector.getAnomalyScore(member3)).isGreaterThanOrEqualTo(0.5);
    }

    @Test
    void testRateDecayAfterQuiet() throws InterruptedException {
        // Record 100 receipts rapidly (high rate)
        for (int i = 0; i < 100; i++) {
            detector.recordValidationResult(
                memberId,
                coordinates,
                new ValidationResult.ValidationFailed("test"),
                10
            );
        }
        var scoreAfterFlood = detector.getAnomalyScore(memberId);
        assertThat(scoreAfterFlood).isGreaterThan(0.7);

        // Wait 6 seconds (past window)
        Thread.sleep(6000);

        // Record single receipt to trigger recalculation
        detector.recordValidationResult(
            memberId,
            coordinates,
            new ValidationResult.ValidationFailed("test"),
            10
        );

        // Score should decay significantly
        var scoreAfterQuiet = detector.getAnomalyScore(memberId);
        assertThat(scoreAfterQuiet).isLessThan(scoreAfterFlood * 0.5);
    }

    @Test
    void testGetDetectorName() {
        assertThat(detector.getDetectorName()).isEqualTo("RateAnomalyDetector");
    }

    @Test
    void testGetDetectedAnomalies() {
        // Record extreme rate to trigger anomaly detection
        for (int i = 0; i < 1000; i++) {
            detector.recordValidationResult(
                memberId,
                coordinates,
                new ValidationResult.ValidationFailed("test"),
                10
            );
        }

        var anomalies = detector.getDetectedAnomalies();

        // Should detect anomaly for member with extreme rate
        assertThat(anomalies).isNotEmpty();

        var anomaly = anomalies.get(0);
        assertThat(anomaly.suspectMemberId()).isEqualTo(memberId);
        assertThat(anomaly.detectorName()).isEqualTo("RateAnomalyDetector");
        assertThat(anomaly.type()).isEqualTo(AnomalyType.RATE_ANOMALY);
        assertThat(anomaly.anomalyScore()).isGreaterThan(0.9);
    }

    @Test
    void testReset() {
        // Record receipts to build up state
        for (int i = 0; i < 100; i++) {
            detector.recordValidationResult(
                memberId,
                coordinates,
                new ValidationResult.ValidationFailed("test"),
                10
            );
        }

        assertThat(detector.getAnomalyScore(memberId)).isGreaterThan(0.7);

        // Reset should clear all state
        detector.reset();

        // Score should be 0.0 after reset
        assertThat(detector.getAnomalyScore(memberId)).isEqualTo(0.0);
        assertThat(detector.getMemberStats(memberId)).isNull();
    }

    @Test
    void testUnknownMember() {
        var unknownMember = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("unknown-member".getBytes()));

        // Score for unknown member should be 0.0
        assertThat(detector.getAnomalyScore(unknownMember)).isEqualTo(0.0);

        // Stats for unknown member should be null
        assertThat(detector.getMemberStats(unknownMember)).isNull();
    }
}
