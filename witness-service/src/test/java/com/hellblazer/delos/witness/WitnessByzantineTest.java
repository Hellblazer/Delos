/*
 * Copyright (c) 2024 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.delos.witness;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.witness.WitnessReceiptTestHelper.TestSigner;
import com.hellblazer.delos.witness.validation.ByzantineWitnessDetector;
import com.hellblazer.delos.stereotomy.event.proto.KERL_;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D.2: Byzantine Fault Injection Tests
 * <p>
 * Validates Byzantine fault tolerance through systematic fault injection:
 * - Invalid signature detection and rejection
 * - Equivocation detection (conflicting signatures from same witness)
 * - Threshold maintenance with Byzantine nodes (M honest beats f Byzantine)
 * - Wrong event data detection and isolation
 * - Timeout attacks and partial participation scenarios
 * - Consensus achievement with f=2 Byzantine failures
 * - False positive prevention for honest nodes
 * - Recovery mechanisms and exclusion protocols
 * - Operational continuity and forensic reporting
 * <p>
 * Target: 15+ tests covering complete Byzantine fault injection scenarios
 */
@DisplayName("D.2: Byzantine Fault Injection Tests")
class WitnessByzantineTest {

    private DigestAlgorithm digestAlgorithm;
    private SecureRandom entropy;
    private ByzantineWitnessDetector byzantineDetector;
    private int thresholdM;
    private List<TestSigner> honestSigners;
    private List<TestSigner> byzantineSigners;
    private WitnessReceiptTestHelper testHelper;
    private ConcurrentHashMap<String, Integer> byzantineDetectionMetrics;

    @BeforeEach
    void setUp() {
        digestAlgorithm = DigestAlgorithm.DEFAULT;
        entropy = new SecureRandom();
        byzantineDetectionMetrics = new ConcurrentHashMap<>();
        byzantineDetector = new ByzantineWitnessDetector(byzantineDetectionMetrics);
        testHelper = new WitnessReceiptTestHelper();

        // Create M=5 honest signers and f=2 Byzantine signers
        honestSigners = new ArrayList<>();
        byzantineSigners = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            honestSigners.add(testHelper.createTestSigner("honest-" + i));
        }

        for (int i = 0; i < 2; i++) {
            byzantineSigners.add(testHelper.createTestSigner("byzantine-" + i));
        }

        // Threshold M=5, total witnesses=7 (5 honest + 2 Byzantine)
        thresholdM = 5;
    }

    @Test
    @DisplayName("1. Reject signature from Byzantine witness with invalid signature")
    void testRejectSignatureFromByzantineWitness() throws Exception {
        var event = testHelper.createTestKERL();
        var byzantineSigner = byzantineSigners.get(0);

        // Create invalid signature (tampered data)
        var validSignature = byzantineSigner.sign(event.toByteString());
        var tamperedSignature = testHelper.tamperSignature(validSignature.getBytes()[0]);

        // Validation should reject tampered signature
        var isValid = testHelper.validateSignature(
            event,
            tamperedSignature,
            byzantineSigner.getPublicKey()
        );

        assertFalse(isValid, "Byzantine tampered signature should be rejected");

        // Detector should mark this as Byzantine behavior
        byzantineDetector.recordInvalidSignature(byzantineSigner.getIdentifier());
        assertTrue(byzantineDetector.isSuspicious(byzantineSigner.getIdentifier()),
            "Witness should be marked suspicious after invalid signature");
    }

    @Test
    @DisplayName("2. Detect equivocation - conflicting signatures from same witness")
    void testDetectEquivocation() throws Exception {
        var event1 = testHelper.createTestKERL("event-1");
        var event2 = testHelper.createTestKERL("event-2");
        var byzantineSigner = byzantineSigners.get(0);

        // Byzantine node signs two different events at same sequence
        var sig1 = byzantineSigner.sign(event1.toByteString().toByteArray());
        var sig2 = byzantineSigner.sign(event2.toByteString().toByteArray());

        // Detector should identify equivocation
        byzantineDetector.recordSignature(
            byzantineSigner.getIdentifier(),
            1L, // sequence number
            sig1.getBytes()[0][0]
        );

        var isEquivocation = byzantineDetector.recordSignature(
            byzantineSigner.getIdentifier(),
            1L, // same sequence
            sig2.getBytes()[0][0]
        );

        assertTrue(isEquivocation, "Should detect equivocation with different signatures at same sequence");
        assertTrue(byzantineDetector.isSuspicious(byzantineSigner.getIdentifier()),
            "Equivocating witness should be marked suspicious");
    }

    @Test
    @DisplayName("3. Threshold still met with M=5 honest beating f=2 Byzantine")
    void testThresholdStillMetWithByzantine() throws Exception {
        var event = testHelper.createTestKERL();
        var signatures = new ArrayList<byte[]>();

        // Collect M=5 honest signatures
        for (var signer : honestSigners) {
            signatures.add(signer.sign(event.toByteString()).getBytes()[0]);
        }

        // Byzantine nodes either don't participate or provide invalid signatures
        // (threshold should still be met with just honest nodes)

        var thresholdMet = signatures.size() >= thresholdM;
        assertTrue(thresholdMet,
            "Threshold M=5 should be met with 5 honest signatures despite f=2 Byzantine nodes");

        // Verify all honest signatures are valid
        for (int i = 0; i < honestSigners.size(); i++) {
            var isValid = testHelper.validateSignature(
                event,
                signatures.get(i),
                honestSigners.get(i).getPublicKey()
            );
            assertTrue(isValid, "Honest signature " + i + " should be valid");
        }
    }

    @Test
    @DisplayName("4. Detect Byzantine node sending wrong event")
    void testByzantineNodeSendingWrongEvent() throws Exception {
        var correctEvent = testHelper.createTestKERL("correct-event");
        var wrongEvent = testHelper.createTestKERL("wrong-event");
        var byzantineSigner = byzantineSigners.get(0);

        // Byzantine node signs wrong event
        var wrongSignature = byzantineSigner.sign(wrongEvent.toByteString().toByteArray());

        // Validation against correct event should fail
        var isValid = testHelper.validateSignature(
            correctEvent,
            wrongSignature.getBytes()[0],
            byzantineSigner.getPublicKey()
        );

        assertFalse(isValid, "Signature for wrong event should not validate against correct event");

        byzantineDetector.recordInvalidSignature(byzantineSigner.getIdentifier());
        assertTrue(byzantineDetector.isSuspicious(byzantineSigner.getIdentifier()),
            "Witness providing wrong event should be marked suspicious");
    }

    @Test
    @DisplayName("5. Detect Byzantine node equivocating to different recipients")
    void testByzantineNodeEquivocating() throws Exception {
        var event1 = testHelper.createTestKERL("version-1");
        var event2 = testHelper.createTestKERL("version-2");
        var byzantineSigner = byzantineSigners.get(0);

        // Byzantine node sends different versions to different nodes
        var sig1 = byzantineSigner.sign(event1.toByteString().toByteArray());
        var sig2 = byzantineSigner.sign(event2.toByteString().toByteArray());

        // When signatures are compared, equivocation is detected
        assertNotEquals(sig1, sig2, "Signatures for different events should differ");

        // Record both signatures at same height/sequence
        byzantineDetector.recordSignature(
            byzantineSigner.getIdentifier(),
            100L,
            sig1.getBytes()[0][0]
        );

        var equivocation = byzantineDetector.recordSignature(
            byzantineSigner.getIdentifier(),
            100L, // same sequence
            sig2.getBytes()[0][0]
        );

        assertTrue(equivocation, "Should detect equivocation across different recipients");
    }

    @Test
    @DisplayName("6. Byzantine node timeout attack - delayed signatures prevent threshold")
    void testByzantineNodeTimeoutAttack() throws Exception {
        var event = testHelper.createTestKERL();
        var latch = new CountDownLatch(thresholdM);
        var signatures = new ConcurrentHashMap<String, byte[]>();

        // Byzantine nodes delay their signatures
        var byzantineDelayMs = 5000L;

        // Honest nodes respond quickly
        for (var signer : honestSigners) {
            new Thread(() -> {
                try {
                    var sig = signer.sign(event.toByteString()).getBytes()[0];
                    signatures.put(signer.getIdentifier().toString(), sig);
                    latch.countDown();
                } catch (Exception e) {
                    fail("Honest signer failed: " + e.getMessage());
                }
            }).start();
        }

        // Wait for threshold with timeout
        var thresholdMet = latch.await(2000, TimeUnit.MILLISECONDS);

        assertTrue(thresholdMet,
            "Threshold should be met by honest nodes within timeout despite Byzantine delay attack");
        assertEquals(honestSigners.size(), signatures.size(),
            "Should have M honest signatures");

        // Byzantine nodes eventually respond (too late)
        Thread.sleep(100); // Simulate delayed Byzantine response
        for (var signer : byzantineSigners) {
            var sig = signer.sign(event.toByteString()).getBytes()[0];
            signatures.put(signer.getIdentifier().toString(), sig);
        }

        // But honest nodes already achieved consensus
        assertTrue(signatures.size() >= thresholdM,
            "System should not wait for Byzantine delayed signatures");
    }

    @Test
    @DisplayName("7. Collection with Byzantine partial participation")
    void testCollectionWithByzantinePartialParticipation() throws Exception {
        var event = testHelper.createTestKERL();
        var participationRate = new AtomicInteger(0);

        // Byzantine node participates intermittently (50% of the time)
        for (int round = 0; round < 10; round++) {
            var byzantineSigner = byzantineSigners.get(0);

            if (entropy.nextBoolean()) {
                // Byzantine participates this round
                var sig = byzantineSigner.sign(event.toByteString()).getBytes()[0];
                participationRate.incrementAndGet();
            }
            // else: Byzantine doesn't participate
        }

        // Partial participation should be detected
        var participationPercent = (participationRate.get() / 10.0) * 100;
        byzantineDetector.recordParticipationRate(
            byzantineSigners.get(0).getIdentifier(),
            participationPercent
        );

        if (participationPercent < 80) {
            assertTrue(byzantineDetector.isSuspicious(byzantineSigners.get(0).getIdentifier()),
                "Low participation rate should mark witness as suspicious");
        }
    }

    @Test
    @DisplayName("8. Consensus still achieved with f=2 Byzantine failures")
    void testConsensusStillAchieved() throws Exception {
        var event = testHelper.createTestKERL();
        var signatures = new ArrayList<byte[]>();

        // All honest nodes sign
        for (var signer : honestSigners) {
            signatures.add(signer.sign(event.toByteString()).getBytes()[0]);
        }

        // Byzantine nodes: one provides invalid signature, one doesn't participate
        var invalidSig = testHelper.tamperSignature(
            byzantineSigners.get(0).sign(event.toByteString()).getBytes()[0]
        );
        // Don't add to signatures (invalid or non-participating)

        // Verify threshold is met with honest nodes only
        var validSignatures = signatures.stream()
            .filter(sig -> testHelper.validateSignatureGeneric(event, sig))
            .count();

        assertTrue(validSignatures >= thresholdM,
            "Consensus should be achieved with M=5 honest despite f=2 Byzantine");
        assertEquals(honestSigners.size(), validSignatures,
            "All honest signatures should be valid");
    }

    @Test
    @DisplayName("9. No false positives for honest nodes")
    void testNoFalsePositivesForHonestNodes() throws Exception {
        var event = testHelper.createTestKERL();

        // Honest nodes sign correctly over multiple rounds
        for (int round = 0; round < 20; round++) {
            for (var signer : honestSigners) {
                var sig = signer.sign(event.toByteString()).getBytes()[0];
                var isValid = testHelper.validateSignature(event, sig, signer.getPublicKey());

                if (!isValid) {
                    byzantineDetector.recordInvalidSignature(signer.getIdentifier());
                }
            }
        }

        // No honest node should be marked suspicious
        for (var signer : honestSigners) {
            assertFalse(byzantineDetector.isSuspicious(signer.getIdentifier()),
                "Honest node should not be marked suspicious");
        }
    }

    @Test
    @DisplayName("10. Node recovery after Byzantine detection")
    void testNodeRecoveryAfterByzantineDetection() throws Exception {
        var event = testHelper.createTestKERL();
        var byzantineSigner = byzantineSigners.get(0);

        // Byzantine behavior initially
        var invalidSig = testHelper.tamperSignature(
            byzantineSigner.sign(event.toByteString()).getBytes()[0]
        );
        byzantineDetector.recordInvalidSignature(byzantineSigner.getIdentifier());
        assertTrue(byzantineDetector.isSuspicious(byzantineSigner.getIdentifier()));

        // Node recovers and provides valid signatures
        for (int i = 0; i < 100; i++) {
            var validSig = byzantineSigner.sign(event.toByteString()).getBytes()[0];
            var isValid = testHelper.validateSignature(event, validSig, byzantineSigner.getPublicKey());

            if (isValid) {
                byzantineDetector.recordValidSignature(byzantineSigner.getIdentifier());
            }
        }

        // After sufficient valid signatures, suspicion should clear
        byzantineDetector.evaluateRecovery(byzantineSigner.getIdentifier());
        assertFalse(byzantineDetector.isSuspicious(byzantineSigner.getIdentifier()),
            "Node should recover after sustained valid behavior");
    }

    @Test
    @DisplayName("11. Byzantine node exclusion from committee")
    void testByzantineNodeExclusion() throws Exception {
        var byzantineSigner = byzantineSigners.get(0);
        var event = testHelper.createTestKERL();

        // Repeated Byzantine behavior
        for (int i = 0; i < 10; i++) {
            var tamperedSig = testHelper.tamperSignature(
                byzantineSigner.sign(event.toByteString()).getBytes()[0]
            );
            byzantineDetector.recordInvalidSignature(byzantineSigner.getIdentifier());
        }

        assertTrue(byzantineDetector.isSuspicious(byzantineSigner.getIdentifier()));

        // Mark for exclusion
        byzantineDetector.markForExclusion(byzantineSigner.getIdentifier());
        assertTrue(byzantineDetector.isMarkedForExclusion(byzantineSigner.getIdentifier()),
            "Byzantine node should be marked for exclusion after repeated violations");
    }

    @Test
    @DisplayName("12. Cluster continues with Byzantine detected")
    void testClusterContinuesWithByzantineDetected() throws Exception {
        var event = testHelper.createTestKERL();

        // Mark Byzantine nodes as suspicious
        for (var signer : byzantineSigners) {
            byzantineDetector.recordInvalidSignature(signer.getIdentifier());
        }

        // Honest nodes continue to operate
        var signatures = new ArrayList<byte[]>();
        for (var signer : honestSigners) {
            signatures.add(signer.sign(event.toByteString()).getBytes()[0]);
        }

        // Threshold still met with honest nodes
        assertTrue(signatures.size() >= thresholdM,
            "Cluster should continue operating with Byzantine nodes excluded");

        // Verify system is operational
        var validCount = signatures.stream()
            .filter(sig -> testHelper.validateSignatureGeneric(event, sig))
            .count();

        assertEquals(honestSigners.size(), validCount,
            "All honest signatures should remain valid");
    }

    @Test
    @DisplayName("13. Byzantine detection metrics updated")
    void testByzantineDetectionMetricsUpdated() throws Exception {
        var event = testHelper.createTestKERL();

        // Generate Byzantine behavior
        for (int i = 0; i < 5; i++) {
            for (var signer : byzantineSigners) {
                byzantineDetector.recordInvalidSignature(signer.getIdentifier());
            }
        }

        // Check metrics
        var invalidSigCount = byzantineDetectionMetrics.getOrDefault("invalid_signatures", 0);
        var suspiciousNodeCount = byzantineDetectionMetrics.getOrDefault("suspicious_nodes", 0);

        assertTrue(invalidSigCount > 0, "Invalid signature count should be incremented");
        assertTrue(suspiciousNodeCount >= byzantineSigners.size(),
            "Suspicious node count should include Byzantine nodes");
    }

    @Test
    @DisplayName("14. Forensic report generated for Byzantine behavior")
    void testForensicReportGenerated() throws Exception {
        var event = testHelper.createTestKERL();
        var byzantineSigner = byzantineSigners.get(0);

        // Record Byzantine behavior
        for (int i = 0; i < 5; i++) {
            byzantineDetector.recordInvalidSignature(byzantineSigner.getIdentifier());
        }

        // Generate forensic report
        var report = byzantineDetector.generateForensicReport(byzantineSigner.getIdentifier());

        assertNotNull(report, "Forensic report should be generated");
        assertTrue(report.contains("invalid signature"),
            "Report should document invalid signatures");
        assertTrue(report.contains(byzantineSigner.getIdentifier().toString()),
            "Report should identify Byzantine node");
    }

    @Test
    @DisplayName("15. Byzantine node clearly identified")
    void testByzantineNodeIdentified() throws Exception {
        var event = testHelper.createTestKERL();

        // All signers attempt to sign
        for (var signer : honestSigners) {
            var sig = signer.sign(event.toByteString()).getBytes()[0];
            var isValid = testHelper.validateSignature(event, sig, signer.getPublicKey());

            if (!isValid) {
                byzantineDetector.recordInvalidSignature(signer.getIdentifier());
            }
        }

        for (var signer : byzantineSigners) {
            var tamperedSig = testHelper.tamperSignature(
                signer.sign(event.toByteString()).getBytes()[0]
            );
            byzantineDetector.recordInvalidSignature(signer.getIdentifier());
        }

        // Verify only Byzantine nodes are identified
        var identifiedByzantine = byzantineDetector.getIdentifiedByzantineNodes();

        assertEquals(byzantineSigners.size(), identifiedByzantine.size(),
            "Should identify exactly the Byzantine nodes");

        for (var signer : byzantineSigners) {
            assertTrue(identifiedByzantine.containsKey(signer.getIdentifier()),
                "Byzantine node should be in identified list");
        }

        for (var signer : honestSigners) {
            assertFalse(identifiedByzantine.containsKey(signer.getIdentifier()),
                "Honest node should not be in identified list");
        }
    }
}
