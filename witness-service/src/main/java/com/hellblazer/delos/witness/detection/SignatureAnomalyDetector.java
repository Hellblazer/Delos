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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects signature anomalies in receipt validation.
 * <p>
 * Tracks per-member signature failure statistics to identify Byzantine behavior patterns including
 * invalid signatures, forgery attempts, and signature verification failures.
 * </p>
 * <p>
 * Scoring logic:
 * - Base score calculated from overall failure rate (signature_failures / total_validations)
 * - Consecutive failures boost score exponentially: baseScore * (1 + consecutiveFailures * 0.15)
 * - Successful verification or non-signature failure resets consecutive counter
 * - Only InvalidSignature results contribute to anomaly score
 * - Score clamped to range [0.0, 1.0]
 * </p>
 * <p>
 * Example scoring:
 * - 1 failure in 100 attempts (1%): score ~0.05
 * - 5 consecutive failures: score ~0.3
 * - 30% failure rate: score ~0.7+
 * - 80% failure rate with consecutive failures: score ~0.95+
 * </p>
 *
 * @author hal.hildebrand
 */
public class SignatureAnomalyDetector implements ByzantineDetector {

    // Consecutive failure boost factor
    private static final double CONSECUTIVE_BOOST_FACTOR = 0.15;

    private final ConcurrentHashMap<Identifier, SignatureStats> memberStats = new ConcurrentHashMap<>();
    private final ByzantineDetectorConfig config;

    /**
     * Signature statistics for a member.
     *
     * @param totalValidations     Total validation attempts
     * @param totalFailures        Count of all validation failures (any type)
     * @param signatureFailures    Count of InvalidSignature results specifically
     * @param consecutiveFailures  Current consecutive signature failure count
     * @param lastFailureTime      Timestamp of last signature failure
     */
    public record SignatureStats(
        long totalValidations,
        long totalFailures,
        long signatureFailures,
        long consecutiveFailures,
        Instant lastFailureTime
    ) {}

    public SignatureAnomalyDetector(ByzantineDetectorConfig config) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
    }

    @Override
    public void recordValidationResult(
        Identifier memberId,
        EventCoordinates receiptCoordinates,
        ValidationResult result,
        long validationTimeMs
    ) {
        Objects.requireNonNull(memberId, "memberId cannot be null");
        Objects.requireNonNull(receiptCoordinates, "receiptCoordinates cannot be null");
        Objects.requireNonNull(result, "result cannot be null");

        // Determine if this is a failure (anything except Valid)
        var isFailure = !(result instanceof ValidationResult.Valid);
        var isSignatureFailure = result instanceof ValidationResult.InvalidSignature;

        // Update statistics based on validation result
        memberStats.compute(memberId, (id, existingStats) -> {
            if (existingStats == null) {
                // First validation for this member
                if (isSignatureFailure) {
                    return new SignatureStats(
                        1,              // totalValidations
                        1,              // totalFailures
                        1,              // signatureFailures
                        1,              // consecutiveFailures
                        Instant.now()   // lastFailureTime
                    );
                } else if (isFailure) {
                    return new SignatureStats(
                        1,              // totalValidations
                        1,              // totalFailures
                        0,              // signatureFailures
                        0,              // consecutiveFailures
                        null            // lastFailureTime
                    );
                } else {
                    return new SignatureStats(
                        1,              // totalValidations
                        0,              // totalFailures
                        0,              // signatureFailures
                        0,              // consecutiveFailures
                        null            // lastFailureTime
                    );
                }
            } else {
                var newTotal = existingStats.totalValidations() + 1;
                var newTotalFailures = isFailure ? existingStats.totalFailures() + 1 : existingStats.totalFailures();

                if (isSignatureFailure) {
                    // Signature failure: increment failure counters
                    var newSignatureFailures = existingStats.signatureFailures() + 1;
                    var newConsecutiveFailures = existingStats.consecutiveFailures() + 1;

                    return new SignatureStats(
                        newTotal,
                        newTotalFailures,
                        newSignatureFailures,
                        newConsecutiveFailures,
                        Instant.now()
                    );
                } else {
                    // Success or non-signature failure: reset consecutive counter
                    return new SignatureStats(
                        newTotal,
                        newTotalFailures,
                        existingStats.signatureFailures(),
                        0,              // Reset consecutive failures
                        existingStats.lastFailureTime()
                    );
                }
            }
        });
    }

    @Override
    public double getAnomalyScore(Identifier memberId) {
        Objects.requireNonNull(memberId, "memberId cannot be null");

        var stats = memberStats.get(memberId);
        if (stats == null || stats.totalValidations() == 0) {
            return 0.0;
        }

        // Calculate base score from overall failure rate (provides baseline from any validation failure)
        var overallFailureRate = (double) stats.totalFailures() / stats.totalValidations();

        // Calculate signature-specific failure rate (higher weight for actual signature failures)
        var signatureFailureRate = (double) stats.signatureFailures() / stats.totalValidations();

        // Combined score: weight overall failures lightly (10%), signature failures heavily (90%)
        var baseScore = (overallFailureRate * 0.1) + (signatureFailureRate * 0.9);

        // Boost for consecutive failures: each consecutive failure adds 15% to the score
        var consecutiveBoost = 1.0 + (stats.consecutiveFailures() * CONSECUTIVE_BOOST_FACTOR);
        var boostedScore = baseScore * consecutiveBoost;

        // Clamp to [0.0, 1.0]
        return Math.min(1.0, Math.max(0.0, boostedScore));
    }

    @Override
    public List<DetectedAnomaly> getDetectedAnomalies() {
        var anomalies = new ArrayList<DetectedAnomaly>();

        for (var entry : memberStats.entrySet()) {
            var memberId = entry.getKey();
            var stats = entry.getValue();
            var score = getAnomalyScore(memberId);

            // Report anomalies above warning threshold
            if (score >= config.warningAnomalyScore()) {
                var evidence = List.of(
                    String.format("Signature failures: %d / %d (%.1f%%)",
                        stats.signatureFailures(),
                        stats.totalValidations(),
                        100.0 * stats.signatureFailures() / stats.totalValidations()
                    ),
                    String.format("Consecutive failures: %d", stats.consecutiveFailures()),
                    stats.lastFailureTime() != null
                        ? String.format("Last failure: %s", stats.lastFailureTime())
                        : "No recent failures"
                );

                // Determine anomaly type based on failure characteristics
                var anomalyType = determineAnomalyType(stats, score);

                var description = String.format(
                    "Signature anomaly detected (failure rate: %.1f%%, consecutive: %d)",
                    100.0 * stats.signatureFailures() / stats.totalValidations(),
                    stats.consecutiveFailures()
                );

                anomalies.add(new DetectedAnomaly(
                    memberId,
                    getDetectorName(),
                    score,
                    description,
                    anomalyType,
                    Instant.now(),
                    evidence
                ));
            }
        }

        return anomalies;
    }

    @Override
    public void reset() {
        memberStats.clear();
    }

    @Override
    public String getDetectorName() {
        return "SignatureAnomalyDetector";
    }

    /**
     * Get signature statistics for a specific member.
     *
     * @param memberId Member identifier
     * @return Signature statistics or null if no data
     */
    public SignatureStats getSignatureStats(Identifier memberId) {
        Objects.requireNonNull(memberId, "memberId cannot be null");
        return memberStats.get(memberId);
    }

    /**
     * Determine the specific anomaly type based on failure characteristics.
     *
     * @param stats Member statistics
     * @param score Current anomaly score
     * @return Appropriate AnomalyType
     */
    private AnomalyType determineAnomalyType(SignatureStats stats, double score) {
        // High consecutive failures with high score suggests forgery attempt
        if (stats.consecutiveFailures() >= config.invalidSignatureThreshold() &&
            score >= config.criticalAnomalyScore()) {
            return AnomalyType.SIGNATURE_FORGERY;
        }

        // Default to general signature invalid
        return AnomalyType.SIGNATURE_INVALID;
    }
}
