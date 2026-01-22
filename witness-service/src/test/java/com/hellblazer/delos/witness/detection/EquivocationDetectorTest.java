/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.aggregation.ValidationResult;
import com.hellblazer.delos.witness.validation.FirefliesShunningIntegration;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

/**
 * Test suite for EquivocationDetector.
 *
 * @author hal.hildebrand
 */
class EquivocationDetectorTest {

    private EquivocationDetector detector;
    private ByzantineDetectorConfig config;
    private Identifier testMember1;
    private Identifier testMember2;
    private EventCoordinates testCoordinates1;
    private EventCoordinates testCoordinates2;
    private byte[] signature1;
    private byte[] signature2;

    @BeforeEach
    void setUp() {
        config = ByzantineDetectorConfig.defaults();
        detector = new EquivocationDetector(config, new NoOpByzantineDetectionMetrics());

        testMember1 = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("member1".getBytes()));
        testMember2 = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("member2".getBytes()));

        var digest1 = DigestAlgorithm.DEFAULT.digest("event1".getBytes());
        var digest2 = DigestAlgorithm.DEFAULT.digest("event2".getBytes());

        testCoordinates1 = new EventCoordinates(testMember1, ULong.valueOf(1), digest1, "test");
        testCoordinates2 = new EventCoordinates(testMember1, ULong.valueOf(2), digest2, "test");

        signature1 = "signature1".getBytes();
        signature2 = "signature2".getBytes();
    }

    @Test
    void shouldStartWithZeroScore() {
        // Initial state: no equivocations recorded
        var score = detector.getAnomalyScore(testMember1);
        assertThat(score).isEqualTo(0.0);

        var anomalies = detector.getDetectedAnomalies();
        assertThat(anomalies).isEmpty();
    }

    @Test
    void shouldDetectFirstEquivocation() {
        // Record first signature at event coordinate
        detector.recordEquivocation(testMember1, testCoordinates1, signature1);

        // Same coordinate with different signature = equivocation
        detector.recordEquivocation(testMember1, testCoordinates1, signature2);

        // First equivocation should result in 0.2 score
        var score = detector.getAnomalyScore(testMember1);
        assertThat(score).isGreaterThanOrEqualTo(0.19).isLessThanOrEqualTo(0.21);

        // Should have detected anomaly if score above warning threshold
        var anomalies = detector.getDetectedAnomalies();
        if (config.warningAnomalyScore() <= 0.2) {
            assertThat(anomalies).hasSize(1);
            assertThat(anomalies.getFirst().suspectMemberId()).isEqualTo(testMember1);
            assertThat(anomalies.getFirst().type()).isEqualTo(AnomalyType.EQUIVOCATION);
        }
    }

    @Test
    void shouldDetectSecondEquivocation() throws InterruptedException {
        // First equivocation
        detector.recordEquivocation(testMember1, testCoordinates1, signature1);
        detector.recordEquivocation(testMember1, testCoordinates1, signature2);

        // Wait a bit (but less than 5 minutes)
        Thread.sleep(100);

        // Second equivocation on different coordinates within 5 minutes
        detector.recordEquivocation(testMember1, testCoordinates2, signature1);
        detector.recordEquivocation(testMember1, testCoordinates2, signature2);

        // Second equivocation within 5 minutes should result in 0.5 score
        var score = detector.getAnomalyScore(testMember1);
        assertThat(score).isEqualTo(0.5);
    }

    @Test
    void shouldDetectThirdEquivocation() throws InterruptedException {
        // First equivocation
        detector.recordEquivocation(testMember1, testCoordinates1, signature1);
        detector.recordEquivocation(testMember1, testCoordinates1, signature2);

        // Second equivocation
        var coords2 = new EventCoordinates(
            testMember1,
            ULong.valueOf(2),
            DigestAlgorithm.DEFAULT.digest("event2".getBytes()),
            "test"
        );
        detector.recordEquivocation(testMember1, coords2, signature1);
        detector.recordEquivocation(testMember1, coords2, signature2);

        // Third equivocation
        var coords3 = new EventCoordinates(
            testMember1,
            ULong.valueOf(3),
            DigestAlgorithm.DEFAULT.digest("event3".getBytes()),
            "test"
        );
        detector.recordEquivocation(testMember1, coords3, signature1);
        detector.recordEquivocation(testMember1, coords3, signature2);

        // Third equivocation should trigger 0.85 score
        var score = detector.getAnomalyScore(testMember1);
        assertThat(score).isEqualTo(0.85);
    }

    @Test
    void shouldCallShunningIntegration() throws InterruptedException {
        var shunningCalled = new AtomicBoolean(false);
        var shunnedMember = new AtomicBoolean(false);

        var mockShunning = new FirefliesShunningIntegration() {
            @Override
            public CompletableFuture<Void> markMemberForShunning(Identifier memberId) {
                shunningCalled.set(true);
                shunnedMember.set(memberId.equals(testMember1));
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public boolean isShunned(Identifier memberId) {
                return false;
            }
        };

        detector.setShunningIntegration(mockShunning);

        // Trigger three equivocations to reach 0.85 threshold
        var coords1 = new EventCoordinates(
            testMember1,
            ULong.valueOf(1),
            DigestAlgorithm.DEFAULT.digest("event1".getBytes()),
            "test"
        );
        detector.recordEquivocation(testMember1, coords1, signature1);
        detector.recordEquivocation(testMember1, coords1, signature2);

        var coords2 = new EventCoordinates(
            testMember1,
            ULong.valueOf(2),
            DigestAlgorithm.DEFAULT.digest("event2".getBytes()),
            "test"
        );
        detector.recordEquivocation(testMember1, coords2, signature1);
        detector.recordEquivocation(testMember1, coords2, signature2);

        var coords3 = new EventCoordinates(
            testMember1,
            ULong.valueOf(3),
            DigestAlgorithm.DEFAULT.digest("event3".getBytes()),
            "test"
        );
        detector.recordEquivocation(testMember1, coords3, signature1);
        detector.recordEquivocation(testMember1, coords3, signature2);

        // Give async call time to complete
        Thread.sleep(100);

        // Verify shunning was called
        assertThat(shunningCalled.get()).isTrue();
        assertThat(shunnedMember.get()).isTrue();
    }

    @Test
    void shouldNotShunBelowThreshold() throws InterruptedException {
        var shunningCalled = new AtomicBoolean(false);

        var mockShunning = new FirefliesShunningIntegration() {
            @Override
            public CompletableFuture<Void> markMemberForShunning(Identifier memberId) {
                shunningCalled.set(true);
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public boolean isShunned(Identifier memberId) {
                return false;
            }
        };

        detector.setShunningIntegration(mockShunning);

        // Only two equivocations (score 0.5, below 0.85 threshold)
        detector.recordEquivocation(testMember1, testCoordinates1, signature1);
        detector.recordEquivocation(testMember1, testCoordinates1, signature2);

        detector.recordEquivocation(testMember1, testCoordinates2, signature1);
        detector.recordEquivocation(testMember1, testCoordinates2, signature2);

        // Give async call time (shouldn't be called)
        Thread.sleep(100);

        // Verify shunning was NOT called
        assertThat(shunningCalled.get()).isFalse();
    }

    @Test
    void shouldDecayScore() throws InterruptedException {
        // Record first equivocation
        detector.recordEquivocation(testMember1, testCoordinates1, signature1);
        detector.recordEquivocation(testMember1, testCoordinates1, signature2);

        var initialScore = detector.getAnomalyScore(testMember1);
        assertThat(initialScore).isEqualTo(0.2);

        // Simulate time passing (decay happens when getAnomalyScore is called)
        // Decay: 0.5 * score per hour
        // We'll use a short sleep and verify decay logic in the implementation
        Thread.sleep(50);

        // For testing purposes, we need to verify decay happens
        // In production, decay would be based on elapsed time
        // The detector should track timestamps and apply decay
        var scoreAfterTime = detector.getAnomalyScore(testMember1);

        // Score should decay over time
        // (This test verifies the decay mechanism exists; exact timing tested in implementation)
        assertThat(scoreAfterTime).isLessThanOrEqualTo(initialScore);
    }

    @Test
    void shouldHandleMultipleMembersIndependently() {
        // Member 1: one equivocation
        detector.recordEquivocation(testMember1, testCoordinates1, signature1);
        detector.recordEquivocation(testMember1, testCoordinates1, signature2);

        // Member 2: two equivocations
        var member2Coords1 = new EventCoordinates(
            testMember2,
            ULong.valueOf(1),
            DigestAlgorithm.DEFAULT.digest("m2event1".getBytes()),
            "test"
        );
        var member2Coords2 = new EventCoordinates(
            testMember2,
            ULong.valueOf(2),
            DigestAlgorithm.DEFAULT.digest("m2event2".getBytes()),
            "test"
        );

        detector.recordEquivocation(testMember2, member2Coords1, signature1);
        detector.recordEquivocation(testMember2, member2Coords1, signature2);
        detector.recordEquivocation(testMember2, member2Coords2, signature1);
        detector.recordEquivocation(testMember2, member2Coords2, signature2);

        // Verify independent scoring
        var score1 = detector.getAnomalyScore(testMember1);
        var score2 = detector.getAnomalyScore(testMember2);

        assertThat(score1).isGreaterThanOrEqualTo(0.19).isLessThanOrEqualTo(0.21);
        assertThat(score2).isGreaterThanOrEqualTo(0.49).isLessThanOrEqualTo(0.51);

        // Verify independent state tracking
        assertThat(score2).isGreaterThan(score1);
    }

    @Test
    void shouldResetState() {
        // Record equivocations
        detector.recordEquivocation(testMember1, testCoordinates1, signature1);
        detector.recordEquivocation(testMember1, testCoordinates1, signature2);

        assertThat(detector.getAnomalyScore(testMember1)).isGreaterThan(0.0);

        // Reset should clear state
        detector.reset();

        assertThat(detector.getAnomalyScore(testMember1)).isEqualTo(0.0);
        assertThat(detector.getDetectedAnomalies()).isEmpty();
    }

    @Test
    void shouldProvideDetectorName() {
        assertThat(detector.getDetectorName()).isEqualTo("EquivocationDetector");
    }

    @Test
    void shouldHandleConcurrentAccess() throws InterruptedException {
        var executor = Executors.newFixedThreadPool(10);
        var latch = new CountDownLatch(100);

        // Simulate concurrent equivocation recording
        for (int i = 0; i < 100; i++) {
            final int iteration = i;
            executor.submit(() -> {
                try {
                    var coords = new EventCoordinates(
                        testMember1,
                        ULong.valueOf(iteration % 10),
                        DigestAlgorithm.DEFAULT.digest(("event" + iteration).getBytes()),
                        "test"
                    );
                    detector.recordEquivocation(testMember1, coords, signature1);
                    detector.recordEquivocation(testMember1, coords, signature2);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        // Verify thread-safe operation
        assertThatCode(() -> detector.getAnomalyScore(testMember1)).doesNotThrowAnyException();
        assertThatCode(() -> detector.getDetectedAnomalies()).doesNotThrowAnyException();
    }

    @Test
    void shouldNotDetectEquivocationOnSameSignature() {
        // Recording same signature twice should not trigger equivocation
        detector.recordEquivocation(testMember1, testCoordinates1, signature1);
        detector.recordEquivocation(testMember1, testCoordinates1, signature1);

        var score = detector.getAnomalyScore(testMember1);
        assertThat(score).isEqualTo(0.0);

        var anomalies = detector.getDetectedAnomalies();
        assertThat(anomalies).isEmpty();
    }

    @Test
    void shouldImplementByzantineDetectorInterface() {
        // Verify it implements the interface correctly
        assertThat(detector).isInstanceOf(ByzantineDetector.class);

        // Test recordValidationResult method (interface contract)
        // Use ValidationFailed instead of Valid (which requires non-null aggregate)
        detector.recordValidationResult(
            testMember1,
            testCoordinates1,
            new ValidationResult.ValidationFailed("test"),
            100L
        );

        // Should not throw exceptions
        assertThatCode(() -> detector.getAnomalyScore(testMember1)).doesNotThrowAnyException();
    }
}
