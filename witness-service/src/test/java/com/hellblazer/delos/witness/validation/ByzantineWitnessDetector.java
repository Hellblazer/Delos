/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.delos.witness.validation;

import com.codahale.metrics.MetricRegistry;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.witness.certification.ReceiptSignatureAggregator;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test helper for detecting Byzantine behavior in witness networks.
 * <p>
 * Tracks signatures, equivocation, invalid signatures, and participation
 * rates to identify suspicious witness nodes during testing.
 */
public class ByzantineWitnessDetector {

    private final Map<String, Integer> metrics;
    private final MetricRegistry metricRegistry;
    private final ConcurrentHashMap<Identifier, SignatureTracker> trackers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Identifier, SuspiciousWitness> suspicious = new ConcurrentHashMap<>();
    private final AtomicInteger equivocationCount = new AtomicInteger(0);
    private final AtomicInteger signatureForgeryCount = new AtomicInteger(0);
    private final AtomicInteger thresholdBypassCount = new AtomicInteger(0);

    private static final int INVALID_SIGNATURE_THRESHOLD = 3;
    private static final int RECOVERY_VALID_SIGNATURES_NEEDED = 10;

    public ByzantineWitnessDetector(Map<String, Integer> metricMap) {
        this.metrics = metricMap;
        this.metricRegistry = null;
    }

    public ByzantineWitnessDetector(MetricRegistry registry) {
        this.metrics = new ConcurrentHashMap<>();
        this.metricRegistry = registry;
    }

    private void incrementMetric(String name) {
        if (metricRegistry != null) {
            metricRegistry.counter(name).inc();
        } else if (metrics != null) {
            metrics.merge(name, 1, Integer::sum);
        }
    }

    /**
     * Detect equivocation - two different signatures for same event
     */
    public ByzantineDetectionResult detectEquivocation(EventCoordinates event, Identifier witnessId,
                                                       Digest signature1, Digest signature2) {
        equivocationCount.incrementAndGet();
        suspicious.put(witnessId, new SuspiciousWitness(witnessId, "Equivocation at " + event));

        var evidence = new EquivocationEvidence(event, witnessId, signature1, signature2, Instant.now());
        return new ByzantineDetectionResult.EquivocationDetected(evidence);
    }

    /**
     * Detect signature forgery - signature failed verification
     */
    public ByzantineDetectionResult detectSignatureForgery(EventCoordinates event, Identifier witnessId,
                                                           JohnHancock signature, String verificationError) {
        signatureForgeryCount.incrementAndGet();
        suspicious.put(witnessId, new SuspiciousWitness(witnessId, "Signature forgery: " + verificationError));

        var evidence = new SignatureForgeryEvidence(event, witnessId, signature, verificationError, Instant.now());
        return new ByzantineDetectionResult.SignatureForgeryDetected(evidence);
    }

    /**
     * Detect threshold bypass - witness in bitmap without valid signature
     */
    public ByzantineDetectionResult detectThresholdBypass(Identifier witnessId, int committeeIndex,
                                                          ReceiptSignatureAggregator.AggregatedReceipt receipt) {
        thresholdBypassCount.incrementAndGet();
        suspicious.put(witnessId, new SuspiciousWitness(witnessId, "Threshold bypass at committee " + committeeIndex));

        var event = receipt.event();
        var evidence = new ThresholdBypassEvidence(event, witnessId, committeeIndex,
                                                   receipt.signatures().size(), receipt.threshold(), Instant.now());
        return new ByzantineDetectionResult.ThresholdBypassDetected(evidence);
    }

    /**
     * Mark a witness for exclusion from future consensus
     */
    public void markForExclusion(Identifier witnessId) {
        suspicious.put(witnessId, new SuspiciousWitness(witnessId, "Marked for exclusion"));
        incrementMetric("byzantine.exclusions");
    }

    /**
     * Check if a witness is marked for exclusion
     */
    public boolean isMarkedForExclusion(Identifier witnessId) {
        return suspicious.containsKey(witnessId);
    }

    /**
     * Generate forensic report for a suspicious witness
     */
    public String generateForensicReport(Identifier witnessId) {
        var witness = suspicious.get(witnessId);
        if (witness == null) {
            return "No Byzantine behavior recorded for witness: " + witnessId;
        }

        var tracker = trackers.get(witnessId);
        return String.format("""
            Byzantine Forensic Report
            Witness: %s
            Reason: %s
            Invalid Signatures: %d
            Valid Signatures: %d
            Participation Rate: %.2f
            """,
            witnessId,
            witness.reason(),
            tracker != null ? tracker.invalidCount : 0,
            tracker != null ? tracker.validCount : 0,
            tracker != null ? tracker.participationRate : 0.0
        );
    }

    /**
     * Get all identified Byzantine nodes
     */
    public Map<Identifier, SuspiciousWitness> getIdentifiedByzantineNodes() {
        return getSuspiciousWitnesses();
    }

    /**
     * Get Byzantine detection statistics
     */
    public ByzantineStats getStats() {
        return new ByzantineStats(
            equivocationCount.get(),
            signatureForgeryCount.get(),
            thresholdBypassCount.get(),
            equivocationCount.get() + signatureForgeryCount.get() + thresholdBypassCount.get()
        );
    }

    /**
     * Record an invalid signature from a witness
     */
    public void recordInvalidSignature(Identifier witnessId) {
        var tracker = trackers.computeIfAbsent(witnessId, k -> new SignatureTracker());
        tracker.invalidCount++;

        if (tracker.invalidCount >= INVALID_SIGNATURE_THRESHOLD) {
            suspicious.put(witnessId, new SuspiciousWitness(witnessId, "Invalid signature threshold exceeded"));
            incrementMetric("byzantine.invalid_signatures");
        }
    }

    /**
     * Record a valid signature from a witness
     */
    public void recordValidSignature(Identifier witnessId) {
        var tracker = trackers.computeIfAbsent(witnessId, k -> new SignatureTracker());
        tracker.validCount++;
    }

    /**
     * Record a signature at a specific sequence number.
     * Returns true if equivocation detected (different signature at same sequence).
     */
    public boolean recordSignature(Identifier witnessId, long sequenceNumber, byte signatureFirstByte) {
        var tracker = trackers.computeIfAbsent(witnessId, k -> new SignatureTracker());
        var existing = tracker.sequenceSignatures.put(sequenceNumber, signatureFirstByte);

        if (existing != null && existing != signatureFirstByte) {
            // Equivocation detected
            suspicious.put(witnessId, new SuspiciousWitness(witnessId, "Equivocation at sequence " + sequenceNumber));
            incrementMetric("byzantine.equivocations");
            return true;
        }

        return false;
    }

    /**
     * Record participation rate for a witness
     */
    public void recordParticipationRate(Identifier witnessId, double rate) {
        var tracker = trackers.computeIfAbsent(witnessId, k -> new SignatureTracker());
        tracker.participationRate = rate;

        // Low participation could indicate Byzantine behavior
        if (rate < 0.3) {
            suspicious.put(witnessId, new SuspiciousWitness(witnessId, "Low participation rate: " + rate));
            incrementMetric("byzantine.low_participation");
        }
    }

    /**
     * Check if a witness is marked as suspicious
     */
    public boolean isSuspicious(Identifier witnessId) {
        return suspicious.containsKey(witnessId);
    }

    /**
     * Evaluate if a witness can recover from suspicious status
     */
    public void evaluateRecovery(Identifier witnessId) {
        var tracker = trackers.get(witnessId);
        if (tracker != null && tracker.validCount >= RECOVERY_VALID_SIGNATURES_NEEDED) {
            suspicious.remove(witnessId);
            incrementMetric("byzantine.recoveries");
        }
    }

    /**
     * Get all currently suspicious witnesses
     */
    public Map<Identifier, SuspiciousWitness> getSuspiciousWitnesses() {
        return Map.copyOf(suspicious);
    }

    private static class SignatureTracker {
        int validCount = 0;
        int invalidCount = 0;
        double participationRate = 1.0;
        final ConcurrentHashMap<Long, Byte> sequenceSignatures = new ConcurrentHashMap<>();
    }

    public record SuspiciousWitness(Identifier witnessId, String reason) {}

    public record ByzantineStats(
        int equivocationCount,
        int signatureForgeryCount,
        int thresholdBypassCount,
        int totalDetections
    ) {}

    // Evidence and detection result types for validation tests
    public sealed interface ByzantineDetectionResult {
        record EquivocationDetected(EquivocationEvidence evidence) implements ByzantineDetectionResult {}
        record SignatureForgeryDetected(SignatureForgeryEvidence evidence) implements ByzantineDetectionResult {}
        record ThresholdBypassDetected(ThresholdBypassEvidence evidence) implements ByzantineDetectionResult {}
    }

    public record EquivocationEvidence(
        EventCoordinates event,
        Identifier witnessId,
        Digest signature1,
        Digest signature2,
        Instant timestamp
    ) {}

    public record SignatureForgeryEvidence(
        EventCoordinates event,
        Identifier witnessId,
        JohnHancock signature,
        String verificationError,
        Instant timestamp
    ) {}

    public record ThresholdBypassEvidence(
        EventCoordinates event,
        Identifier witnessId,
        int committeeIndex,
        int signatureCount,
        int threshold,
        Instant timestamp
    ) {}

    public static String generateByzantineReport(Object evidence) {
        return switch (evidence) {
            case EquivocationEvidence e ->
                """
                Byzantine Equivocation Report
                Type: EQUIVOCATION
                Event: %s
                Witness: %s
                Signature 1: %s
                Signature 2: %s
                Timestamp: %s
                """.formatted(e.event, e.witnessId, e.signature1, e.signature2, e.timestamp);

            case SignatureForgeryEvidence e ->
                """
                Byzantine Signature Forgery Report
                Type: SIGNATURE_FORGERY
                Event: %s
                Witness: %s
                Signature: %s
                Error: %s
                Timestamp: %s
                """.formatted(e.event, e.witnessId, e.signature, e.verificationError, e.timestamp);

            case ThresholdBypassEvidence e ->
                """
                Byzantine Threshold Bypass Report
                Type: THRESHOLD_BYPASS
                Event: %s
                Witness: %s
                Committee Index: %d
                Actual Signatures: %d
                Expected Threshold: %d
                Timestamp: %s
                """.formatted(e.event, e.witnessId, e.committeeIndex,
                            e.signatureCount, e.threshold, e.timestamp);

            default -> "Unknown Byzantine evidence type";
        };
    }
}
