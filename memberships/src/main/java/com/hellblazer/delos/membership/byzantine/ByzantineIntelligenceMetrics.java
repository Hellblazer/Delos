/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.byzantine;

/**
 * Metrics for Byzantine intelligence coordination.
 * <p>
 * Provides observability into the cross-layer detection system including
 * polling performance, response rates, and score distributions.
 * Abstracted from underlying metrics implementation (Dropwizard/Micrometer).
 * </p>
 *
 * @author hal.hildebrand
 */
public interface ByzantineIntelligenceMetrics {

    /**
     * Record duration of polling a single layer.
     *
     * @param nanos Duration in nanoseconds
     */
    void recordLayerPollDuration(long nanos);

    /**
     * Record duration of full evaluation cycle across all members.
     *
     * @param nanos Duration in nanoseconds
     */
    void recordEvaluationCycleDuration(long nanos);

    /**
     * Record an aggregated score when evaluated.
     * <p>
     * Score values are in range [0.0, 1.0].
     * </p>
     *
     * @param score Aggregated score value
     */
    void recordAggregatedScore(double score);

    /**
     * Increment warning-level detection counter.
     */
    void incrementWarningDetections();

    /**
     * Increment critical-level detection counter.
     */
    void incrementCriticalDetections();

    /**
     * Record a response triggered (warning or critical).
     */
    void recordResponseTriggered();

    /**
     * Increment pending response counter.
     */
    void incrementPendingResponses();

    /**
     * Decrement pending response counter.
     */
    void decrementPendingResponses();

    /**
     * Increment failed response counter.
     */
    void incrementFailedResponses();

    /**
     * Increment cooldown skip counter.
     */
    void incrementCooldownSkips();

    /**
     * Record the number of tracked members in an evaluation.
     *
     * @param count Number of members tracked
     */
    void recordTrackedMemberCount(int count);

    /**
     * Increment provider error counter.
     */
    void incrementProviderErrors();

    /**
     * Increment rate-limited skip counter.
     * <p>
     * Phase 5: Anti-feedback mechanism tracks when responses are
     * skipped because maxResponsesPerInterval was exceeded.
     * </p>
     */
    void incrementRateLimitedSkips();

    /**
     * Increment deduplicated signal counter.
     * <p>
     * Phase 5: Tracks signal deduplication to prevent amplification
     * when multiple layers detect the same underlying event.
     * </p>
     */
    void incrementDeduplicatedSignals();

    /**
     * Record number of responses in an evaluation interval.
     * <p>
     * Phase 5: Tracks distribution of response counts per interval
     * to monitor rate limiting effectiveness.
     * </p>
     *
     * @param count Number of responses in the interval
     */
    void recordResponsesPerInterval(int count);

    /**
     * No-op implementation for testing.
     *
     * @return Metrics that record nothing
     */
    static ByzantineIntelligenceMetrics noOp() {
        return new NoOpMetrics();
    }

    /**
     * No-op implementation that does nothing.
     */
    class NoOpMetrics implements ByzantineIntelligenceMetrics {

        @Override
        public void recordLayerPollDuration(long nanos) {
            // no-op
        }

        @Override
        public void recordEvaluationCycleDuration(long nanos) {
            // no-op
        }

        @Override
        public void recordAggregatedScore(double score) {
            // no-op
        }

        @Override
        public void incrementWarningDetections() {
            // no-op
        }

        @Override
        public void incrementCriticalDetections() {
            // no-op
        }

        @Override
        public void recordResponseTriggered() {
            // no-op
        }

        @Override
        public void incrementPendingResponses() {
            // no-op
        }

        @Override
        public void decrementPendingResponses() {
            // no-op
        }

        @Override
        public void incrementFailedResponses() {
            // no-op
        }

        @Override
        public void incrementCooldownSkips() {
            // no-op
        }

        @Override
        public void recordTrackedMemberCount(int count) {
            // no-op
        }

        @Override
        public void incrementProviderErrors() {
            // no-op
        }

        @Override
        public void incrementRateLimitedSkips() {
            // no-op
        }

        @Override
        public void incrementDeduplicatedSignals() {
            // no-op
        }

        @Override
        public void recordResponsesPerInterval(int count) {
            // no-op
        }
    }
}
