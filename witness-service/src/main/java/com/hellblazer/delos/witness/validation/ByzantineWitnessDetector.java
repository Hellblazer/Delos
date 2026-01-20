/*
 * Copyright (c) 2024, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
/*
 * Portions copyright (c) 2025, Hal Hildebrand.
 * Modifications made under GNU Affero General Public License.
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
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
    private static final int BLS_FAILURE_THRESHOLD = 5;

    private final Counter equivocationDetected;
    private final Counter signatureForgeryDetected;
    private final Counter thresholdBypassDetected;

    private final java.util.concurrent.ConcurrentHashMap<Identifier, WitnessStatus> witnessStatuses = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Identifier, java.util.Map<Long, byte[]>> signatureHistory = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Integer> metrics;

    // BLS failure tracking (Phase 1B-3 C-1)
    private final java.util.concurrent.ConcurrentHashMap<Identifier, java.util.concurrent.atomic.AtomicInteger> blsFailureCounts = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Identifier, Long> blsFailureTimestamps = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile FirefliesShunningIntegration shunningIntegration;

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
        this.metrics = null;
    }

    /**
     * Create Byzantine detector with simple metrics map for testing.
     *
     * @param metricsMap Metrics map
     */
    public ByzantineWitnessDetector(java.util.Map<String, Integer> metricsMap) {
        this.metrics = metricsMap;
        this.equivocationDetected = null;
        this.signatureForgeryDetected = null;
        this.thresholdBypassDetected = null;
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
     * Set Fireflies shunning integration callback.
     * <p>
     * Must be set before BLS failure tracking triggers shunning.
     * </p>
     *
     * @param integration Shunning integration implementation
     */
    public void setShunningIntegration(FirefliesShunningIntegration integration) {
        this.shunningIntegration = integration;
    }

    /**
     * Record BLS validation failure for a member.
     * <p>
     * Tracks failure count and timestamps for Byzantine detection.
     * When threshold is reached, triggers Fireflies shunning.
     * </p>
     *
     * @param memberId Member identifier
     * @param reason Failure reason (for logging)
     */
    public void recordBlsValidationFailure(Identifier memberId, String reason) {
        Objects.requireNonNull(memberId, "memberId cannot be null");
        Objects.requireNonNull(reason, "reason cannot be null");

        // Increment failure count
        var count = blsFailureCounts.computeIfAbsent(
            memberId,
            k -> new java.util.concurrent.atomic.AtomicInteger(0)
        ).incrementAndGet();

        // Update timestamp
        blsFailureTimestamps.put(memberId, System.currentTimeMillis());

        log.warn("BLS validation failure for member={}: {} (count={})", memberId, reason, count);

        // Check threshold and trigger shunning
        if (count >= BLS_FAILURE_THRESHOLD) {
            log.error("BLS failure threshold reached for member={}, triggering shunning", memberId);
            markForExclusion(memberId);

            // Trigger Fireflies shunning if integration available
            if (shunningIntegration != null) {
                shunningIntegration.markMemberForShunning(memberId)
                    .exceptionally(ex -> {
                        log.error("Failed to trigger Fireflies shunning for member={}", memberId, ex);
                        return null;
                    });
            }
        }
    }

    /**
     * Get BLS failure count for a member.
     *
     * @param memberId Member identifier
     * @return Failure count (0 if no failures recorded)
     */
    public int getBlsFailureCount(Identifier memberId) {
        Objects.requireNonNull(memberId, "memberId cannot be null");
        var counter = blsFailureCounts.get(memberId);
        return counter != null ? counter.get() : 0;
    }

    /**
     * Check if a member should be shunned based on BLS failures.
     * <p>
     * Returns true if failure count meets or exceeds threshold.
     * </p>
     *
     * @param memberId Member identifier
     * @return true if member should be shunned
     */
    public boolean shouldShun(Identifier memberId) {
        Objects.requireNonNull(memberId, "memberId cannot be null");
        return getBlsFailureCount(memberId) >= BLS_FAILURE_THRESHOLD;
    }

    /**
     * Clear stale BLS failure entries older than TTL.
     * <p>
     * Used to age out old failures and allow recovery for members
     * that have corrected their behavior.
     * </p>
     *
     * @param ttlMs Time-to-live in milliseconds
     */
    public void clearStaleFailures(long ttlMs) {
        var now = System.currentTimeMillis();
        var staleMembers = blsFailureTimestamps.entrySet().stream()
            .filter(entry -> (now - entry.getValue()) > ttlMs)
            .map(java.util.Map.Entry::getKey)
            .toList();

        for (var memberId : staleMembers) {
            blsFailureCounts.remove(memberId);
            blsFailureTimestamps.remove(memberId);
            log.debug("Cleared stale BLS failures for member={}", memberId);
        }
    }

    /**
     * Get enhanced Byzantine detection statistics including BLS failures.
     *
     * @return Enhanced detection stats
     */
    public ByzantineStats getByzantineStats() {
        var totalBlsFailures = blsFailureCounts.values().stream()
            .mapToInt(java.util.concurrent.atomic.AtomicInteger::get)
            .sum();

        return new ByzantineStats(
            equivocationDetected != null ? equivocationDetected.getCount() : 0,
            signatureForgeryDetected != null ? signatureForgeryDetected.getCount() : 0,
            thresholdBypassDetected != null ? thresholdBypassDetected.getCount() : 0,
            totalBlsFailures
        );
    }

    /**
     * Get count of members currently shunned based on BLS failures.
     * <p>
     * Returns the number of members with failure counts meeting or exceeding threshold.
     * </p>
     *
     * @return Count of shunned members
     */
    public int getShunnedMemberCount() {
        return (int) blsFailureCounts.entrySet().stream()
            .filter(entry -> entry.getValue().get() >= BLS_FAILURE_THRESHOLD)
            .count();
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
        long thresholdBypassCount,
        long blsFailureCount
    ) {
        /**
         * Constructor for backwards compatibility (no BLS failures).
         */
        public ByzantineStats(long equivocationCount, long signatureForgeryCount, long thresholdBypassCount) {
            this(equivocationCount, signatureForgeryCount, thresholdBypassCount, 0);
        }

        /**
         * Get total Byzantine detections.
         */
        public long totalDetections() {
            return equivocationCount + signatureForgeryCount + thresholdBypassCount + blsFailureCount;
        }
    }

    // Additional methods for testing support

    public void recordInvalidSignature(Identifier witnessId) {
        Objects.requireNonNull(witnessId, "witnessId cannot be null");
        var status = witnessStatuses.computeIfAbsent(witnessId, k -> new WitnessStatus());
        status.invalidSignatures++;
        // Mark as suspicious on first invalid signature
        status.suspicious = true;
        if (metrics != null) {
            metrics.merge("invalid_signatures", 1, Integer::sum);
            if (status.suspicious) {
                metrics.merge("suspicious_nodes", 1, Integer::sum);
            }
        }
        if (signatureForgeryDetected != null) {
            signatureForgeryDetected.inc();
        }
    }

    public boolean isSuspicious(Identifier witnessId) {
        Objects.requireNonNull(witnessId, "witnessId cannot be null");
        return witnessStatuses.getOrDefault(witnessId, new WitnessStatus()).suspicious;
    }

    public boolean recordSignature(Identifier witnessId, long sequence, byte[] signatureBytes) {
        Objects.requireNonNull(witnessId, "witnessId cannot be null");
        Objects.requireNonNull(signatureBytes, "signatureBytes cannot be null");

        var history = signatureHistory.computeIfAbsent(witnessId, k -> new java.util.concurrent.ConcurrentHashMap<>());
        var existing = history.get(sequence);
        if (existing != null && !java.util.Arrays.equals(existing, signatureBytes)) {
            // Equivocation detected: different signatures at same sequence
            recordInvalidSignature(witnessId);
            if (equivocationDetected != null) {
                equivocationDetected.inc();
            }
            return true;
        }
        history.put(sequence, signatureBytes);
        return false;
    }

    public void recordParticipationRate(Identifier witnessId, double participationPercent) {
        var status = witnessStatuses.computeIfAbsent(witnessId, k -> new WitnessStatus());
        status.participationRate = participationPercent;
        if (participationPercent < 80) {
            status.suspicious = true;
        }
    }

    public void recordValidSignature(Identifier witnessId) {
        var status = witnessStatuses.computeIfAbsent(witnessId, k -> new WitnessStatus());
        status.validSignatures++;
    }

    public void evaluateRecovery(Identifier witnessId) {
        var status = witnessStatuses.get(witnessId);
        if (status != null && status.validSignatures >= 100) {
            // Sufficient valid signatures to clear suspicion
            status.suspicious = false;
            status.markedForExclusion = false;
        }
    }

    public void markForExclusion(Identifier witnessId) {
        var status = witnessStatuses.computeIfAbsent(witnessId, k -> new WitnessStatus());
        status.markedForExclusion = true;
        status.suspicious = true;
    }

    public boolean isMarkedForExclusion(Identifier witnessId) {
        return witnessStatuses.getOrDefault(witnessId, new WitnessStatus()).markedForExclusion;
    }

    public String generateForensicReport(Identifier witnessId) {
        var status = witnessStatuses.get(witnessId);
        if (status == null) {
            return "No record for witness: " + witnessId;
        }

        return String.format("""
            Byzantine Forensic Report
            =========================
            Witness: %s
            Invalid Signatures: %d
            Valid Signatures: %d
            Participation Rate: %.1f%%
            Suspicious: %s
            Marked for Exclusion: %s

            This witness has exhibited Byzantine behavior through invalid signature submissions.
            """,
            witnessId,
            status.invalidSignatures,
            status.validSignatures,
            status.participationRate,
            status.suspicious,
            status.markedForExclusion
        );
    }

    public java.util.Map<Identifier, WitnessStatus> getIdentifiedByzantineNodes() {
        var result = new java.util.concurrent.ConcurrentHashMap<Identifier, WitnessStatus>();
        witnessStatuses.entrySet().stream()
            .filter(e -> e.getValue().suspicious)
            .forEach(e -> result.put(e.getKey(), e.getValue()));
        return result;
    }

    private static class WitnessStatus {
        int invalidSignatures = 0;
        int validSignatures = 0;
        double participationRate = 100.0;
        boolean suspicious = false;
        boolean markedForExclusion = false;
    }
}
