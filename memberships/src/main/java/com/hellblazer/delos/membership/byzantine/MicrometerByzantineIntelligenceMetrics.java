/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.byzantine;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Micrometer implementation of ByzantineIntelligenceMetrics.
 *
 * @author hal.hildebrand
 */
public class MicrometerByzantineIntelligenceMetrics implements ByzantineIntelligenceMetrics {

    // Duration metrics (Timers)
    private final Timer layerPollTimer;
    private final Timer evaluationCycleTimer;

    // Distribution metrics (DistributionSummary)
    private final DistributionSummary aggregatedScore;
    private final DistributionSummary trackedMemberCount;
    private final DistributionSummary responsesPerInterval;

    // Counter metrics
    private final Counter warningDetections;
    private final Counter criticalDetections;
    private final Counter responsesTriggered;
    private final Counter failedResponses;
    private final Counter cooldownSkips;
    private final Counter providerErrors;
    private final Counter rateLimitedSkips;
    private final Counter deduplicatedSignals;

    // Gauge metric (for pending responses)
    private final AtomicInteger pendingResponses;

    public MicrometerByzantineIntelligenceMetrics(MeterRegistry registry) {
        // Timers
        layerPollTimer = Timer.builder("byzantine.intelligence.layer.poll.duration")
                              .description("Duration of polling a single layer")
                              .register(registry);
        evaluationCycleTimer = Timer.builder("byzantine.intelligence.evaluation.cycle.duration")
                                    .description("Duration of full evaluation cycle across all members")
                                    .register(registry);

        // Distribution summaries
        aggregatedScore = DistributionSummary.builder("byzantine.intelligence.aggregated.score")
                                            .description("Aggregated score distribution (0.0-1.0)")
                                            .register(registry);
        trackedMemberCount = DistributionSummary.builder("byzantine.intelligence.tracked.members")
                                               .description("Number of tracked members per evaluation")
                                               .register(registry);
        responsesPerInterval = DistributionSummary.builder("byzantine.intelligence.responses.per.interval")
                                                 .description("Number of responses per evaluation interval")
                                                 .register(registry);

        // Counters
        warningDetections = Counter.builder("byzantine.intelligence.detections.warning")
                                   .description("Warning-level detections")
                                   .register(registry);
        criticalDetections = Counter.builder("byzantine.intelligence.detections.critical")
                                    .description("Critical-level detections")
                                    .register(registry);
        responsesTriggered = Counter.builder("byzantine.intelligence.responses.triggered")
                                    .description("Total responses triggered (warning or critical)")
                                    .register(registry);
        failedResponses = Counter.builder("byzantine.intelligence.responses.failed")
                                 .description("Failed responses")
                                 .register(registry);
        cooldownSkips = Counter.builder("byzantine.intelligence.cooldown.skips")
                               .description("Responses skipped due to cooldown")
                               .register(registry);
        providerErrors = Counter.builder("byzantine.intelligence.provider.errors")
                                .description("Errors from layer providers")
                                .register(registry);
        rateLimitedSkips = Counter.builder("byzantine.intelligence.rate.limited.skips")
                                  .description("Responses skipped due to rate limiting")
                                  .register(registry);
        deduplicatedSignals = Counter.builder("byzantine.intelligence.signals.deduplicated")
                                     .description("Signals deduplicated to prevent amplification")
                                     .register(registry);

        // Gauge for pending responses
        pendingResponses = registry.gauge("byzantine.intelligence.responses.pending",
                                         new AtomicInteger(0));
    }

    // === Duration Recording (Timers) ===

    @Override
    public void recordLayerPollDuration(long nanos) {
        layerPollTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordEvaluationCycleDuration(long nanos) {
        evaluationCycleTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    // === Distribution Recording ===

    @Override
    public void recordAggregatedScore(double score) {
        aggregatedScore.record(score);
    }

    @Override
    public void recordTrackedMemberCount(int count) {
        trackedMemberCount.record(count);
    }

    @Override
    public void recordResponsesPerInterval(int count) {
        responsesPerInterval.record(count);
    }

    // === Counter Recording ===

    @Override
    public void incrementWarningDetections() {
        warningDetections.increment();
    }

    @Override
    public void incrementCriticalDetections() {
        criticalDetections.increment();
    }

    @Override
    public void recordResponseTriggered() {
        responsesTriggered.increment();
    }

    @Override
    public void incrementFailedResponses() {
        failedResponses.increment();
    }

    @Override
    public void incrementCooldownSkips() {
        cooldownSkips.increment();
    }

    @Override
    public void incrementProviderErrors() {
        providerErrors.increment();
    }

    @Override
    public void incrementRateLimitedSkips() {
        rateLimitedSkips.increment();
    }

    @Override
    public void incrementDeduplicatedSignals() {
        deduplicatedSignals.increment();
    }

    // === Gauge Management ===

    @Override
    public void incrementPendingResponses() {
        pendingResponses.incrementAndGet();
    }

    @Override
    public void decrementPendingResponses() {
        pendingResponses.decrementAndGet();
    }
}
