/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.validation;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.certification.ReceiptSignatureAggregator;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test ByzantineWitnessDetector for Byzantine behavior detection.
 */
class ByzantineWitnessDetectorTest {

    private ByzantineWitnessDetector detector;
    private MeterRegistry metricRegistry;

    @BeforeEach
    void setup() {
        metricRegistry = new SimpleMeterRegistry();
        detector = new ByzantineWitnessDetector(metricRegistry);
    }

    /**
     * C.4 Test 1: Detect equivocation (same witness, conflicting signatures).
     * <p>
     * Scenario: Witness signs two different receipts for same event
     * - Event coordinates identical
     * - Witness identifier identical
     * - Signatures different (conflicting)
     * - Result: EquivocationDetected
     * </p>
     */
    @Test
    void testDetectEquivocation() {
        var event = createTestEvent();
        var witnessId = createWitnessId(0);

        var signature1 = createSignature(1);
        var signature2 = createSignature(2); // Different signature

        // Detect equivocation
        var result = detector.detectEquivocation(event, witnessId, signature1, signature2);

        // Verify detection
        assertThat(result).isInstanceOf(ByzantineWitnessDetector.ByzantineDetectionResult.EquivocationDetected.class);
        var detected = (ByzantineWitnessDetector.ByzantineDetectionResult.EquivocationDetected) result;

        var evidence = detected.evidence();
        assertThat(evidence.event()).isEqualTo(event);
        assertThat(evidence.witnessId()).isEqualTo(witnessId);
        assertThat(evidence.signature1()).isEqualTo(signature1);
        assertThat(evidence.signature2()).isEqualTo(signature2);
        assertThat(evidence.timestamp()).isNotNull();

        // Verify metric incremented
        var stats = detector.getStats();
        assertThat(stats.equivocationCount()).isEqualTo(1);
        assertThat(stats.totalDetections()).isEqualTo(1);
    }

    /**
     * C.4 Test 2: Detect signature forgery (invalid signature in aggregate).
     * <p>
     * Scenario: Witness signature fails verification
     * - Signature present in receipt
     * - Verification failed (B.3)
     * - Result: SignatureForgeryDetected
     * </p>
     */
    @Test
    void testDetectSignatureForgery() {
        var event = createTestEvent();
        var witnessId = createWitnessId(0);

        var signature = createJohnHancock(1);
        var verificationError = "Signature verification failed: public key mismatch";

        // Detect forgery
        var result = detector.detectSignatureForgery(event, witnessId, signature, verificationError);

        // Verify detection
        assertThat(result).isInstanceOf(ByzantineWitnessDetector.ByzantineDetectionResult.SignatureForgeryDetected.class);
        var detected = (ByzantineWitnessDetector.ByzantineDetectionResult.SignatureForgeryDetected) result;

        var evidence = detected.evidence();
        assertThat(evidence.event()).isEqualTo(event);
        assertThat(evidence.witnessId()).isEqualTo(witnessId);
        assertThat(evidence.signature()).isEqualTo(signature);
        assertThat(evidence.verificationError()).isEqualTo(verificationError);
        assertThat(evidence.timestamp()).isNotNull();

        // Verify metric incremented
        var stats = detector.getStats();
        assertThat(stats.signatureForgeryCount()).isEqualTo(1);
        assertThat(stats.totalDetections()).isEqualTo(1);
    }

    /**
     * C.4 Test 3: Detect threshold bypass (witness in bitmap without signature).
     * <p>
     * Scenario: Witness bit set in bitmap but no signature
     * - Bitmap has bit 5 set
     * - No signature from witness 5
     * - Attempt to inflate signature count
     * - Result: ThresholdBypassDetected
     * </p>
     */
    @Test
    void testDetectThresholdBypass() {
        var event = createTestEvent();
        var threshold = 5;

        // Create aggregated receipt with bitmap anomaly
        // Witnesses 0-3 signed (4 signatures)
        var signatures = List.of(
            new ReceiptSignatureAggregator.WitnessSignature(createWitnessId(0), 0, createSignature(0)),
            new ReceiptSignatureAggregator.WitnessSignature(createWitnessId(1), 1, createSignature(1)),
            new ReceiptSignatureAggregator.WitnessSignature(createWitnessId(2), 2, createSignature(2)),
            new ReceiptSignatureAggregator.WitnessSignature(createWitnessId(3), 3, createSignature(3))
        );

        // Bitmap has bit 5 set (Byzantine witness trying to bypass)
        var bitmap = new BitSet();
        bitmap.set(0);
        bitmap.set(1);
        bitmap.set(2);
        bitmap.set(3);
        bitmap.set(5); // Byzantine: bit set but no signature

        var receipt = new ReceiptSignatureAggregator.AggregatedReceipt(
            event,
            bitmap,
            signatures,
            threshold,
            ReceiptSignatureAggregator.AggregationStatus.PARTIAL
        );

        // Detect threshold bypass for witness 5
        var witnessId5 = createWitnessId(5);
        var result = detector.detectThresholdBypass(witnessId5, 5, receipt);

        // Verify detection
        assertThat(result).isInstanceOf(ByzantineWitnessDetector.ByzantineDetectionResult.ThresholdBypassDetected.class);
        var detected = (ByzantineWitnessDetector.ByzantineDetectionResult.ThresholdBypassDetected) result;

        var evidence = detected.evidence();
        assertThat(evidence.event()).isEqualTo(event);
        assertThat(evidence.witnessId()).isEqualTo(witnessId5);
        assertThat(evidence.committeeIndex()).isEqualTo(5);
        assertThat(evidence.signatureCount()).isEqualTo(4);
        assertThat(evidence.threshold()).isEqualTo(5);
        assertThat(evidence.timestamp()).isNotNull();

        // Verify metric incremented
        var stats = detector.getStats();
        assertThat(stats.thresholdBypassCount()).isEqualTo(1);
        assertThat(stats.totalDetections()).isEqualTo(1);
    }

    /**
     * C.4 Test 4: Generate formatted Byzantine forensic report.
     * <p>
     * Validates report generation for each evidence type:
     * - Equivocation
     * - Signature Forgery
     * - Threshold Bypass
     * </p>
     */
    @Test
    void testGenerateByzantineReport() {
        var event = createTestEvent();
        var witnessId = createWitnessId(0);

        // Equivocation report
        var equivocationEvidence = new ByzantineWitnessDetector.EquivocationEvidence(
            event,
            witnessId,
            createSignature(1),
            createSignature(2),
            java.time.Instant.now()
        );

        var equivocationReport = ByzantineWitnessDetector.generateByzantineReport(equivocationEvidence);
        assertThat(equivocationReport).contains("Byzantine Equivocation Report");
        assertThat(equivocationReport).contains("EQUIVOCATION");
        assertThat(equivocationReport).contains(witnessId.toString());

        // Signature forgery report
        var forgeryEvidence = new ByzantineWitnessDetector.SignatureForgeryEvidence(
            event,
            witnessId,
            createJohnHancock(1),
            "Verification failed",
            java.time.Instant.now()
        );

        var forgeryReport = ByzantineWitnessDetector.generateByzantineReport(forgeryEvidence);
        assertThat(forgeryReport).contains("Byzantine Signature Forgery Report");
        assertThat(forgeryReport).contains("SIGNATURE_FORGERY");
        assertThat(forgeryReport).contains("Verification failed");

        // Threshold bypass report
        var bypassEvidence = new ByzantineWitnessDetector.ThresholdBypassEvidence(
            event,
            witnessId,
            5,
            4,
            5,
            java.time.Instant.now()
        );

        var bypassReport = ByzantineWitnessDetector.generateByzantineReport(bypassEvidence);
        assertThat(bypassReport).contains("Byzantine Threshold Bypass Report");
        assertThat(bypassReport).contains("THRESHOLD_BYPASS");
        assertThat(bypassReport).contains("Committee Index: 5");
    }

    /**
     * C.4 Test 5: Metrics tracking integration.
     * <p>
     * Validates metric counters increment correctly:
     * - Equivocation counter
     * - Signature forgery counter
     * - Threshold bypass counter
     * - Total detections
     * </p>
     */
    @Test
    void testIntegrationWithMetricsTracking() {
        var event = createTestEvent();

        // Detect equivocation
        detector.detectEquivocation(event, createWitnessId(0), createSignature(1), createSignature(2));

        // Detect forgery
        detector.detectSignatureForgery(event, createWitnessId(1), createJohnHancock(1), "error");

        // Detect bypass
        var receipt = createTestReceiptWithBypass(event, 5);
        detector.detectThresholdBypass(createWitnessId(5), 5, receipt);

        // Verify metrics
        var stats = detector.getStats();
        assertThat(stats.equivocationCount()).isEqualTo(1);
        assertThat(stats.signatureForgeryCount()).isEqualTo(1);
        assertThat(stats.thresholdBypassCount()).isEqualTo(1);
        assertThat(stats.totalDetections()).isEqualTo(3);

        // Verify MeterRegistry counters
        var equivCounter = metricRegistry.find("witness.byzantine.equivocation").counter();
        var forgeryCounter = metricRegistry.find("witness.byzantine.signature_forgery").counter();
        var bypassCounter = metricRegistry.find("witness.byzantine.threshold_bypass").counter();
        assertThat(equivCounter).isNotNull();
        assertThat(equivCounter.count()).isEqualTo(1);
        assertThat(forgeryCounter).isNotNull();
        assertThat(forgeryCounter.count()).isEqualTo(1);
        assertThat(bypassCounter).isNotNull();
        assertThat(bypassCounter.count()).isEqualTo(1);
    }

    // Test utilities

    private EventCoordinates createTestEvent() {
        var identifier = createWitnessId(0);
        var digest = DigestAlgorithm.BLAKE3_256.digest("test-event".getBytes());
        return new EventCoordinates(identifier, ULong.valueOf(0), digest, "icp");
    }

    private Identifier createWitnessId(int index) {
        var digest = DigestAlgorithm.BLAKE3_256.digest(("witness-" + index).getBytes());
        return new SelfAddressingIdentifier(digest);
    }

    private Digest createSignature(int index) {
        return DigestAlgorithm.BLAKE3_256.digest(("signature-" + index).getBytes());
    }

    private JohnHancock createJohnHancock(int index) {
        var signature = createSignature(index);
        return new JohnHancock(SignatureAlgorithm.ED_25519, signature.getBytes(), ULong.valueOf(0));
    }

    private ReceiptSignatureAggregator.AggregatedReceipt createTestReceiptWithBypass(EventCoordinates event, int bypassIndex) {
        var signatures = List.of(
            new ReceiptSignatureAggregator.WitnessSignature(createWitnessId(0), 0, createSignature(0)),
            new ReceiptSignatureAggregator.WitnessSignature(createWitnessId(1), 1, createSignature(1)),
            new ReceiptSignatureAggregator.WitnessSignature(createWitnessId(2), 2, createSignature(2)),
            new ReceiptSignatureAggregator.WitnessSignature(createWitnessId(3), 3, createSignature(3))
        );

        var bitmap = new BitSet();
        bitmap.set(0);
        bitmap.set(1);
        bitmap.set(2);
        bitmap.set(3);
        bitmap.set(bypassIndex); // Byzantine bit

        return new ReceiptSignatureAggregator.AggregatedReceipt(
            event,
            bitmap,
            signatures,
            5,
            ReceiptSignatureAggregator.AggregationStatus.PARTIAL
        );
    }
}
