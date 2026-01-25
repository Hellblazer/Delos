/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import java.time.Duration;

/**
 * Configuration for Byzantine detection thresholds and behavior.
 *
 * @param invalidSignatureThreshold          Fail count before flagging
 * @param maxReceiptLatencyMs                Maximum acceptable latency
 * @param timingAnomalyThreshold             Percentile above which = anomaly
 * @param failureRateThreshold               Failure % above which = anomaly
 * @param minSampleSize                      Minimum data points before scoring
 * @param criticalAnomalyScore               Score requiring immediate action
 * @param warningAnomalyScore                Score requiring alerting
 * @param scoreDecayPeriod                   How often anomaly scores decay
 * @param scoreDecayRate                     Decay factor (0.0-1.0)
 * @param enableAutomaticThresholdAdaptation Whether to adapt thresholds automatically
 * @param historyWindowSize                  How many validation results to keep
 * @author hal.hildebrand
 */
public record ByzantineDetectorConfig(
    int invalidSignatureThreshold,
    long maxReceiptLatencyMs,
    double timingAnomalyThreshold,
    double failureRateThreshold,
    int minSampleSize,
    double criticalAnomalyScore,
    double warningAnomalyScore,
    Duration scoreDecayPeriod,
    double scoreDecayRate,
    boolean enableAutomaticThresholdAdaptation,
    int historyWindowSize
) {
    public ByzantineDetectorConfig {
        if (invalidSignatureThreshold < 1) {
            throw new IllegalArgumentException("invalidSignatureThreshold must be >= 1");
        }
        if (failureRateThreshold < 0 || failureRateThreshold > 1.0) {
            throw new IllegalArgumentException("failureRateThreshold must be 0.0-1.0");
        }
        if (criticalAnomalyScore <= warningAnomalyScore) {
            throw new IllegalArgumentException(
                "criticalAnomalyScore must be > warningAnomalyScore"
            );
        }
        if (scoreDecayRate < 0 || scoreDecayRate > 1.0) {
            throw new IllegalArgumentException("scoreDecayRate must be 0.0-1.0");
        }
    }

    /**
     * Default configuration (conservative, suitable for production).
     */
    public static ByzantineDetectorConfig defaults() {
        return new ByzantineDetectorConfig(
            5,                           // invalidSignatureThreshold
            5000,                        // maxReceiptLatencyMs
            0.95,                        // timingAnomalyThreshold
            0.5,                         // failureRateThreshold (50% failure = suspicious)
            50,                          // minSampleSize
            0.9,                         // criticalAnomalyScore
            0.7,                         // warningAnomalyScore
            Duration.ofHours(1),         // scoreDecayPeriod
            0.5,                         // scoreDecayRate (50% decay per period)
            true,                        // enableAutomaticThresholdAdaptation
            1000                         // historyWindowSize
        );
    }
}
