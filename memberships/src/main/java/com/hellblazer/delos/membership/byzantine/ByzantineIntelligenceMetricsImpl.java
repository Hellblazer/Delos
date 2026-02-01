/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.byzantine;

import com.codahale.metrics.*;

import static com.codahale.metrics.MetricRegistry.name;

/**
 * Implementation of Byzantine intelligence metrics using Dropwizard Metrics.
 *
 * @author hal.hildebrand
 */
public class ByzantineIntelligenceMetricsImpl implements ByzantineIntelligenceMetrics {

    private final Timer layerPollDuration;
    private final Timer evaluationCycleDuration;
    private final Histogram aggregatedScoreDistribution;
    private final Counter warningDetections;
    private final Counter criticalDetections;
    private final Meter responsesTriggered;
    private final Counter pendingResponses;
    private final Counter failedResponses;
    private final Counter cooldownSkips;
    private final Histogram trackedMemberCount;

    /**
     * Create metrics registered with the given registry.
     *
     * @param registry MetricRegistry for registration
     * @param prefix   Prefix for metric names (typically context ID)
     */
    public ByzantineIntelligenceMetricsImpl(MetricRegistry registry, String prefix) {
        this.layerPollDuration = registry.timer(
            name(prefix, "byzantine.layer.poll.duration"));
        this.evaluationCycleDuration = registry.timer(
            name(prefix, "byzantine.evaluation.cycle.duration"));
        this.aggregatedScoreDistribution = registry.histogram(
            name(prefix, "byzantine.score.distribution"));
        this.warningDetections = registry.counter(
            name(prefix, "byzantine.detections.warning"));
        this.criticalDetections = registry.counter(
            name(prefix, "byzantine.detections.critical"));
        this.responsesTriggered = registry.meter(
            name(prefix, "byzantine.responses.triggered"));
        this.pendingResponses = registry.counter(
            name(prefix, "byzantine.responses.pending"));
        this.failedResponses = registry.counter(
            name(prefix, "byzantine.responses.failed"));
        this.cooldownSkips = registry.counter(
            name(prefix, "byzantine.cooldown.skips"));
        this.trackedMemberCount = registry.histogram(
            name(prefix, "byzantine.members.tracked"));
    }

    @Override
    public Timer layerPollDuration() {
        return layerPollDuration;
    }

    @Override
    public Timer evaluationCycleDuration() {
        return evaluationCycleDuration;
    }

    @Override
    public Histogram aggregatedScoreDistribution() {
        return aggregatedScoreDistribution;
    }

    @Override
    public Counter warningDetections() {
        return warningDetections;
    }

    @Override
    public Counter criticalDetections() {
        return criticalDetections;
    }

    @Override
    public Meter responsesTriggered() {
        return responsesTriggered;
    }

    @Override
    public Counter pendingResponses() {
        return pendingResponses;
    }

    @Override
    public Counter failedResponses() {
        return failedResponses;
    }

    @Override
    public Counter cooldownSkips() {
        return cooldownSkips;
    }

    @Override
    public Histogram trackedMemberCount() {
        return trackedMemberCount;
    }
}
