/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.byzantine;

import com.hellblazer.delos.stereotomy.identifier.Identifier;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aggregated risk profile for a member across all layers.
 * <p>
 * Thread-safe implementation supporting concurrent updates from multiple
 * layer pollers. Uses ConcurrentHashMap for layer states and atomic
 * operations for score updates.
 * </p>
 *
 * @author hal.hildebrand
 */
public class MemberRiskProfile {

    private final Identifier memberId;
    private final ConcurrentHashMap<String, LayerAnomalyState> layerStates;
    private final IntelligenceConfig config;
    private volatile double aggregatedScore;
    private volatile Instant lastEvaluated;

    public MemberRiskProfile(Identifier memberId, IntelligenceConfig config) {
        this.memberId = Objects.requireNonNull(memberId, "memberId cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.layerStates = new ConcurrentHashMap<>();
        this.aggregatedScore = 0.0;
        this.lastEvaluated = Instant.now();
    }

    /**
     * Get the member identifier.
     */
    public Identifier getMemberId() {
        return memberId;
    }

    /**
     * Update state from a specific layer.
     * <p>
     * Thread-safe: can be called concurrently from multiple layer pollers.
     * </p>
     *
     * @param state New state from the layer
     */
    public void updateLayerState(LayerAnomalyState state) {
        Objects.requireNonNull(state, "state cannot be null");
        layerStates.put(state.layerName(), state);
        recalculateAggregatedScore();
    }

    /**
     * Get the current aggregated score.
     *
     * @return Score in range [0.0, 1.0]
     */
    public double getAggregatedScore() {
        return aggregatedScore;
    }

    /**
     * Get state from a specific layer.
     *
     * @param layerName Layer to query
     * @return State if present, empty otherwise
     */
    public Optional<LayerAnomalyState> getLayerState(String layerName) {
        return Optional.ofNullable(layerStates.get(layerName));
    }

    /**
     * Get all current layer states.
     *
     * @return Unmodifiable map of layer states
     */
    public Map<String, LayerAnomalyState> getAllLayerStates() {
        return Collections.unmodifiableMap(new HashMap<>(layerStates));
    }

    /**
     * Get names of layers that have active signals.
     *
     * @return Set of layer names with active signals
     */
    public Set<String> getActiveSignalSources() {
        var sources = new HashSet<String>();
        for (var entry : layerStates.entrySet()) {
            if (entry.getValue().hasActiveSignals()) {
                sources.add(entry.getKey());
            }
        }
        return sources;
    }

    /**
     * Apply score decay.
     * <p>
     * Called periodically to reduce influence of stale signals.
     * </p>
     */
    public void applyDecay() {
        aggregatedScore = aggregatedScore * config.scoreDecayRate();
        lastEvaluated = Instant.now();
    }

    /**
     * Get when this profile was last evaluated.
     */
    public Instant getLastEvaluated() {
        return lastEvaluated;
    }

    /**
     * Check if this profile indicates critical Byzantine behavior.
     *
     * @return true if score >= critical threshold
     */
    public boolean isCritical() {
        return aggregatedScore >= config.criticalThreshold();
    }

    /**
     * Check if this profile indicates warning-level behavior.
     *
     * @return true if score >= warning threshold
     */
    public boolean isWarning() {
        return aggregatedScore >= config.warningThreshold();
    }

    /**
     * Check if this profile has negligible score.
     *
     * @param threshold Minimum score to consider non-negligible
     * @return true if score < threshold
     */
    public boolean isNegligible(double threshold) {
        return aggregatedScore < threshold;
    }

    /**
     * Recalculate aggregated score from all layer states.
     * <p>
     * Uses weighted average based on configured layer weights.
     * </p>
     */
    private void recalculateAggregatedScore() {
        double weightedSum = 0.0;
        double totalWeight = 0.0;

        for (var entry : layerStates.entrySet()) {
            var layerName = entry.getKey();
            var state = entry.getValue();
            var weight = config.getWeightFor(layerName);

            weightedSum += state.anomalyScore() * weight;
            totalWeight += weight;
        }

        if (totalWeight > 0) {
            aggregatedScore = Math.min(1.0, weightedSum / totalWeight);
        } else {
            aggregatedScore = 0.0;
        }

        lastEvaluated = Instant.now();
    }

    @Override
    public String toString() {
        return "MemberRiskProfile{" +
            "memberId=" + memberId +
            ", aggregatedScore=" + String.format("%.3f", aggregatedScore) +
            ", activeLayers=" + getActiveSignalSources() +
            '}';
    }
}
