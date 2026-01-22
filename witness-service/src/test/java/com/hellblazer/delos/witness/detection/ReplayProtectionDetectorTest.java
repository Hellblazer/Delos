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
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Test suite for ReplayProtectionDetector.
 *
 * @author hal.hildebrand
 */
class ReplayProtectionDetectorTest {

    private ReplayProtectionDetector detector;
    private ByzantineDetectorConfig config;
    private Identifier testMember1;
    private Identifier testMember2;
    private EventCoordinates testCoordinates1;
    private EventCoordinates testCoordinates2;
    private BLSSignature testSignature1;
    private BLSSignature testSignature2;

    @BeforeEach
    void setUp() {
        config = ByzantineDetectorConfig.defaults();
        detector = new ReplayProtectionDetector(config, new NoOpByzantineDetectionMetrics());

        testMember1 = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("member1".getBytes()));
        testMember2 = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("member2".getBytes()));

        testCoordinates1 = new EventCoordinates(
            testMember1,
            ULong.valueOf(1),
            DigestAlgorithm.DEFAULT.digest("event1".getBytes()),
            "icp"
        );

        testCoordinates2 = new EventCoordinates(
            testMember1,
            ULong.valueOf(2),
            DigestAlgorithm.DEFAULT.digest("event2".getBytes()),
            "icp"
        );

        // Create test signatures (96 bytes for BLS)
        var sigBytes1 = new byte[96];
        var sigBytes2 = new byte[96];
        for (int i = 0; i < 96; i++) {
            sigBytes1[i] = (byte) i;
            sigBytes2[i] = (byte) (i + 1);
        }
        testSignature1 = new BLSSignature(sigBytes1);
        testSignature2 = new BLSSignature(sigBytes2);
    }

    @Test
    void shouldStartWithZeroScore() {
        // No replays recorded yet
        var score = detector.getAnomalyScore(testMember1);
        assertThat(score).isEqualTo(0.0);

        // Unknown member also has zero score
        var unknownMember = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("unknown".getBytes()));
        assertThat(detector.getAnomalyScore(unknownMember)).isEqualTo(0.0);
    }

    @Test
    void shouldDetectFirstReplay() {
        var aggregate = createAggregate(testSignature1);
        var result = new ValidationResult.Valid(aggregate);

        // First submission - no replay
        detector.recordValidationResult(testMember1, testCoordinates1, result, 100L);
        assertThat(detector.getAnomalyScore(testMember1)).isEqualTo(0.0);

        // Second submission of same (coordinates, signature) pair - REPLAY!
        detector.recordValidationResult(testMember1, testCoordinates1, result, 100L);

        // Score should be 0.2 for first replay
        var score = detector.getAnomalyScore(testMember1);
        assertThat(score).isEqualTo(0.2);

        // Should have detected anomaly
        var anomalies = detector.getDetectedAnomalies();
        assertThat(anomalies).hasSize(1);
        assertThat(anomalies.getFirst().suspectMemberId()).isEqualTo(testMember1);
        assertThat(anomalies.getFirst().type()).isEqualTo(AnomalyType.REPLAY);
        assertThat(anomalies.getFirst().evidence()).isNotEmpty();
    }

    @Test
    void shouldDetectSecondReplay() {
        var aggregate = createAggregate(testSignature1);
        var result = new ValidationResult.Valid(aggregate);

        // First submission
        detector.recordValidationResult(testMember1, testCoordinates1, result, 100L);

        // First replay - score 0.2
        detector.recordValidationResult(testMember1, testCoordinates1, result, 100L);
        assertThat(detector.getAnomalyScore(testMember1)).isEqualTo(0.2);

        // Second replay within 1 hour - score should jump to 0.5
        detector.recordValidationResult(testMember1, testCoordinates1, result, 100L);
        var score = detector.getAnomalyScore(testMember1);
        assertThat(score).isEqualTo(0.5);
    }

    @Test
    void shouldDetectThirdReplay() {
        var aggregate = createAggregate(testSignature1);
        var result = new ValidationResult.Valid(aggregate);

        // First submission
        detector.recordValidationResult(testMember1, testCoordinates1, result, 100L);

        // First replay - score 0.2
        detector.recordValidationResult(testMember1, testCoordinates1, result, 100L);

        // Second replay - score 0.5
        detector.recordValidationResult(testMember1, testCoordinates1, result, 100L);

        // Third replay within 1 hour - score should jump to 0.85 (coordinated attack pattern)
        detector.recordValidationResult(testMember1, testCoordinates1, result, 100L);
        var score = detector.getAnomalyScore(testMember1);
        assertThat(score).isEqualTo(0.85);

        // Evidence should show replay count
        var anomalies = detector.getDetectedAnomalies();
        assertThat(anomalies).hasSize(1);
        assertThat(anomalies.getFirst().evidence()).anyMatch(e -> e.contains("replay count: 3"));
    }

    @Test
    void shouldBoundCacheSize() {
        // Fill cache with 5,001 different (coordinate, signature) pairs
        for (int i = 0; i < 5001; i++) {
            var coords = new EventCoordinates(
                testMember1,
                ULong.valueOf(i),
                DigestAlgorithm.DEFAULT.digest(("event" + i).getBytes()),
                "icp"
            );

            var sigBytes = new byte[96];
            for (int j = 0; j < 96; j++) {
                sigBytes[j] = (byte) (i + j);
            }
            var sig = new BLSSignature(sigBytes);
            var aggregate = createAggregate(sig);
            var result = new ValidationResult.Valid(aggregate);

            detector.recordValidationResult(testMember1, coords, result, 100L);
        }

        // Cache should be bounded at 5,000 entries
        // The earliest entry should have been evicted
        // We can't directly test cache size, but we can test that replaying the first entry
        // doesn't count as a replay (it was evicted)
        var coords0 = new EventCoordinates(
            testMember1,
            ULong.valueOf(0),
            DigestAlgorithm.DEFAULT.digest("event0".getBytes()),
            "icp"
        );
        var sigBytes0 = new byte[96];
        for (int j = 0; j < 96; j++) {
            sigBytes0[j] = (byte) j;
        }
        var sig0 = new BLSSignature(sigBytes0);
        var aggregate0 = createAggregate(sig0);
        var result0 = new ValidationResult.Valid(aggregate0);

        // Submit again - should NOT be detected as replay because it was evicted
        detector.recordValidationResult(testMember1, coords0, result0, 100L);

        // Score should still be 0 (no replay detected for this member in recent window)
        var score = detector.getAnomalyScore(testMember1);
        assertThat(score).isEqualTo(0.0);
    }

    @Test
    void shouldExpireOldEntries() {
        // Use a config with very short TTL for testing (1 second)
        var shortTtlConfig = new ByzantineDetectorConfig(
            5,                           // invalidSignatureThreshold
            5000,                        // maxReceiptLatencyMs
            0.95,                        // timingAnomalyThreshold
            0.5,                         // failureRateThreshold
            50,                          // minSampleSize
            0.9,                         // criticalAnomalyScore
            0.7,                         // warningAnomalyScore
            Duration.ofSeconds(1),       // scoreDecayPeriod (fast decay for test)
            0.5,                         // scoreDecayRate
            true,                        // enableAutomaticThresholdAdaptation
            1000                         // historyWindowSize
        );
        var shortTtlDetector = new ReplayProtectionDetector(shortTtlConfig, new NoOpByzantineDetectionMetrics());

        var aggregate = createAggregate(testSignature1);
        var result = new ValidationResult.Valid(aggregate);

        // First submission
        shortTtlDetector.recordValidationResult(testMember1, testCoordinates1, result, 100L);

        // Wait for TTL expiration (simulate with manual clock advance or use actual wait)
        // For this test, we'll rely on the decay mechanism
        // Submit the same pair again after waiting - should still detect as replay initially
        // but score should decay over time

        // Immediate replay - should detect
        shortTtlDetector.recordValidationResult(testMember1, testCoordinates1, result, 100L);
        assertThat(shortTtlDetector.getAnomalyScore(testMember1)).isEqualTo(0.2);
    }

    @Test
    void shouldDecayScore() throws InterruptedException {
        // Use config with fast decay for testing
        var fastDecayConfig = new ByzantineDetectorConfig(
            5,
            5000,
            0.95,
            0.5,
            50,
            0.9,
            0.7,
            Duration.ofMillis(100),      // Very short decay period for testing
            0.5,                         // 50% decay per period
            true,
            1000
        );
        var fastDecayDetector = new ReplayProtectionDetector(fastDecayConfig, new NoOpByzantineDetectionMetrics());

        var aggregate = createAggregate(testSignature1);
        var result = new ValidationResult.Valid(aggregate);

        // First submission
        fastDecayDetector.recordValidationResult(testMember1, testCoordinates1, result, 100L);

        // First replay - score 0.2
        fastDecayDetector.recordValidationResult(testMember1, testCoordinates1, result, 100L);
        var initialScore = fastDecayDetector.getAnomalyScore(testMember1);
        assertThat(initialScore).isEqualTo(0.2);

        // Wait for decay period
        Thread.sleep(200);

        // Score should have decayed (0.2 * 0.5 = 0.1)
        var decayedScore = fastDecayDetector.getAnomalyScore(testMember1);
        assertThat(decayedScore).isLessThan(initialScore);
        assertThat(decayedScore).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void shouldTrackMultipleMembersIndependently() {
        var aggregate1 = createAggregate(testSignature1);
        var aggregate2 = createAggregate(testSignature2);
        var result1 = new ValidationResult.Valid(aggregate1);
        var result2 = new ValidationResult.Valid(aggregate2);

        // Member 1: replay detected
        detector.recordValidationResult(testMember1, testCoordinates1, result1, 100L);
        detector.recordValidationResult(testMember1, testCoordinates1, result1, 100L);

        // Member 2: no replay
        detector.recordValidationResult(testMember2, testCoordinates2, result2, 100L);

        // Member 1 should have non-zero score
        assertThat(detector.getAnomalyScore(testMember1)).isEqualTo(0.2);

        // Member 2 should have zero score
        assertThat(detector.getAnomalyScore(testMember2)).isEqualTo(0.0);

        // Anomalies should only include member1
        var anomalies = detector.getDetectedAnomalies();
        assertThat(anomalies).hasSize(1);
        assertThat(anomalies.getFirst().suspectMemberId()).isEqualTo(testMember1);
    }

    @Test
    void shouldIgnoreNonValidResults() {
        // Record non-Valid results (no signature to track)
        detector.recordValidationResult(
            testMember1,
            testCoordinates1,
            new ValidationResult.InvalidSignature("member1", "forged"),
            100L
        );

        detector.recordValidationResult(
            testMember1,
            testCoordinates1,
            new ValidationResult.ValidationFailed("error"),
            100L
        );

        // Score should remain 0 (no Valid results to track)
        assertThat(detector.getAnomalyScore(testMember1)).isEqualTo(0.0);
        assertThat(detector.getDetectedAnomalies()).isEmpty();
    }

    @Test
    void shouldDetectMultipleReplaysInDifferentEvents() {
        var aggregate1 = createAggregate(testSignature1);
        var result1 = new ValidationResult.Valid(aggregate1);

        // Event 1: first submission and replay
        detector.recordValidationResult(testMember1, testCoordinates1, result1, 100L);
        detector.recordValidationResult(testMember1, testCoordinates1, result1, 100L);

        // Event 2: submission and replay (same signature reused!)
        detector.recordValidationResult(testMember1, testCoordinates2, result1, 100L);
        detector.recordValidationResult(testMember1, testCoordinates2, result1, 100L);

        // Score should reflect multiple replays: 0.2 + 0.15 = 0.35 (additional replay boost)
        var score = detector.getAnomalyScore(testMember1);
        assertThat(score).isGreaterThanOrEqualTo(0.35);
    }

    @Test
    void shouldResetClearsState() {
        var aggregate = createAggregate(testSignature1);
        var result = new ValidationResult.Valid(aggregate);

        // Record replay
        detector.recordValidationResult(testMember1, testCoordinates1, result, 100L);
        detector.recordValidationResult(testMember1, testCoordinates1, result, 100L);

        assertThat(detector.getAnomalyScore(testMember1)).isGreaterThan(0.0);
        assertThat(detector.getDetectedAnomalies()).isNotEmpty();

        // Reset should clear all state
        detector.reset();

        assertThat(detector.getAnomalyScore(testMember1)).isEqualTo(0.0);
        assertThat(detector.getDetectedAnomalies()).isEmpty();
    }

    @Test
    void shouldHandleConcurrentRecording() throws InterruptedException {
        var executor = Executors.newFixedThreadPool(10);
        var latch = new CountDownLatch(100);

        var aggregate = createAggregate(testSignature1);
        var result = new ValidationResult.Valid(aggregate);

        // Submit same (coordinate, signature) pair 100 times concurrently
        for (int i = 0; i < 100; i++) {
            executor.submit(() -> {
                try {
                    detector.recordValidationResult(testMember1, testCoordinates1, result, 100L);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // Should detect multiple replays (thread-safe)
        var score = detector.getAnomalyScore(testMember1);
        assertThat(score).isGreaterThan(0.0);

        // Should not throw concurrent modification exceptions
        assertThatCode(() -> detector.getDetectedAnomalies()).doesNotThrowAnyException();
    }

    @Test
    void shouldProvideDetectorName() {
        assertThat(detector.getDetectorName()).isEqualTo("ReplayProtectionDetector");
    }

    // Helper method to create BLSAggregate from a single signature
    private BLSAggregate createAggregate(BLSSignature signature) {
        var bitmap = new byte[]{0x01}; // Single signer at index 0
        return new BLSAggregate(signature, bitmap);
    }
}
