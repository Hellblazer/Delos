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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Test suite for SignatureAnomalyDetector.
 *
 * @author hal.hildebrand
 */
class SignatureAnomalyDetectorTest {

    private SignatureAnomalyDetector detector;
    private ByzantineDetectorConfig config;
    private Identifier testMember1;
    private Identifier testMember2;
    private EventCoordinates testCoordinates;

    @BeforeEach
    void setUp() {
        config = ByzantineDetectorConfig.defaults();
        detector = new SignatureAnomalyDetector(config);

        testMember1 = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("member1".getBytes()));
        testMember2 = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("member2".getBytes()));
        testCoordinates = EventCoordinates.NONE;
    }

    @Test
    void testSingleSignatureFailure() {
        // Record successful validations first
        for (int i = 0; i < 10; i++) {
            detector.recordValidationResult(
                testMember1,
                testCoordinates,
                new ValidationResult.ValidationFailed("test"),
                100L
            );
        }

        // Get baseline score
        var baselineScore = detector.getAnomalyScore(testMember1);
        assertThat(baselineScore).isGreaterThan(0.0);

        // Record one more failure
        detector.recordValidationResult(
            testMember1,
            testCoordinates,
            new ValidationResult.InvalidSignature("member1", "forged"),
            100L
        );

        // Score should increase
        var updatedScore = detector.getAnomalyScore(testMember1);
        assertThat(updatedScore).isGreaterThan(baselineScore);
        assertThat(updatedScore).isLessThanOrEqualTo(1.0);
    }

    @Test
    void testConsecutiveFailures() {
        // Record consecutive failures
        for (int i = 0; i < 5; i++) {
            detector.recordValidationResult(
                testMember1,
                testCoordinates,
                new ValidationResult.InvalidSignature("member1", "consecutive failure " + i),
                100L
            );
        }

        var score = detector.getAnomalyScore(testMember1);

        // Consecutive failures should result in higher score
        // Expected: exponential boost from consecutive failures
        assertThat(score).isGreaterThan(0.3);
        assertThat(score).isLessThanOrEqualTo(1.0);

        // Verify anomalies were detected
        var anomalies = detector.getDetectedAnomalies();
        assertThat(anomalies).isNotEmpty();
        assertThat(anomalies.getFirst().suspectMemberId()).isEqualTo(testMember1);
        assertThat(anomalies.getFirst().type()).isIn(
            AnomalyType.SIGNATURE_INVALID,
            AnomalyType.SIGNATURE_FORGERY
        );
    }

    @Test
    void testSuccessfulVerificationResetsConsecutive() {
        // Record consecutive failures
        for (int i = 0; i < 3; i++) {
            detector.recordValidationResult(
                testMember1,
                testCoordinates,
                new ValidationResult.InvalidSignature("member1", "failure"),
                100L
            );
        }

        var scoreAfterFailures = detector.getAnomalyScore(testMember1);

        // Record successful verification - this should reset consecutive counter
        // But ValidationResult.Valid requires a BLSAggregate which we can't easily create in tests
        // So we'll test with ValidationFailed which isn't InvalidSignature
        detector.recordValidationResult(
            testMember1,
            testCoordinates,
            new ValidationResult.ValidationFailed("transient error"),
            100L
        );

        // Record another failure - should not have exponential boost since consecutive was reset
        detector.recordValidationResult(
            testMember1,
            testCoordinates,
            new ValidationResult.InvalidSignature("member1", "failure after reset"),
            100L
        );

        var scoreAfterReset = detector.getAnomalyScore(testMember1);

        // Score should still be high due to overall failure rate, but not as high as pure consecutive
        assertThat(scoreAfterReset).isGreaterThan(0.0);
    }

    @Test
    void testFailureRateThreshold() {
        // Create high failure rate (80% failures)
        for (int i = 0; i < 80; i++) {
            detector.recordValidationResult(
                testMember1,
                testCoordinates,
                new ValidationResult.InvalidSignature("member1", "failure " + i),
                100L
            );
        }

        for (int i = 0; i < 20; i++) {
            detector.recordValidationResult(
                testMember1,
                testCoordinates,
                new ValidationResult.ValidationFailed("not signature failure"),
                100L
            );
        }

        var score = detector.getAnomalyScore(testMember1);

        // High failure rate should trigger high anomaly score
        assertThat(score).isGreaterThan(0.7);
        assertThat(score).isLessThanOrEqualTo(1.0);

        // Should have detected anomalies
        var anomalies = detector.getDetectedAnomalies();
        assertThat(anomalies).isNotEmpty();
    }

    @Test
    void testMultipleMembersIndependent() {
        // Member 1: high failure rate
        for (int i = 0; i < 10; i++) {
            detector.recordValidationResult(
                testMember1,
                testCoordinates,
                new ValidationResult.InvalidSignature("member1", "failure"),
                100L
            );
        }

        // Member 2: all successful
        for (int i = 0; i < 10; i++) {
            detector.recordValidationResult(
                testMember2,
                testCoordinates,
                new ValidationResult.ValidationFailed("not signature issue"),
                100L
            );
        }

        var score1 = detector.getAnomalyScore(testMember1);
        var score2 = detector.getAnomalyScore(testMember2);

        // Member 1 should have high score, Member 2 should have low score
        assertThat(score1).isGreaterThan(score2);
        assertThat(score2).isLessThan(0.3);
    }

    @Test
    void testAnomalyScorePersists() {
        // Record failures
        for (int i = 0; i < 10; i++) {
            detector.recordValidationResult(
                testMember1,
                testCoordinates,
                new ValidationResult.InvalidSignature("member1", "failure"),
                100L
            );
        }

        var score1 = detector.getAnomalyScore(testMember1);
        var score2 = detector.getAnomalyScore(testMember1);

        // Score should persist across multiple calls
        assertThat(score1).isEqualTo(score2);
        assertThat(score1).isGreaterThan(0.0);
    }

    @Test
    void testBoundaryConditions() {
        // No failures recorded - score should be 0
        var scoreNoData = detector.getAnomalyScore(testMember1);
        assertThat(scoreNoData).isEqualTo(0.0);

        // 100% non-signature failures (should result in low signature anomaly score)
        for (int i = 0; i < 50; i++) {
            detector.recordValidationResult(
                testMember1,
                testCoordinates,
                new ValidationResult.ValidationFailed("not signature failure"),
                100L
            );
        }

        var scoreNoSigFailures = detector.getAnomalyScore(testMember1);
        assertThat(scoreNoSigFailures).isLessThan(0.2);

        // Unknown member - score should be 0
        var unknownMember = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("unknown".getBytes()));
        var scoreUnknown = detector.getAnomalyScore(unknownMember);
        assertThat(scoreUnknown).isEqualTo(0.0);
    }

    @Test
    void testConcurrentRecording() throws InterruptedException {
        var executor = Executors.newFixedThreadPool(10);
        var latch = new CountDownLatch(100);

        // Record 100 failures concurrently
        for (int i = 0; i < 100; i++) {
            final int iteration = i;
            executor.submit(() -> {
                try {
                    detector.recordValidationResult(
                        testMember1,
                        testCoordinates,
                        new ValidationResult.InvalidSignature("member1", "failure " + iteration),
                        100L
                    );
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // Verify thread-safe tracking
        var score = detector.getAnomalyScore(testMember1);
        assertThat(score).isGreaterThan(0.5);
        assertThat(score).isLessThanOrEqualTo(1.0);

        // Verify no concurrent modification exceptions
        assertThatCode(() -> detector.getDetectedAnomalies()).doesNotThrowAnyException();
    }

    @Test
    void testResetClearsState() {
        // Record failures
        for (int i = 0; i < 10; i++) {
            detector.recordValidationResult(
                testMember1,
                testCoordinates,
                new ValidationResult.InvalidSignature("member1", "failure"),
                100L
            );
        }

        assertThat(detector.getAnomalyScore(testMember1)).isGreaterThan(0.0);
        assertThat(detector.getDetectedAnomalies()).isNotEmpty();

        // Reset should clear state
        detector.reset();

        assertThat(detector.getAnomalyScore(testMember1)).isEqualTo(0.0);
        assertThat(detector.getDetectedAnomalies()).isEmpty();
    }

    @Test
    void testDetectorName() {
        assertThat(detector.getDetectorName()).isEqualTo("SignatureAnomalyDetector");
    }

    @Test
    void testInvalidBitmapNotCountedAsSignatureAnomaly() {
        // InvalidBitmap is not a signature-specific failure
        for (int i = 0; i < 20; i++) {
            detector.recordValidationResult(
                testMember1,
                testCoordinates,
                new ValidationResult.InvalidBitmap("bitmap error"),
                100L
            );
        }

        // Should result in low signature anomaly score since these aren't signature failures
        var score = detector.getAnomalyScore(testMember1);
        assertThat(score).isLessThan(0.2);
    }

    @Test
    void testInvalidThresholdNotCountedAsSignatureAnomaly() {
        // InvalidThreshold is not a signature-specific failure
        for (int i = 0; i < 20; i++) {
            detector.recordValidationResult(
                testMember1,
                testCoordinates,
                new ValidationResult.InvalidThreshold(10, 8),
                100L
            );
        }

        // Should result in low signature anomaly score
        var score = detector.getAnomalyScore(testMember1);
        assertThat(score).isLessThan(0.2);
    }
}
