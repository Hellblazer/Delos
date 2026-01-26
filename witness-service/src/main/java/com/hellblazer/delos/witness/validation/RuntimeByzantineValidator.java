/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.validation;

import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.witness.WitnessContext;
import com.hellblazer.delos.witness.detection.ByzantineDetectorCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Runtime Byzantine quorum enforcement for witness signature validation.
 * <p>
 * Phase 1A-3-C.1: Provides pre-validation before signature accumulation:
 * <ul>
 *   <li>Committee membership check - only selected members can sign</li>
 *   <li>Early quorum rejection - reject signatures after threshold met</li>
 *   <li>Byzantine member rejection - reject known Byzantine actors</li>
 * </ul>
 * </p>
 * <p>
 * Thread-safe: Uses concurrent data structures and is safe for multi-threaded access.
 * </p>
 *
 * @author hal.hildebrand
 */
public class RuntimeByzantineValidator {

    private static final Logger log = LoggerFactory.getLogger(RuntimeByzantineValidator.class);

    /**
     * Configuration for Byzantine validation behavior.
     */
    public record Config(
        double byzantineScoreThreshold,  // Score above which member is rejected (default 0.8)
        boolean strictCommitteeEnforcement,  // Reject non-committee members (default true)
        boolean rejectAfterQuorum  // Reject late signatures after quorum (default true)
    ) {
        public static Config defaults() {
            return new Config(0.8, true, true);
        }

        public static Config lenient() {
            return new Config(0.9, false, false);
        }
    }

    /**
     * Validation result with status and details.
     */
    public record ValidationResult(
        ValidationStatus status,
        String reason
    ) {
        public static ValidationResult accepted() {
            return new ValidationResult(ValidationStatus.ACCEPTED, "Signature accepted");
        }

        public static ValidationResult rejectedNotInCommittee(Identifier member) {
            return new ValidationResult(
                ValidationStatus.REJECTED_NOT_IN_COMMITTEE,
                "Member %s not in committee for event".formatted(member)
            );
        }

        public static ValidationResult rejectedDuplicate(Identifier member) {
            return new ValidationResult(
                ValidationStatus.REJECTED_DUPLICATE,
                "Duplicate signature from member %s".formatted(member)
            );
        }

        public static ValidationResult rejectedQuorumMet() {
            return new ValidationResult(
                ValidationStatus.REJECTED_QUORUM_MET,
                "Quorum threshold already met"
            );
        }

        public static ValidationResult rejectedByzantine(Identifier member, String reason) {
            return new ValidationResult(
                ValidationStatus.REJECTED_BYZANTINE,
                "Member %s rejected as Byzantine: %s".formatted(member, reason)
            );
        }
    }

    /**
     * Tracks per-event validation state.
     */
    private static class EventValidationState {
        private final Set<Identifier> acceptedSigners = ConcurrentHashMap.newKeySet();
        private volatile boolean quorumMet = false;

        boolean hasAccepted(Identifier member) {
            return acceptedSigners.contains(member);
        }

        void recordAccepted(Identifier member) {
            acceptedSigners.add(member);
        }

        void markQuorumMet() {
            quorumMet = true;
        }

        boolean isQuorumMet() {
            return quorumMet;
        }
    }

    private final WitnessContext witnessContext;
    private final ByzantineWitnessDetector byzantineDetector;
    private final ByzantineDetectorCoordinator detectorCoordinator;  // nullable
    private final Config config;
    private final ConcurrentHashMap<String, EventValidationState> eventStates;

    // Metrics
    private final java.util.concurrent.atomic.AtomicLong acceptedCount = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong rejectedNotInCommitteeCount = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong rejectedDuplicateCount = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong rejectedQuorumMetCount = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong rejectedByzantineCount = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Create validator with default configuration.
     *
     * @param witnessContext WitnessContext for committee selection
     * @param byzantineDetector Byzantine detector for member status
     */
    public RuntimeByzantineValidator(WitnessContext witnessContext, ByzantineWitnessDetector byzantineDetector) {
        this(witnessContext, byzantineDetector, null, Config.defaults());
    }

    /**
     * Create validator with full configuration.
     *
     * @param witnessContext WitnessContext for committee selection
     * @param byzantineDetector Byzantine detector for member status
     * @param detectorCoordinator Optional coordinator for anomaly scores (nullable)
     * @param config Validation configuration
     */
    public RuntimeByzantineValidator(
        WitnessContext witnessContext,
        ByzantineWitnessDetector byzantineDetector,
        ByzantineDetectorCoordinator detectorCoordinator,
        Config config
    ) {
        this.witnessContext = Objects.requireNonNull(witnessContext, "witnessContext required");
        this.byzantineDetector = Objects.requireNonNull(byzantineDetector, "byzantineDetector required");
        this.detectorCoordinator = detectorCoordinator;  // nullable
        this.config = Objects.requireNonNull(config, "config required");
        this.eventStates = new ConcurrentHashMap<>();
    }

    /**
     * Validate a signature submission before accumulation.
     * <p>
     * Performs pre-validation checks in order:
     * <ol>
     *   <li>Check if member is marked Byzantine (immediate reject)</li>
     *   <li>Check if member is in committee for event (if strict enforcement enabled)</li>
     *   <li>Check if quorum already met (if late rejection enabled)</li>
     *   <li>Check if duplicate submission from member</li>
     * </ol>
     * </p>
     *
     * @param event Event coordinates being signed
     * @param member Member submitting signature
     * @return Validation result with status and reason
     */
    public ValidationResult validate(EventCoordinates event, Identifier member) {
        Objects.requireNonNull(event, "event required");
        Objects.requireNonNull(member, "member required");

        // 1. Check Byzantine status first (most critical)
        var byzantineResult = checkByzantineStatus(member);
        if (byzantineResult != null) {
            rejectedByzantineCount.incrementAndGet();
            log.debug("Rejected Byzantine member {} for event {}", member, event);
            return byzantineResult;
        }

        // 2. Check committee membership (if strict enforcement)
        if (config.strictCommitteeEnforcement()) {
            if (!witnessContext.isCommitteeMember(member, event)) {
                rejectedNotInCommitteeCount.incrementAndGet();
                log.debug("Rejected non-committee member {} for event {}", member, event);
                return ValidationResult.rejectedNotInCommittee(member);
            }
        }

        // Get or create event state
        var eventKey = eventKey(event);
        var state = eventStates.computeIfAbsent(eventKey, k -> new EventValidationState());

        // 3. Check if quorum already met (if late rejection enabled)
        if (config.rejectAfterQuorum() && state.isQuorumMet()) {
            rejectedQuorumMetCount.incrementAndGet();
            log.debug("Rejected late signature from {} for event {} (quorum met)", member, event);
            return ValidationResult.rejectedQuorumMet();
        }

        // 4. Check for duplicate signature
        if (state.hasAccepted(member)) {
            rejectedDuplicateCount.incrementAndGet();
            log.debug("Rejected duplicate signature from {} for event {}", member, event);
            return ValidationResult.rejectedDuplicate(member);
        }

        // All checks passed
        acceptedCount.incrementAndGet();
        return ValidationResult.accepted();
    }

    /**
     * Record that a signature was successfully accumulated.
     * <p>
     * Called after signature passes accumulator validation to track state.
     * </p>
     *
     * @param event Event coordinates
     * @param member Member who signed
     */
    public void recordAccepted(EventCoordinates event, Identifier member) {
        var eventKey = eventKey(event);
        var state = eventStates.computeIfAbsent(eventKey, k -> new EventValidationState());
        state.recordAccepted(member);
    }

    /**
     * Record that quorum threshold was met for an event.
     * <p>
     * Called when accumulator reports ThresholdMet result.
     * Future signatures will be rejected as late.
     * </p>
     *
     * @param event Event coordinates
     */
    public void recordQuorumMet(EventCoordinates event) {
        var eventKey = eventKey(event);
        var state = eventStates.computeIfAbsent(eventKey, k -> new EventValidationState());
        state.markQuorumMet();
        log.debug("Quorum met for event {}", event);
    }

    /**
     * Clear state for completed event.
     * <p>
     * Called when receipt collection is finalized to free memory.
     * </p>
     *
     * @param event Event coordinates
     */
    public void clearEventState(EventCoordinates event) {
        var eventKey = eventKey(event);
        eventStates.remove(eventKey);
    }

    /**
     * Reset validator on view change.
     * <p>
     * Clears all event states. Called during epoch transitions.
     * </p>
     */
    public void resetOnViewChange() {
        eventStates.clear();
        log.info("RuntimeByzantineValidator reset on view change");
    }

    /**
     * Check if member is marked as Byzantine.
     *
     * @param member Member to check
     * @return Rejection result if Byzantine, null otherwise
     */
    private ValidationResult checkByzantineStatus(Identifier member) {
        // Check ByzantineWitnessDetector first
        if (byzantineDetector.isMarkedForExclusion(member)) {
            return ValidationResult.rejectedByzantine(member, "marked for exclusion");
        }

        if (byzantineDetector.shouldShun(member)) {
            return ValidationResult.rejectedByzantine(member, "BLS failure threshold exceeded");
        }

        if (byzantineDetector.isSuspicious(member)) {
            // Suspicious but not fully excluded - check threshold
            if (detectorCoordinator != null) {
                var anomalyScore = detectorCoordinator.getAnomalyScore(member);
                if (anomalyScore >= config.byzantineScoreThreshold()) {
                    return ValidationResult.rejectedByzantine(member,
                        "anomaly score %.2f exceeds threshold %.2f".formatted(
                            anomalyScore, config.byzantineScoreThreshold()));
                }
            }
        }

        return null;  // Not Byzantine
    }

    /**
     * Generate unique key for event tracking.
     */
    private String eventKey(EventCoordinates event) {
        return event.getDigest().toString() + ":" + event.getSequenceNumber();
    }

    /**
     * Get validation statistics.
     *
     * @return Current stats snapshot
     */
    public Stats getStats() {
        return new Stats(
            acceptedCount.get(),
            rejectedNotInCommitteeCount.get(),
            rejectedDuplicateCount.get(),
            rejectedQuorumMetCount.get(),
            rejectedByzantineCount.get(),
            eventStates.size()
        );
    }

    /**
     * Validation statistics.
     */
    public record Stats(
        long accepted,
        long rejectedNotInCommittee,
        long rejectedDuplicate,
        long rejectedQuorumMet,
        long rejectedByzantine,
        int activeEvents
    ) {
        public long totalRejected() {
            return rejectedNotInCommittee + rejectedDuplicate + rejectedQuorumMet + rejectedByzantine;
        }

        public double acceptanceRate() {
            var total = accepted + totalRejected();
            return total > 0 ? (double) accepted / total : 1.0;
        }
    }
}
