/*
 * Copyright (c) 2025, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.validation;

import com.codahale.metrics.MetricRegistry;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.WitnessReceiptTestHelper;
import com.hellblazer.delos.witness.certification.ReceiptSignatureAggregator;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Byzantine Failure Scenario Tests (Phase 1B-3 D-1)
 * <p>
 * Comprehensive test suite for Byzantine behavior detection and handling:
 * - BLS signature failures and tracking
 * - Equivocation detection across multiple scenarios
 * - Signature forgery and malformed signatures
 * - Shunning propagation and integration
 * - Recovery mechanisms after Byzantine behavior
 * </p>
 */
class ByzantineFailureScenarioTest {

    private ByzantineWitnessDetector detector;
    private MockFirefliesView mockFirefliesView;
    private FirefliesShunningIntegration shunningIntegration;
    private MetricRegistry metricRegistry;
    private WitnessReceiptTestHelper helper;
    private SecureRandom entropy;

    @BeforeEach
    void setUp() {
        metricRegistry = new MetricRegistry();
        detector = new ByzantineWitnessDetector(metricRegistry);
        mockFirefliesView = new MockFirefliesView();
        shunningIntegration = new FirefliesShunningIntegrationImpl(mockFirefliesView);
        detector.setShunningIntegration(shunningIntegration);
        helper = new WitnessReceiptTestHelper();
        entropy = new SecureRandom(new byte[]{42}); // Deterministic seed
    }

    // ============================================================
    // Category 1: BLS Signature Failures (6 tests)
    // ============================================================

    @Test
    @DisplayName("BLS signature failure triggers tracking - single failure recorded")
    void testBlsSignatureFailureTriggersTracking() {
        // Arrange
        var memberId = createMemberId(0);

        // Act: Record single BLS failure
        detector.recordBlsValidationFailure(memberId, "Invalid BLS signature");

        // Assert: Failure count incremented
        assertThat(detector.getBlsFailureCount(memberId))
            .isEqualTo(1);

        // Verify: Not yet shunned (threshold is 5)
        assertThat(detector.shouldShun(memberId))
            .isFalse();

        // Verify: Metrics not yet indicating shunning
        assertThat(mockFirefliesView.getShunCallCount())
            .isEqualTo(0);
    }

    @Test
    @DisplayName("BLS failure accumulation to shunning - 5 failures trigger shunning")
    void testBlsFailureAccumulationToShunning() throws Exception {
        // Arrange
        var memberId = createMemberId(0);

        // Act: Record 5 BLS failures
        for (int i = 0; i < 5; i++) {
            detector.recordBlsValidationFailure(memberId, "Invalid BLS signature #" + i);
        }

        // Allow async shunning to complete
        Thread.sleep(100);

        // Assert: Failure count at threshold
        assertThat(detector.getBlsFailureCount(memberId))
            .isEqualTo(5);

        // Verify: Member marked for shunning
        assertThat(detector.shouldShun(memberId))
            .isTrue();

        // Verify: Fireflies shunning triggered
        assertThat(mockFirefliesView.getShunCallCount())
            .isEqualTo(1);
        assertThat(shunningIntegration.isShunned(memberId))
            .isTrue();
    }

    @Test
    @DisplayName("BLS failure count per member - independent counts per member")
    void testBlsFailureCountPerMember() {
        // Arrange
        var member1 = createMemberId(0);
        var member2 = createMemberId(1);
        var member3 = createMemberId(2);

        // Act: Record different failure counts for each member
        detector.recordBlsValidationFailure(member1, "Failure 1");
        detector.recordBlsValidationFailure(member1, "Failure 2");

        detector.recordBlsValidationFailure(member2, "Failure 1");
        detector.recordBlsValidationFailure(member2, "Failure 2");
        detector.recordBlsValidationFailure(member2, "Failure 3");

        detector.recordBlsValidationFailure(member3, "Failure 1");

        // Assert: Each member has independent count
        assertThat(detector.getBlsFailureCount(member1)).isEqualTo(2);
        assertThat(detector.getBlsFailureCount(member2)).isEqualTo(3);
        assertThat(detector.getBlsFailureCount(member3)).isEqualTo(1);

        // Verify: None yet shunned (all below threshold of 5)
        assertThat(detector.shouldShun(member1)).isFalse();
        assertThat(detector.shouldShun(member2)).isFalse();
        assertThat(detector.shouldShun(member3)).isFalse();
    }

    @Test
    @DisplayName("BLS failure clears after TTL - stale failures age out")
    void testBlsFailureClearsAfterTtl() throws Exception {
        // Arrange
        var memberId = createMemberId(0);

        // Act: Record failures
        detector.recordBlsValidationFailure(memberId, "Failure 1");
        detector.recordBlsValidationFailure(memberId, "Failure 2");

        // Verify initial count
        assertThat(detector.getBlsFailureCount(memberId)).isEqualTo(2);

        // Simulate TTL expiry (200ms TTL)
        Thread.sleep(250);

        // Clear stale failures
        detector.clearStaleFailures(200);

        // Assert: Failures cleared
        assertThat(detector.getBlsFailureCount(memberId)).isEqualTo(0);
        assertThat(detector.shouldShun(memberId)).isFalse();
    }

    @Test
    @DisplayName("BLS failure triggers Fireflies shunning - integration with Fireflies")
    void testBlsFailureTriggersFirefliesShunning() throws Exception {
        // Arrange
        var memberId = createMemberId(0);

        // Act: Trigger shunning threshold
        for (int i = 0; i < 5; i++) {
            detector.recordBlsValidationFailure(memberId, "Failure #" + i);
        }

        // Allow async completion
        Thread.sleep(100);

        // Assert: Fireflies View called
        assertThat(mockFirefliesView.getShunCallCount()).isEqualTo(1);
        assertThat(mockFirefliesView.isShunned(memberId)).isTrue();

        // Verify: Shunning integration reports member as shunned
        assertThat(shunningIntegration.isShunned(memberId)).isTrue();
    }

    @Test
    @DisplayName("BLS failure does not affect other members - isolation verified")
    void testBlsFailureDoesNotAffectOtherMembers() throws Exception {
        // Arrange
        var byzantineMember = createMemberId(0);
        var honestMember1 = createMemberId(1);
        var honestMember2 = createMemberId(2);

        // Act: Only Byzantine member triggers threshold
        for (int i = 0; i < 5; i++) {
            detector.recordBlsValidationFailure(byzantineMember, "Failure #" + i);
        }

        // Record single failures for honest members
        detector.recordBlsValidationFailure(honestMember1, "Single failure");
        detector.recordBlsValidationFailure(honestMember2, "Single failure");

        Thread.sleep(100);

        // Assert: Only Byzantine member shunned
        assertThat(detector.shouldShun(byzantineMember)).isTrue();
        assertThat(detector.shouldShun(honestMember1)).isFalse();
        assertThat(detector.shouldShun(honestMember2)).isFalse();

        // Verify: Fireflies only shunned Byzantine member
        assertThat(mockFirefliesView.getShunCallCount()).isEqualTo(1);
        assertThat(shunningIntegration.isShunned(byzantineMember)).isTrue();
        assertThat(shunningIntegration.isShunned(honestMember1)).isFalse();
        assertThat(shunningIntegration.isShunned(honestMember2)).isFalse();
    }

    // ============================================================
    // Category 2: Equivocation Scenarios (5 tests)
    // ============================================================

    @Test
    @DisplayName("Equivocation with different BLS signatures - same event, different sigs")
    void testEquivocationWithDifferentBlsSignatures() {
        // Arrange
        var event = createTestEvent(0);
        var witnessId = createMemberId(0);
        var signature1 = createSignatureDigest(1);
        var signature2 = createSignatureDigest(2);

        // Act: Detect equivocation
        var result = detector.detectEquivocation(event, witnessId, signature1, signature2);

        // Assert: Equivocation detected
        assertThat(result).isInstanceOf(ByzantineWitnessDetector.ByzantineDetectionResult.EquivocationDetected.class);

        var detected = (ByzantineWitnessDetector.ByzantineDetectionResult.EquivocationDetected) result;
        assertThat(detected.evidence().event()).isEqualTo(event);
        assertThat(detected.evidence().witnessId()).isEqualTo(witnessId);
        assertThat(detected.evidence().signature1()).isEqualTo(signature1);
        assertThat(detected.evidence().signature2()).isEqualTo(signature2);

        // Verify: Metrics incremented
        var stats = detector.getStats();
        assertThat(stats.equivocationCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Equivocation across multiple recipients - Byzantine sends different versions")
    void testEquivocationAcrossMultipleRecipients() {
        // Arrange
        var event = createTestEvent(0);
        var byzantineWitness = createMemberId(0);

        // Simulate Byzantine witness sending different signatures to different recipients
        var signatureToRecipient1 = createSignatureDigest(100);
        var signatureToRecipient2 = createSignatureDigest(200);
        var signatureToRecipient3 = createSignatureDigest(300);

        // Act: Detect equivocation between recipients 1 and 2
        var result1 = detector.detectEquivocation(event, byzantineWitness, signatureToRecipient1, signatureToRecipient2);

        // Detect equivocation between recipients 2 and 3
        var result2 = detector.detectEquivocation(event, byzantineWitness, signatureToRecipient2, signatureToRecipient3);

        // Assert: Both equivocations detected
        assertThat(result1).isInstanceOf(ByzantineWitnessDetector.ByzantineDetectionResult.EquivocationDetected.class);
        assertThat(result2).isInstanceOf(ByzantineWitnessDetector.ByzantineDetectionResult.EquivocationDetected.class);

        // Verify: Multiple equivocations recorded
        var stats = detector.getStats();
        assertThat(stats.equivocationCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Equivocation detection with proof - verifiable equivocation proof")
    void testEquivocationDetectionWithProof() {
        // Arrange
        var event = createTestEvent(0);
        var witnessId = createMemberId(0);
        var sig1 = createSignatureDigest(1);
        var sig2 = createSignatureDigest(2);

        // Act: Detect and generate report
        var result = detector.detectEquivocation(event, witnessId, sig1, sig2);
        assertThat(result).isInstanceOf(ByzantineWitnessDetector.ByzantineDetectionResult.EquivocationDetected.class);

        var detected = (ByzantineWitnessDetector.ByzantineDetectionResult.EquivocationDetected) result;
        var evidence = detected.evidence();

        // Generate forensic report
        var report = ByzantineWitnessDetector.generateByzantineReport(evidence);

        // Assert: Report contains verifiable proof
        assertThat(report).contains("Byzantine Equivocation Report");
        assertThat(report).contains("EQUIVOCATION");
        assertThat(report).contains(witnessId.toString());
        assertThat(report).contains(sig1.toString());
        assertThat(report).contains(sig2.toString());
        assertThat(report).contains("Witness signed multiple conflicting receipts");
    }

    @Test
    @DisplayName("Equivocation triggers immediate shunning - fast path for equivocation")
    void testEquivocationTriggersImmediateShunning() throws Exception {
        // Arrange
        var event = createTestEvent(0);
        var witnessId = createMemberId(0);
        var sig1 = createSignatureDigest(1);
        var sig2 = createSignatureDigest(2);

        // Act: Detect equivocation and mark for exclusion
        var result = detector.detectEquivocation(event, witnessId, sig1, sig2);
        assertThat(result).isInstanceOf(ByzantineWitnessDetector.ByzantineDetectionResult.EquivocationDetected.class);

        // Manually trigger shunning (equivocation is severe)
        detector.markForExclusion(witnessId);
        shunningIntegration.markMemberForShunning(witnessId).get(1, TimeUnit.SECONDS);

        // Assert: Member immediately shunned
        assertThat(detector.isMarkedForExclusion(witnessId)).isTrue();
        assertThat(shunningIntegration.isShunned(witnessId)).isTrue();
        assertThat(mockFirefliesView.isShunned(witnessId)).isTrue();
    }

    @Test
    @DisplayName("No false equivocation on retransmission - idempotent handling")
    void testNoFalseEquivocationOnRetransmission() {
        // Arrange
        var event = createTestEvent(0);
        var witnessId = createMemberId(0);
        var signature = createSignatureDigest(1);

        // Act: "Retransmit" same signature (should not be equivocation)
        var result = detector.detectEquivocation(event, witnessId, signature, signature);

        // Assert: No equivocation detected
        assertThat(result).isInstanceOf(ByzantineWitnessDetector.ByzantineDetectionResult.NoByzantineBehavior.class);

        // Verify: No metrics incremented
        var stats = detector.getStats();
        assertThat(stats.equivocationCount()).isEqualTo(0);
    }

    // ============================================================
    // Category 3: Signature Forgery (4 tests)
    // ============================================================

    @Test
    @DisplayName("Forgery detection in aggregated receipt - invalid sig in aggregate")
    void testForgeryDetectionInAggregatedReceipt() {
        // Arrange
        var event = createTestEvent(0);
        var witnessId = createMemberId(0);
        var forgedSignature = createJohnHancock(1);
        var verificationError = "BLS signature verification failed: invalid point on curve";

        // Act: Detect forgery
        var result = detector.detectSignatureForgery(event, witnessId, forgedSignature, verificationError);

        // Assert: Forgery detected
        assertThat(result).isInstanceOf(ByzantineWitnessDetector.ByzantineDetectionResult.SignatureForgeryDetected.class);

        var detected = (ByzantineWitnessDetector.ByzantineDetectionResult.SignatureForgeryDetected) result;
        assertThat(detected.evidence().witnessId()).isEqualTo(witnessId);
        assertThat(detected.evidence().signature()).isEqualTo(forgedSignature);
        assertThat(detected.evidence().verificationError()).isEqualTo(verificationError);

        // Verify: Metrics incremented
        var stats = detector.getStats();
        assertThat(stats.signatureForgeryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Forgery triggers forensic report - evidence collection")
    void testForgeryTriggersForensicReport() {
        // Arrange
        var event = createTestEvent(0);
        var witnessId = createMemberId(0);
        var forgedSignature = createJohnHancock(1);
        var verificationError = "Public key mismatch";

        // Act: Detect forgery and generate report
        var result = detector.detectSignatureForgery(event, witnessId, forgedSignature, verificationError);
        assertThat(result).isInstanceOf(ByzantineWitnessDetector.ByzantineDetectionResult.SignatureForgeryDetected.class);

        var detected = (ByzantineWitnessDetector.ByzantineDetectionResult.SignatureForgeryDetected) result;
        var report = ByzantineWitnessDetector.generateByzantineReport(detected.evidence());

        // Assert: Report contains forensic details
        assertThat(report).contains("Byzantine Signature Forgery Report");
        assertThat(report).contains("SIGNATURE_FORGERY");
        assertThat(report).contains(witnessId.toString());
        assertThat(report).contains("Public key mismatch");
        assertThat(report).contains("Witness claimed signature but verification failed");
    }

    @Test
    @DisplayName("Forgery with valid-looking signature - malformed but plausible")
    void testForgeryWithValidLookingSignature() {
        // Arrange: Create a signature that looks structurally valid but fails verification
        var event = createTestEvent(0);
        var witnessId = createMemberId(0);

        // Create a well-formed signature that will fail cryptographic verification
        var validLookingButInvalidSig = createJohnHancock(999);
        var verificationError = "Signature verification failed: point not on BLS12-381 curve";

        // Act: Detect forgery
        var result = detector.detectSignatureForgery(event, witnessId, validLookingButInvalidSig, verificationError);

        // Assert: Forgery detected despite valid structure
        assertThat(result).isInstanceOf(ByzantineWitnessDetector.ByzantineDetectionResult.SignatureForgeryDetected.class);

        var detected = (ByzantineWitnessDetector.ByzantineDetectionResult.SignatureForgeryDetected) result;
        assertThat(detected.evidence().signature()).isEqualTo(validLookingButInvalidSig);
        assertThat(detected.evidence().verificationError()).contains("BLS12-381 curve");
    }

    @Test
    @DisplayName("Forgery bitmap without signature - threshold bypass attempt")
    void testForgeryBitmapWithoutSignature() {
        // Arrange: Create receipt with bitmap claiming signature but no actual signature
        var event = createTestEvent(0);
        var byzantineWitness = createMemberId(5);

        // Create receipt with 4 valid signatures
        var signatures = List.of(
            new ReceiptSignatureAggregator.WitnessSignature(createMemberId(0), 0, createSignatureDigest(0)),
            new ReceiptSignatureAggregator.WitnessSignature(createMemberId(1), 1, createSignatureDigest(1)),
            new ReceiptSignatureAggregator.WitnessSignature(createMemberId(2), 2, createSignatureDigest(2)),
            new ReceiptSignatureAggregator.WitnessSignature(createMemberId(3), 3, createSignatureDigest(3))
        );

        // Bitmap claims witness 5 signed (bit set) but no corresponding signature
        var bitmap = new BitSet();
        bitmap.set(0);
        bitmap.set(1);
        bitmap.set(2);
        bitmap.set(3);
        bitmap.set(5); // Byzantine claim

        var receipt = new ReceiptSignatureAggregator.AggregatedReceipt(
            event, bitmap, signatures, 5, ReceiptSignatureAggregator.AggregationStatus.PARTIAL
        );

        // Act: Detect threshold bypass
        var result = detector.detectThresholdBypass(byzantineWitness, 5, receipt);

        // Assert: Threshold bypass detected
        assertThat(result).isInstanceOf(ByzantineWitnessDetector.ByzantineDetectionResult.ThresholdBypassDetected.class);

        var detected = (ByzantineWitnessDetector.ByzantineDetectionResult.ThresholdBypassDetected) result;
        assertThat(detected.evidence().witnessId()).isEqualTo(byzantineWitness);
        assertThat(detected.evidence().committeeIndex()).isEqualTo(5);
        assertThat(detected.evidence().signatureCount()).isEqualTo(4);
        assertThat(detected.evidence().threshold()).isEqualTo(5);
    }

    // ============================================================
    // Category 4: Shunning Propagation (5 tests)
    // ============================================================

    @Test
    @DisplayName("Shunning propagates from detector to Fireflies - end-to-end flow")
    void testShunningPropagatesFromDetectorToFireflies() throws Exception {
        // Arrange
        var memberId = createMemberId(0);

        // Act: Trigger BLS threshold
        for (int i = 0; i < 5; i++) {
            detector.recordBlsValidationFailure(memberId, "Failure #" + i);
        }

        Thread.sleep(100);

        // Assert: Full propagation path
        assertThat(detector.shouldShun(memberId)).isTrue();
        assertThat(shunningIntegration.isShunned(memberId)).isTrue();
        assertThat(mockFirefliesView.isShunned(memberId)).isTrue();

        // Verify: Member in View's shunned set
        var shunnedMembers = mockFirefliesView.getShunnedMembers();
        assertThat(shunnedMembers).contains(toDigest(memberId));
    }

    @Test
    @DisplayName("Shunning idempotency - same member shunned twice")
    void testShunningIdempotency() throws Exception {
        // Arrange
        var memberId = createMemberId(0);

        // Act: Shun same member twice
        shunningIntegration.markMemberForShunning(memberId).get(1, TimeUnit.SECONDS);
        shunningIntegration.markMemberForShunning(memberId).get(1, TimeUnit.SECONDS);

        // Assert: Only one shunned entry
        assertThat(shunningIntegration.isShunned(memberId)).isTrue();

        // Verify: View called twice (idempotent operations)
        assertThat(mockFirefliesView.getShunCallCount()).isEqualTo(2);

        // Verify: Single entry in shunned set
        var shunnedMembers = mockFirefliesView.getShunnedMembers();
        assertThat(shunnedMembers).hasSize(1);
        assertThat(shunnedMembers).contains(toDigest(memberId));
    }

    @Test
    @DisplayName("Concurrent shunning requests - thread safety")
    void testConcurrentShunningRequests() throws Exception {
        // Arrange: 3 members to shun concurrently
        var member1 = createMemberId(0);
        var member2 = createMemberId(1);
        var member3 = createMemberId(2);

        var latch = new CountDownLatch(3);
        var errors = new ConcurrentHashMap<Integer, Throwable>();

        // Act: Concurrent shunning
        Thread t1 = new Thread(() -> {
            try {
                shunningIntegration.markMemberForShunning(member1).get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                errors.put(0, e);
            } finally {
                latch.countDown();
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                shunningIntegration.markMemberForShunning(member2).get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                errors.put(1, e);
            } finally {
                latch.countDown();
            }
        });

        Thread t3 = new Thread(() -> {
            try {
                shunningIntegration.markMemberForShunning(member3).get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                errors.put(2, e);
            } finally {
                latch.countDown();
            }
        });

        t1.start();
        t2.start();
        t3.start();

        // Assert: All complete successfully
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(errors).isEmpty();

        // Verify: All members shunned
        assertThat(shunningIntegration.isShunned(member1)).isTrue();
        assertThat(shunningIntegration.isShunned(member2)).isTrue();
        assertThat(shunningIntegration.isShunned(member3)).isTrue();

        assertThat(mockFirefliesView.getShunCallCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Shunning failure does not block detector - async non-blocking")
    void testShunningFailureDoesNotBlockDetector() throws Exception {
        // Arrange: Configure View to fail
        mockFirefliesView.setFailOnShun(true);
        var memberId1 = createMemberId(0);
        var memberId2 = createMemberId(1);

        // Act: Try to shun with failure
        var future1 = shunningIntegration.markMemberForShunning(memberId1);

        // Assert: Future completes exceptionally
        try {
            future1.get(1, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            assertThat(e.getCause()).isInstanceOf(RuntimeException.class);
        }

        // Verify: Detector still operational
        mockFirefliesView.setFailOnShun(false);
        var future2 = shunningIntegration.markMemberForShunning(memberId2);
        future2.get(1, TimeUnit.SECONDS);

        assertThat(shunningIntegration.isShunned(memberId2)).isTrue();
    }

    @Test
    @DisplayName("Shunning status queryable - isShunned() returns correct state")
    void testShunningStatusQueryable() throws Exception {
        // Arrange
        var shunnedMember = createMemberId(0);
        var honestMember = createMemberId(1);

        // Act: Shun only one member
        shunningIntegration.markMemberForShunning(shunnedMember).get(1, TimeUnit.SECONDS);

        // Assert: Query returns correct status
        assertThat(shunningIntegration.isShunned(shunnedMember)).isTrue();
        assertThat(shunningIntegration.isShunned(honestMember)).isFalse();

        // Verify: View status matches
        assertThat(mockFirefliesView.isShunned(shunnedMember)).isTrue();
        assertThat(mockFirefliesView.isShunned(honestMember)).isFalse();
    }

    // ============================================================
    // Category 5: Recovery Scenarios (5 tests)
    // ============================================================

    @Test
    @DisplayName("Recovery after sufficient valid signatures - suspicion clears")
    void testRecoveryAfterSufficientValidSignatures() {
        // Arrange: Member initially marked suspicious
        var memberId = createMemberId(0);
        detector.recordInvalidSignature(memberId);
        assertThat(detector.isSuspicious(memberId)).isTrue();

        // Act: Record 100 valid signatures
        for (int i = 0; i < 100; i++) {
            detector.recordValidSignature(memberId);
        }

        // Evaluate recovery
        detector.evaluateRecovery(memberId);

        // Assert: Suspicion cleared
        assertThat(detector.isSuspicious(memberId)).isFalse();
        assertThat(detector.isMarkedForExclusion(memberId)).isFalse();
    }

    @Test
    @DisplayName("Recovery requires consistent good behavior - threshold needed")
    void testRecoveryRequiresConsistentGoodBehavior() {
        // Arrange: Member marked suspicious
        var memberId = createMemberId(0);
        detector.recordInvalidSignature(memberId);
        assertThat(detector.isSuspicious(memberId)).isTrue();

        // Act: Record insufficient valid signatures (90 < 100 threshold)
        for (int i = 0; i < 90; i++) {
            detector.recordValidSignature(memberId);
        }

        detector.evaluateRecovery(memberId);

        // Assert: Still suspicious (threshold not met)
        assertThat(detector.isSuspicious(memberId)).isTrue();

        // Act: Complete threshold
        for (int i = 0; i < 10; i++) {
            detector.recordValidSignature(memberId);
        }

        detector.evaluateRecovery(memberId);

        // Assert: Now recovered
        assertThat(detector.isSuspicious(memberId)).isFalse();
    }

    @Test
    @DisplayName("No recovery while still misbehaving - mixed behavior blocks recovery")
    void testNoRecoveryWhileStillMisbehaving() {
        // Arrange: Member with mixed behavior
        var memberId = createMemberId(0);

        // Act: Record invalid signatures first to mark as suspicious
        for (int i = 0; i < 5; i++) {
            detector.recordInvalidSignature(memberId);
        }

        // Verify marked suspicious and for exclusion
        assertThat(detector.isSuspicious(memberId)).isTrue();
        detector.markForExclusion(memberId);

        // Act: Alternate between valid and invalid signatures (not enough for recovery)
        for (int i = 0; i < 50; i++) {
            detector.recordValidSignature(memberId);
            if (i % 10 == 0) {
                detector.recordInvalidSignature(memberId); // Periodic misbehavior
            }
        }

        detector.evaluateRecovery(memberId);

        // Assert: Still suspicious - didn't reach 100 valid signatures threshold
        // (only 50 valid, with 5 invalid interspersed)
        assertThat(detector.isSuspicious(memberId)).isTrue();
        assertThat(detector.isMarkedForExclusion(memberId)).isTrue();
    }

    @Test
    @DisplayName("Recovery resets Byzantine counters - clean slate")
    void testRecoveryResetsByzantineCounters() {
        // Arrange: Member with BLS failures below threshold
        var memberId = createMemberId(0);
        detector.recordBlsValidationFailure(memberId, "Failure 1");
        detector.recordBlsValidationFailure(memberId, "Failure 2");
        detector.recordBlsValidationFailure(memberId, "Failure 3");

        assertThat(detector.getBlsFailureCount(memberId)).isEqualTo(3);

        // Act: Simulate TTL-based cleanup (recovery mechanism)
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        detector.clearStaleFailures(200);

        // Assert: Counters reset
        assertThat(detector.getBlsFailureCount(memberId)).isEqualTo(0);
        assertThat(detector.shouldShun(memberId)).isFalse();
    }

    @Test
    @DisplayName("Recovery does not undo forensic evidence - evidence preserved")
    void testRecoveryDoesNotUndoForensicEvidence() {
        // Arrange: Member commits equivocation
        var event = createTestEvent(0);
        var memberId = createMemberId(0);
        var sig1 = createSignatureDigest(1);
        var sig2 = createSignatureDigest(2);

        // Act: Detect equivocation (creates forensic evidence)
        var result = detector.detectEquivocation(event, memberId, sig1, sig2);
        assertThat(result).isInstanceOf(ByzantineWitnessDetector.ByzantineDetectionResult.EquivocationDetected.class);

        var statsBeforeRecovery = detector.getStats();
        assertThat(statsBeforeRecovery.equivocationCount()).isEqualTo(1);

        // Act: Member recovers through valid signatures
        for (int i = 0; i < 100; i++) {
            detector.recordValidSignature(memberId);
        }
        detector.evaluateRecovery(memberId);

        // Assert: Suspicion cleared
        assertThat(detector.isSuspicious(memberId)).isFalse();

        // Verify: Forensic evidence PRESERVED
        var statsAfterRecovery = detector.getStats();
        assertThat(statsAfterRecovery.equivocationCount()).isEqualTo(1);

        // Verify: Can still generate forensic report
        var detected = (ByzantineWitnessDetector.ByzantineDetectionResult.EquivocationDetected) result;
        var report = ByzantineWitnessDetector.generateByzantineReport(detected.evidence());
        assertThat(report).contains("Byzantine Equivocation Report");
        assertThat(report).contains(memberId.toString());
    }

    // ============================================================
    // Test Utilities
    // ============================================================

    private EventCoordinates createTestEvent(int index) {
        var identifier = createMemberId(index);
        var digest = DigestAlgorithm.BLAKE3_256.digest(("test-event-" + index).getBytes());
        return new EventCoordinates(identifier, ULong.valueOf(index), digest, "icp");
    }

    private Identifier createMemberId(int index) {
        var digest = DigestAlgorithm.BLAKE3_256.digest(("member-" + index).getBytes());
        return new SelfAddressingIdentifier(digest);
    }

    private Digest createSignatureDigest(int index) {
        return DigestAlgorithm.BLAKE3_256.digest(("signature-" + index).getBytes());
    }

    private JohnHancock createJohnHancock(int index) {
        var signature = createSignatureDigest(index);
        return new JohnHancock(SignatureAlgorithm.ED_25519, signature.getBytes(), ULong.valueOf(0));
    }

    private Digest toDigest(Identifier identifier) {
        if (identifier instanceof SelfAddressingIdentifier sai) {
            return sai.getDigest();
        }
        throw new IllegalArgumentException("Cannot convert identifier to digest: " + identifier);
    }

    /**
     * Mock Fireflies View for testing shunning integration.
     * <p>
     * Simulates View.shunMember() behavior without full Fireflies infrastructure.
     * Tracks shunned members and call counts for test verification.
     * </p>
     */
    private static class MockFirefliesView implements FirefliesShunningIntegrationImpl.FirefliesViewAdapter {
        private final Set<Digest> shunnedMembers = ConcurrentHashMap.newKeySet();
        private final AtomicInteger shunCallCount = new AtomicInteger(0);
        private volatile boolean failOnShun = false;

        @Override
        public CompletableFuture<Void> shunMember(Identifier memberId) {
            shunCallCount.incrementAndGet();

            if (failOnShun) {
                return CompletableFuture.failedFuture(
                    new RuntimeException("Mock View failure for testing")
                );
            }

            // Convert Identifier to Digest for storage
            var digest = ((SelfAddressingIdentifier) memberId).getDigest();
            shunnedMembers.add(digest);

            return CompletableFuture.completedFuture(null);
        }

        public boolean isShunned(Identifier memberId) {
            var digest = ((SelfAddressingIdentifier) memberId).getDigest();
            return shunnedMembers.contains(digest);
        }

        public Set<Digest> getShunnedMembers() {
            return Set.copyOf(shunnedMembers);
        }

        public int getShunCallCount() {
            return shunCallCount.get();
        }

        public void setFailOnShun(boolean fail) {
            this.failOnShun = fail;
        }
    }
}
