/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import com.hellblazer.delos.cryptography.bls.BLSAggregate;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.witness.aggregation.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests for CoalitionDetector.
 *
 * @author hal.hildebrand
 */
class CoalitionDetectorTest {

    private ByzantineDetectorConfig config;
    private CoalitionDetector detector;
    private EquivocationDetector equivocationDetector;
    private TimingAttackDetector timingAttackDetector;
    private ReplayProtectionDetector replayDetector;

    private Identifier member1;
    private Identifier member2;
    private Identifier member3;
    private Identifier member4;
    private EventCoordinates coordinates;
    private ValidationResult validResult;

    @BeforeEach
    void setUp() {
        config = ByzantineDetectorConfig.defaults();
        var metrics = new NoOpByzantineDetectionMetrics();

        // Create real detector instances
        equivocationDetector = new EquivocationDetector(config, metrics);
        timingAttackDetector = new TimingAttackDetector(config, metrics);
        replayDetector = new ReplayProtectionDetector(config, metrics);

        detector = new CoalitionDetector(config, metrics);
        detector.setDetectorReferences(equivocationDetector, timingAttackDetector, replayDetector);

        // Setup test identifiers
        member1 = mock(Identifier.class);
        member2 = mock(Identifier.class);
        member3 = mock(Identifier.class);
        member4 = mock(Identifier.class);

        coordinates = mock(EventCoordinates.class);

        // Create a dummy BLSAggregate for testing
        var dummySignature = new BLSSignature(new byte[96]);
        var dummyBitmap = new byte[]{1};
        var dummyAggregate = new BLSAggregate(dummySignature, dummyBitmap);
        validResult = new ValidationResult.Valid(dummyAggregate);
    }

    @Test
    void shouldStartWithZeroScore() {
        // Given: Fresh detector with no data

        // When: Get anomaly score for member with no history
        var score = detector.getAnomalyScore(member1);

        // Then: Score should be 0.0
        assertThat(score).isEqualTo(0.0);
    }

    @Test
    void shouldDetectTwoMemberCoalition() {
        // Given: Two members with equivocation anomalies
        simulateEquivocationForMember(equivocationDetector, member1, coordinates);
        simulateEquivocationForMember(equivocationDetector, member2, coordinates);

        // When: Record validation results
        detector.recordValidationResult(member1, coordinates, validResult, 10);
        detector.recordValidationResult(member2, coordinates, validResult, 10);

        // Then: Should detect coalition (2 members with matching anomaly pattern)
        var score1 = detector.getAnomalyScore(member1);
        var score2 = detector.getAnomalyScore(member2);

        assertThat(score1).isGreaterThan(0.0);
        assertThat(score2).isGreaterThan(0.0);

        // Verify anomaly detection
        var anomalies = detector.getDetectedAnomalies();
        assertThat(anomalies).isNotEmpty();

        var coalitionAnomalies = anomalies.stream()
            .filter(a -> a.type() == AnomalyType.COORDINATED_ATTACK)
            .toList();

        assertThat(coalitionAnomalies).isNotEmpty();
    }

    @Test
    void shouldRequireMinimumCommitteeParticipation() {
        // Given: Single member with anomaly (not enough for coalition)
        simulateEquivocationForMember(equivocationDetector, member1, coordinates);

        // When: Record validation result
        detector.recordValidationResult(member1, coordinates, validResult, 10);

        // Then: Should not detect coalition (need minimum 2 members)
        var score = detector.getAnomalyScore(member1);

        // Score should be low or zero since no coalition pattern
        assertThat(score).isLessThan(config.warningAnomalyScore());

        // Should not have COORDINATED_ATTACK anomalies
        var anomalies = detector.getDetectedAnomalies();
        var coalitionAnomalies = anomalies.stream()
            .filter(a -> a.type() == AnomalyType.COORDINATED_ATTACK)
            .toList();

        assertThat(coalitionAnomalies).isEmpty();
    }

    @Test
    void shouldScoreBasedOnDetectorAgreement() {
        // Given: Member with anomalies from multiple detectors
        simulateEquivocationForMember(equivocationDetector, member1, coordinates);
        simulateTimingAnomalyForMember(timingAttackDetector, member1, coordinates);

        // When: Record validation results
        detector.recordValidationResult(member1, coordinates, validResult, 10);

        // Add another member with same pattern for coalition
        simulateEquivocationForMember(equivocationDetector, member2, coordinates);
        simulateTimingAnomalyForMember(timingAttackDetector, member2, coordinates);
        detector.recordValidationResult(member2, coordinates, validResult, 10);

        // Then: Score should be higher due to multiple detectors agreeing
        var score1 = detector.getAnomalyScore(member1);
        var score2 = detector.getAnomalyScore(member2);

        assertThat(score1).isGreaterThan(0.3);  // Multiple detectors boost score
        assertThat(score2).isGreaterThan(0.3);
    }

    @Test
    void shouldApplyCommitteeParticipationBonus() {
        // Given: Multiple committee members with anomalies
        // Simulate committee of 9 members (for 1/3, 2/3, 3/3 thresholds)

        // Test 1/3 participation (3 members)
        simulateEquivocationForMember(equivocationDetector, member1, coordinates);
        simulateEquivocationForMember(equivocationDetector, member2, coordinates);
        simulateEquivocationForMember(equivocationDetector, member3, coordinates);

        detector.recordValidationResult(member1, coordinates, validResult, 10);
        detector.recordValidationResult(member2, coordinates, validResult, 10);
        detector.recordValidationResult(member3, coordinates, validResult, 10);

        // When: Get scores
        var score1 = detector.getAnomalyScore(member1);

        // Then: Should have committee participation bonus
        assertThat(score1).isGreaterThan(0.0);

        // Add more members to test 2/3 participation bonus
        var member5 = mock(Identifier.class);
        var member6 = mock(Identifier.class);

        simulateEquivocationForMember(equivocationDetector, member4, coordinates);
        simulateEquivocationForMember(equivocationDetector, member5, coordinates);
        simulateEquivocationForMember(equivocationDetector, member6, coordinates);

        detector.recordValidationResult(member4, coordinates, validResult, 10);
        detector.recordValidationResult(member5, coordinates, validResult, 10);
        detector.recordValidationResult(member6, coordinates, validResult, 10);

        var score4 = detector.getAnomalyScore(member4);

        // Score should be higher with more participants
        assertThat(score4).isGreaterThanOrEqualTo(score1);
    }

    @Test
    void shouldDetectCoordinatedEquivocation() {
        // Given: Multiple members all showing equivocation
        simulateEquivocationForMember(equivocationDetector, member1, coordinates);
        simulateEquivocationForMember(equivocationDetector, member2, coordinates);

        // When: Record validation results
        detector.recordValidationResult(member1, coordinates, validResult, 10);
        detector.recordValidationResult(member2, coordinates, validResult, 10);

        // Then: Should detect coordinated equivocation attack
        var anomalies = detector.getDetectedAnomalies();
        var coordinatedAnomalies = anomalies.stream()
            .filter(a -> a.type() == AnomalyType.COORDINATED_ATTACK)
            .filter(a -> a.evidence().stream().anyMatch(e -> e.contains("EQUIVOCATION")))
            .toList();

        assertThat(coordinatedAnomalies).isNotEmpty();
    }

    @Test
    void shouldDetectMixedAnomalyPatterns() {
        // Given: Different members with different anomaly types
        simulateEquivocationForMember(equivocationDetector, member1, coordinates);
        simulateTimingAnomalyForMember(timingAttackDetector, member2, coordinates);
        simulateReplayForMember(replayDetector, member3, coordinates);

        // When: Record validation results
        detector.recordValidationResult(member1, coordinates, validResult, 10);
        detector.recordValidationResult(member2, coordinates, validResult, 10);
        detector.recordValidationResult(member3, coordinates, validResult, 10);

        // Then: Should detect coalition despite mixed anomaly types
        var score1 = detector.getAnomalyScore(member1);
        var score2 = detector.getAnomalyScore(member2);
        var score3 = detector.getAnomalyScore(member3);

        // At least some scores should be elevated
        assertThat(score1 + score2 + score3).isGreaterThan(0.0);

        // Should detect some coordinated pattern
        var anomalies = detector.getDetectedAnomalies();
        assertThat(anomalies).isNotEmpty();
    }

    @Test
    void shouldHandleLazyInitializationOfDetectors() {
        // Given: Detector created without references
        var detectorWithoutRefs = new CoalitionDetector(config, new NoOpByzantineDetectionMetrics());

        // When: Try to get score before setting references
        var scoreBefore = detectorWithoutRefs.getAnomalyScore(member1);

        // Then: Should handle gracefully (return 0.0)
        assertThat(scoreBefore).isEqualTo(0.0);

        // When: Set references and simulate anomalies
        detectorWithoutRefs.setDetectorReferences(equivocationDetector, timingAttackDetector, replayDetector);

        simulateEquivocationForMember(equivocationDetector, member1, coordinates);
        simulateEquivocationForMember(equivocationDetector, member2, coordinates);

        detectorWithoutRefs.recordValidationResult(member1, coordinates, validResult, 10);
        detectorWithoutRefs.recordValidationResult(member2, coordinates, validResult, 10);

        // Then: Should now detect coalition
        var scoreAfter = detectorWithoutRefs.getAnomalyScore(member1);
        assertThat(scoreAfter).isGreaterThan(0.0);
    }

    // Helper methods to simulate anomalies in other detectors

    private void simulateEquivocationForMember(EquivocationDetector detector, Identifier memberId, EventCoordinates coords) {
        // Simulate equivocation by recording different signatures at same coordinates
        // Need 3 equivocations to reach 0.85 score (above 0.7 warning threshold)
        byte[] sig1 = new byte[]{1, 2, 3, 4};
        byte[] sig2 = new byte[]{5, 6, 7, 8};
        byte[] sig3 = new byte[]{9, 10, 11, 12};
        byte[] sig4 = new byte[]{13, 14, 15, 16};

        detector.recordEquivocation(memberId, coords, sig1);
        detector.recordEquivocation(memberId, coords, sig2);  // First equivocation (score: 0.2)
        detector.recordEquivocation(memberId, coords, sig3);  // Second equivocation (score: 0.5)
        detector.recordEquivocation(memberId, coords, sig4);  // Third equivocation (score: 0.85)
    }

    private void simulateTimingAnomalyForMember(TimingAttackDetector detector, Identifier memberId, EventCoordinates coords) {
        // Simulate timing anomaly by recording high latency validations
        // Need high latency (800ms+) to reach score > 0.7
        for (int i = 0; i < 10; i++) {
            detector.recordValidationResult(memberId, coords, validResult, 900);  // 900ms latency (score ~0.85)
        }
    }

    private void simulateReplayForMember(ReplayProtectionDetector detector, Identifier memberId, EventCoordinates coords) {
        // Simulate replay by recording same validation result multiple times
        // Need 4 replays to reach score 0.85 (above 0.7 warning threshold)
        var replaySignature = new BLSSignature(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28, 29, 30,
            31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
            41, 42, 43, 44, 45, 46, 47, 48, 49, 50,
            51, 52, 53, 54, 55, 56, 57, 58, 59, 60,
            61, 62, 63, 64, 65, 66, 67, 68, 69, 70,
            71, 72, 73, 74, 75, 76, 77, 78, 79, 80,
            81, 82, 83, 84, 85, 86, 87, 88, 89, 90,
            91, 92, 93, 94, 95, 96});
        var replayBitmap = new byte[]{1};
        var replayAggregate = new BLSAggregate(replaySignature, replayBitmap);
        var replayResult = new ValidationResult.Valid(replayAggregate);

        // Record multiple times to trigger replay detection (need 4+ for score >= 0.85)
        for (int i = 0; i < 5; i++) {
            detector.recordValidationResult(memberId, coords, replayResult, 10);
        }
    }
}
