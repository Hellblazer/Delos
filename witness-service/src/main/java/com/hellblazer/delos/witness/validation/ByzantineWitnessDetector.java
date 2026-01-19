/*
 * Copyright (c) 2024, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.witness.validation;

import com.codahale.metrics.Counter;
import com.codahale.metrics.MetricRegistry;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.witness.certification.ReceiptSignatureAggregator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.BitSet;
import java.util.Objects;

/**
 * Detects Byzantine behavior in witness network.
 * <p>
 * Detects and reports:
 * - Equivocation: Witness signs multiple conflicting receipts for same event
 * - Signature Forgery: Signature fails verification but present in aggregate
 * - Threshold Bypass: Witness in bitmap without valid signature contribution
 * </p>
 * <p>
 * Actions on detection:
 * - Log Byzantine event with full forensic details
 * - Increment metrics for monitoring
 * - Generate forensic report for governance
 * - Mark witness for Fireflies shunning (Phase 1B)
 * </p>
 * <p>
 * Forensic tracking:
 * - Store evidence in WitnessCHOAM for recovery
 * - Include: event, witness, timestamp, evidence type, verification failure
 * - Enable audit trail for governance and incident response
 * </p>
 */
public class ByzantineWitnessDetector {

    private static final Logger log = LoggerFactory.getLogger(ByzantineWitnessDetector.class);

    private final Counter equivocationDetected;
    private final Counter signatureForgeryDetected;
    private final Counter thresholdBypassDetected;

    /**
     * Create Byzantine detector with metrics tracking.
     *
     * @param metricRegistry Metrics registry for tracking
     */
    public ByzantineWitnessDetector(MetricRegistry metricRegistry) {
        Objects.requireNonNull(metricRegistry, "metricRegistry cannot be null");

        this.equivocationDetected = metricRegistry.counter("witness.byzantine.equivocation");
        this.signatureForgeryDetected = metricRegistry.counter("witness.byzantine.signature_forgery");
        this.thresholdBypassDetected = metricRegistry.counter("witness.byzantine.threshold_bypass");
    }

    /**
     * Detect equivocation: witness signs multiple conflicting receipts for same event.
     * <p>
     * Equivocation detection:
     * - Same event coordinates
     * - Same witness identifier
     * - Different signatures (conflicting receipts)
     * </p>
     *
     * @param event      Event coordinates
     * @param witnessId  Witness identifier
     * @param signature1 First signature
     * @param signature2 Second signature (conflicting)
     * @return Detection result
     */
    public ByzantineDetectionResult detectEquivocation(
        EventCoordinates event,
        Identifier witnessId,
        Digest signature1,
        Digest signature2) {

        Objects.requireNonNull(event, "event cannot be null");
        Objects.requireNonNull(witnessId, "witnessId cannot be null");
        Objects.requireNonNull(signature1, "signature1 cannot be null");
        Objects.requireNonNull(signature2, "signature2 cannot be null");

        // Check for conflicting signatures
        if (signature1.equals(signature2)) {
            // Same signature, not equivocation
            return new ByzantineDetectionResult.NoByzantineBehavior();
        }

        // Equivocation detected
        equivocationDetected.inc();

        var evidence = new EquivocationEvidence(
            event,
            witnessId,
            signature1,
            signature2,
            Instant.now()
        );

        log.warn("Byzantine equivocation detected: witness={} event={} sig1={} sig2={}",
                 witnessId, event, signature1, signature2);

        return new ByzantineDetectionResult.EquivocationDetected(evidence);
    }

    /**
     * Detect signature forgery: signature fails verification but present in aggregate.
     * <p>
     * Forgery detection:
     * - Signature in aggregated receipt
     * - Signature verification failed (from B.3)
     * - Witness claims signature but verification fails
     * </p>
     *
     * @param event         Event coordinates
     * @param witnessId     Witness identifier
     * @param signature     Claimed signature
     * @param verificationError Verification failure reason
     * @return Detection result
     */
    public ByzantineDetectionResult detectSignatureForgery(
        EventCoordinates event,
        Identifier witnessId,
        JohnHancock signature,
        String verificationError) {

        Objects.requireNonNull(event, "event cannot be null");
        Objects.requireNonNull(witnessId, "witnessId cannot be null");
        Objects.requireNonNull(signature, "signature cannot be null");
        Objects.requireNonNull(verificationError, "verificationError cannot be null");

        // Signature forgery detected
        signatureForgeryDetected.inc();

        var evidence = new SignatureForgeryEvidence(
            event,
            witnessId,
            signature,
            verificationError,
            Instant.now()
        );

        log.warn("Byzantine signature forgery detected: witness={} event={} error={}",
                 witnessId, event, verificationError);

        return new ByzantineDetectionResult.SignatureForgeryDetected(evidence);
    }

    /**
     * Detect threshold bypass: witness in bitmap without valid signature contribution.
     * <p>
     * Threshold bypass detection:
     * - Witness bit set in bitmap
     * - No corresponding signature in aggregate
     * - Attempt to inflate signature count without contributing
     * </p>
     *
     * @param witnessId       Witness identifier
     * @param committeeIndex  Committee index (bitmap position)
     * @param receipt         Aggregated receipt
     * @return Detection result
     */
    public ByzantineDetectionResult detectThresholdBypass(
        Identifier witnessId,
        int committeeIndex,
        ReceiptSignatureAggregator.AggregatedReceipt receipt) {

        Objects.requireNonNull(witnessId, "witnessId cannot be null");
        Objects.requireNonNull(receipt, "receipt cannot be null");

        var bitmap = receipt.signerBitmap();
        var signatures = receipt.signatures();

        // Check if bitmap bit set
        if (!bitmap.get(committeeIndex)) {
            // Bitmap bit not set, no bypass
            return new ByzantineDetectionResult.NoByzantineBehavior();
        }

        // Check if corresponding signature exists
        var hasSignature = signatures.stream()
            .anyMatch(sig -> sig.witnessId().equals(witnessId) && sig.committeeIndex() == committeeIndex);

        if (hasSignature) {
            // Valid signature contribution
            return new ByzantineDetectionResult.NoByzantineBehavior();
        }

        // Threshold bypass detected (bitmap set but no signature)
        thresholdBypassDetected.inc();

        var evidence = new ThresholdBypassEvidence(
            receipt.event(),
            witnessId,
            committeeIndex,
            receipt.signatureCount(),
            receipt.threshold(),
            Instant.now()
        );

        log.warn("Byzantine threshold bypass detected: witness={} event={} index={} count={}/{}",
                 witnessId, receipt.event(), committeeIndex, receipt.signatureCount(), receipt.threshold());

        return new ByzantineDetectionResult.ThresholdBypassDetected(evidence);
    }

    /**
     * Generate forensic report for Byzantine evidence.
     * <p>
     * Report format:
     * - Event coordinates
     * - Witness identifier
     * - Evidence type (equivocation, forgery, bypass)
     * - Timestamp
     * - Detailed evidence
     * </p>
     *
     * @param evidence Byzantine evidence
     * @return Formatted forensic report
     */
    public static String generateByzantineReport(ByzantineEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence cannot be null");

        return switch (evidence) {
            case EquivocationEvidence eq -> String.format(
                """
                Byzantine Equivocation Report
                ==============================
                Event: %s
                Witness: %s
                Timestamp: %s
                Evidence Type: EQUIVOCATION

                Details:
                  Signature 1: %s
                  Signature 2: %s

                Witness signed multiple conflicting receipts for same event.
                """,
                eq.event(),
                eq.witnessId(),
                eq.timestamp(),
                eq.signature1(),
                eq.signature2()
            );

            case SignatureForgeryEvidence fg -> String.format(
                """
                Byzantine Signature Forgery Report
                ===================================
                Event: %s
                Witness: %s
                Timestamp: %s
                Evidence Type: SIGNATURE_FORGERY

                Details:
                  Claimed Signature: %s
                  Verification Error: %s

                Witness claimed signature but verification failed.
                """,
                fg.event(),
                fg.witnessId(),
                fg.timestamp(),
                fg.signature(),
                fg.verificationError()
            );

            case ThresholdBypassEvidence bp -> String.format(
                """
                Byzantine Threshold Bypass Report
                ==================================
                Event: %s
                Witness: %s
                Timestamp: %s
                Evidence Type: THRESHOLD_BYPASS

                Details:
                  Committee Index: %d
                  Signature Count: %d
                  Threshold: %d

                Witness in bitmap without valid signature contribution.
                """,
                bp.event(),
                bp.witnessId(),
                bp.timestamp(),
                bp.committeeIndex(),
                bp.signatureCount(),
                bp.threshold()
            );
        };
    }

    /**
     * Get Byzantine detection statistics.
     *
     * @return Detection stats
     */
    public ByzantineStats getStats() {
        return new ByzantineStats(
            equivocationDetected.getCount(),
            signatureForgeryDetected.getCount(),
            thresholdBypassDetected.getCount()
        );
    }

    /**
     * Sealed interface for Byzantine evidence.
     */
    public sealed interface ByzantineEvidence {
        EventCoordinates event();
        Identifier witnessId();
        Instant timestamp();
    }

    /**
     * Equivocation evidence: witness signed conflicting receipts.
     */
    public record EquivocationEvidence(
        EventCoordinates event,
        Identifier witnessId,
        Digest signature1,
        Digest signature2,
        Instant timestamp
    ) implements ByzantineEvidence {
    }

    /**
     * Signature forgery evidence: signature verification failed.
     */
    public record SignatureForgeryEvidence(
        EventCoordinates event,
        Identifier witnessId,
        JohnHancock signature,
        String verificationError,
        Instant timestamp
    ) implements ByzantineEvidence {
    }

    /**
     * Threshold bypass evidence: bitmap set without signature.
     */
    public record ThresholdBypassEvidence(
        EventCoordinates event,
        Identifier witnessId,
        int committeeIndex,
        int signatureCount,
        int threshold,
        Instant timestamp
    ) implements ByzantineEvidence {
    }

    /**
     * Sealed interface for Byzantine detection result.
     */
    public sealed interface ByzantineDetectionResult {

        /**
         * No Byzantine behavior detected.
         */
        record NoByzantineBehavior() implements ByzantineDetectionResult {
        }

        /**
         * Equivocation detected.
         */
        record EquivocationDetected(EquivocationEvidence evidence) implements ByzantineDetectionResult {
        }

        /**
         * Signature forgery detected.
         */
        record SignatureForgeryDetected(SignatureForgeryEvidence evidence) implements ByzantineDetectionResult {
        }

        /**
         * Threshold bypass detected.
         */
        record ThresholdBypassDetected(ThresholdBypassEvidence evidence) implements ByzantineDetectionResult {
        }
    }

    /**
     * Byzantine detection statistics.
     */
    public record ByzantineStats(
        long equivocationCount,
        long signatureForgeryCount,
        long thresholdBypassCount
    ) {
        /**
         * Get total Byzantine detections.
         */
        public long totalDetections() {
            return equivocationCount + signatureForgeryCount + thresholdBypassCount;
        }
    }
}
