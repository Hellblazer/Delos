/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.byzantine;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration for the ByzantineIntelligenceCoordinator.
 * <p>
 * Controls polling intervals, thresholds, and layer weights for cross-layer
 * Byzantine detection. All parameters are runtime-configurable.
 * </p>
 * <p>
 * <b>Amendment 6: Per-Layer Poll Intervals</b><br>
 * Different layers may have different optimal polling frequencies based on
 * their signal characteristics:
 * <ul>
 *   <li>ETHEREAL: 2s (consensus events are time-critical)</li>
 *   <li>FIREFLIES: 5s (membership changes are less frequent)</li>
 *   <li>THOTH: 10s (DHT operations are slower)</li>
 * </ul>
 * </p>
 *
 * @param defaultPollInterval Default interval for polling layers
 * @param layerPollIntervals  Per-layer poll interval overrides
 * @param warningThreshold    Score threshold for warning-level response [0.0, 1.0)
 * @param criticalThreshold   Score threshold for critical response (warningThreshold, 1.0]
 * @param layerWeights        Weights for each layer's contribution to aggregated score
 * @param responseCooldown    Minimum time between responses for the same member
 * @param scoreDecayRate      Rate at which scores decay per evaluation cycle [0.0, 1.0]
 * @author hal.hildebrand
 */
public record IntelligenceConfig(
    Duration defaultPollInterval,
    Map<String, Duration> layerPollIntervals,
    double warningThreshold,
    double criticalThreshold,
    Map<String, Double> layerWeights,
    Duration responseCooldown,
    double scoreDecayRate
) {
    /**
     * Standard layer names used across the system.
     */
    public static final String LAYER_FIREFLIES = "FIREFLIES";
    public static final String LAYER_ETHEREAL = "ETHEREAL";
    public static final String LAYER_THOTH = "THOTH";
    public static final String LAYER_GORGONEION = "GORGONEION";

    public IntelligenceConfig {
        Objects.requireNonNull(defaultPollInterval, "defaultPollInterval cannot be null");
        Objects.requireNonNull(layerPollIntervals, "layerPollIntervals cannot be null");
        Objects.requireNonNull(layerWeights, "layerWeights cannot be null");
        Objects.requireNonNull(responseCooldown, "responseCooldown cannot be null");

        if (warningThreshold < 0.0 || warningThreshold >= 1.0) {
            throw new IllegalArgumentException("warningThreshold must be in range [0.0, 1.0)");
        }
        if (criticalThreshold <= warningThreshold || criticalThreshold > 1.0) {
            throw new IllegalArgumentException(
                "criticalThreshold must be in range (warningThreshold, 1.0]");
        }
        if (scoreDecayRate < 0.0 || scoreDecayRate > 1.0) {
            throw new IllegalArgumentException("scoreDecayRate must be in range [0.0, 1.0]");
        }

        // Defensive copies
        layerPollIntervals = Map.copyOf(layerPollIntervals);
        layerWeights = Map.copyOf(layerWeights);
    }

    /**
     * Get poll interval for a specific layer.
     * <p>
     * Returns the layer-specific interval if configured, otherwise the default.
     * </p>
     *
     * @param layerName Layer to get interval for
     * @return Poll interval for this layer
     */
    public Duration getPollIntervalFor(String layerName) {
        return layerPollIntervals.getOrDefault(layerName, defaultPollInterval);
    }

    /**
     * Get weight for a specific layer.
     * <p>
     * Returns the configured weight or 1.0 if not specified.
     * </p>
     *
     * @param layerName Layer to get weight for
     * @return Weight for this layer's contribution
     */
    public double getWeightFor(String layerName) {
        return layerWeights.getOrDefault(layerName, 1.0);
    }

    /**
     * Default configuration suitable for production use.
     * <p>
     * Uses conservative thresholds and recommended layer weights based on
     * signal reliability.
     * </p>
     *
     * @return Default configuration
     */
    public static IntelligenceConfig defaults() {
        return new IntelligenceConfig(
            Duration.ofSeconds(5),                          // defaultPollInterval
            Map.of(                                         // layerPollIntervals (Amendment 6)
                LAYER_ETHEREAL, Duration.ofSeconds(2),
                LAYER_FIREFLIES, Duration.ofSeconds(5),
                LAYER_THOTH, Duration.ofSeconds(10),
                LAYER_GORGONEION, Duration.ofSeconds(10)
            ),
            0.5,                                            // warningThreshold
            0.8,                                            // criticalThreshold
            Map.of(                                         // layerWeights (baseline)
                LAYER_FIREFLIES, 0.4,
                LAYER_ETHEREAL, 0.3,
                LAYER_THOTH, 0.2,
                LAYER_GORGONEION, 0.1
            ),
            Duration.ofSeconds(15),                         // responseCooldown (3x default poll)
            0.95                                            // scoreDecayRate (5% decay per cycle)
        );
    }

    /**
     * Builder for creating custom configurations.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for IntelligenceConfig.
     */
    public static class Builder {
        private Duration defaultPollInterval = Duration.ofSeconds(5);
        private Map<String, Duration> layerPollIntervals = Map.of();
        private double warningThreshold = 0.5;
        private double criticalThreshold = 0.8;
        private Map<String, Double> layerWeights = Map.of();
        private Duration responseCooldown = Duration.ofSeconds(15);
        private double scoreDecayRate = 0.95;

        public Builder defaultPollInterval(Duration interval) {
            this.defaultPollInterval = interval;
            return this;
        }

        public Builder layerPollIntervals(Map<String, Duration> intervals) {
            this.layerPollIntervals = intervals;
            return this;
        }

        public Builder warningThreshold(double threshold) {
            this.warningThreshold = threshold;
            return this;
        }

        public Builder criticalThreshold(double threshold) {
            this.criticalThreshold = threshold;
            return this;
        }

        public Builder layerWeights(Map<String, Double> weights) {
            this.layerWeights = weights;
            return this;
        }

        public Builder responseCooldown(Duration cooldown) {
            this.responseCooldown = cooldown;
            return this;
        }

        public Builder scoreDecayRate(double rate) {
            this.scoreDecayRate = rate;
            return this;
        }

        public IntelligenceConfig build() {
            return new IntelligenceConfig(
                defaultPollInterval,
                layerPollIntervals,
                warningThreshold,
                criticalThreshold,
                layerWeights,
                responseCooldown,
                scoreDecayRate
            );
        }
    }
}
