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
    private final Counter providerErrors;
    private final Counter rateLimitedSkips;
    private final Counter deduplicatedSignals;
    private final Histogram responsesPerInterval;

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
        this.providerErrors = registry.counter(
            name(prefix, "byzantine.provider.errors"));
        this.rateLimitedSkips = registry.counter(
            name(prefix, "byzantine.ratelimit.skips"));
        this.deduplicatedSignals = registry.counter(
            name(prefix, "byzantine.signals.deduplicated"));
        this.responsesPerInterval = registry.histogram(
            name(prefix, "byzantine.responses.per.interval"));
    }

    @Override
    public void recordLayerPollDuration(long nanos) {
        layerPollDuration.update(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordEvaluationCycleDuration(long nanos) {
        evaluationCycleDuration.update(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordAggregatedScore(double score) {
        aggregatedScoreDistribution.update((long) (score * 1000));
    }

    @Override
    public void incrementWarningDetections() {
        warningDetections.inc();
    }

    @Override
    public void incrementCriticalDetections() {
        criticalDetections.inc();
    }

    @Override
    public void recordResponseTriggered() {
        responsesTriggered.mark();
    }

    @Override
    public void incrementPendingResponses() {
        pendingResponses.inc();
    }

    @Override
    public void decrementPendingResponses() {
        pendingResponses.dec();
    }

    @Override
    public void incrementFailedResponses() {
        failedResponses.inc();
    }

    @Override
    public void incrementCooldownSkips() {
        cooldownSkips.inc();
    }

    @Override
    public void recordTrackedMemberCount(int count) {
        trackedMemberCount.update(count);
    }

    @Override
    public void incrementProviderErrors() {
        providerErrors.inc();
    }

    @Override
    public void incrementRateLimitedSkips() {
        rateLimitedSkips.inc();
    }

    @Override
    public void incrementDeduplicatedSignals() {
        deduplicatedSignals.inc();
    }

    @Override
    public void recordResponsesPerInterval(int count) {
        responsesPerInterval.update(count);
    }
}
