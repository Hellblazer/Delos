/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.witness.aggregation.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;

/**
 * Coordinates multiple Byzantine detectors.
 * <p>
 * Responsibilities:
 * - Manage pluggable detector implementations
 * - Aggregate anomaly scores from detectors
 * - Determine response actions
 * - Provide observability/metrics
 * </p>
 *
 * @author hal.hildebrand
 */
public class ByzantineDetectorCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ByzantineDetectorCoordinator.class);

    private final ByzantineDetectorConfig config;
    private final Map<Identifier, AnomalyScore> memberScores;
    private final List<ByzantineDetector> detectors;
    private final ResponseOrchestrator responseOrchestrator;
    private final ByzantineDetectionMetrics metrics;
    private final ScheduledExecutorService scheduler;
    private final Object lock = new Object();

    public ByzantineDetectorCoordinator(
        ByzantineDetectorConfig config,
        ResponseOrchestrator responseOrchestrator,
        ByzantineDetectionMetrics metrics
    ) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.responseOrchestrator = Objects.requireNonNull(responseOrchestrator, "responseOrchestrator cannot be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics cannot be null");
        this.memberScores = new ConcurrentHashMap<>();
        this.detectors = Collections.synchronizedList(new ArrayList<>());
        this.scheduler = Executors.newScheduledThreadPool(1);

        // Start periodic score decay
        scheduler.scheduleAtFixedRate(
            this::applyScoreDecay,
            config.scoreDecayPeriod().getSeconds(),
            config.scoreDecayPeriod().getSeconds(),
            TimeUnit.SECONDS
        );
    }

    /**
     * Register a Byzantine detector implementation.
     *
     * @param detector Detector to register
     */
    public void registerDetector(ByzantineDetector detector) {
        Objects.requireNonNull(detector, "detector cannot be null");
        detectors.add(detector);
        log.info("Registered detector: {}", detector.getDetectorName());
    }

    /**
     * Record receipt validation result.
     * <p>
     * Notifies all detectors, aggregates anomaly scores, determines response.
     * </p>
     *
     * @param memberId           Member that provided signature
     * @param receiptCoordinates Receipt event coordinates
     * @param result             Validation result
     * @param validationTimeMs   Time to validate (milliseconds)
     */
    public void recordValidationResult(
        Identifier memberId,
        EventCoordinates receiptCoordinates,
        ValidationResult result,
        long validationTimeMs
    ) {
        Objects.requireNonNull(memberId, "memberId cannot be null");
        Objects.requireNonNull(result, "result cannot be null");

        // Notify all detectors
        for (var detector : detectors) {
            detector.recordValidationResult(memberId, receiptCoordinates, result, validationTimeMs);
        }

        // Aggregate anomaly scores
        updateMemberScore(memberId);

        // Check for anomalies requiring response
        checkAndRespond(memberId);
    }

    /**
     * Get current anomaly score for member.
     *
     * @param memberId Member identifier
     * @return Anomaly score in range [0.0, 1.0]
     */
    public double getAnomalyScore(Identifier memberId) {
        return memberScores
            .getOrDefault(memberId, new AnomalyScore(memberId, config.historyWindowSize()))
            .getScore();
    }

    /**
     * Get all detected anomalies across all detectors.
     *
     * @return List of all detected anomalies
     */
    public List<DetectedAnomaly> getDetectedAnomalies() {
        var anomalies = new ArrayList<DetectedAnomaly>();
        for (var detector : detectors) {
            anomalies.addAll(detector.getDetectedAnomalies());
        }
        return anomalies;
    }

    /**
     * Reset coordinator on view change.
     */
    public void resetOnViewChange() {
        synchronized (lock) {
            memberScores.clear();
            for (var detector : detectors) {
                detector.reset();
            }
            log.info("Byzantine detector reset on view change");
        }
    }

    /**
     * Shutdown coordinator (for graceful shutdown).
     */
    public void shutdown() {
        scheduler.shutdownNow();
        for (var detector : detectors) {
            detector.reset();
        }
        log.info("Byzantine detector coordinator shutdown");
    }

    // Private helper methods

    private void updateMemberScore(Identifier memberId) {
        // Count how many detectors vote for anomaly (score >= warning threshold)
        int voteCount = 0;
        double aggregatedScore = 0.0;

        for (var detector : detectors) {
            var detectorScore = detector.getAnomalyScore(memberId);
            aggregatedScore += detectorScore;

            if (detectorScore >= config.warningAnomalyScore()) {
                voteCount++;
            }
        }

        // Record ensemble vote count (0-3)
        metrics.recordEnsembleVote(voteCount);

        // Record quorum if 2+ detectors agree
        if (voteCount >= 2) {
            metrics.incrementQuorumReached();
        }

        // Average across detectors
        if (!detectors.isEmpty()) {
            aggregatedScore /= detectors.size();
        }

        var score = memberScores.computeIfAbsent(
            memberId,
            id -> new AnomalyScore(id, config.historyWindowSize())
        );

        score.recordEvent(aggregatedScore, "Aggregated detector anomaly score");
    }

    private void checkAndRespond(Identifier memberId) {
        var score = getAnomalyScore(memberId);

        if (score >= config.criticalAnomalyScore()) {
            log.warn("CRITICAL: Byzantine behavior detected in {}: score={}", memberId, score);
            responseOrchestrator.handleCriticalAnomaly(memberId, score);
        } else if (score >= config.warningAnomalyScore()) {
            log.info("WARNING: Potential Byzantine behavior in {}: score={}", memberId, score);
            responseOrchestrator.handleWarningAnomaly(memberId, score);
        }
    }

    private void applyScoreDecay() {
        for (var score : memberScores.values()) {
            score.applyDecay(config.scoreDecayRate());
        }
    }
}
