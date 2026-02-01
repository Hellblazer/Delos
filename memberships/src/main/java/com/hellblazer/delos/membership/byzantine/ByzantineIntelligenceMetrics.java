/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.byzantine;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;

/**
 * Metrics for Byzantine intelligence coordination.
 * <p>
 * Provides observability into the cross-layer detection system including
 * polling performance, response rates, and score distributions.
 * </p>
 *
 * @author hal.hildebrand
 */
public interface ByzantineIntelligenceMetrics {

    /**
     * Timer for polling a single layer.
     *
     * @return Timer tracking poll duration
     */
    Timer layerPollDuration();

    /**
     * Timer for full evaluation cycle across all members.
     *
     * @return Timer tracking evaluation duration
     */
    Timer evaluationCycleDuration();

    /**
     * Histogram of aggregated scores when evaluated.
     *
     * @return Histogram of score values [0.0, 1.0] scaled to integer
     */
    Histogram aggregatedScoreDistribution();

    /**
     * Counter of warning-level detections.
     *
     * @return Counter for warning events
     */
    Counter warningDetections();

    /**
     * Counter of critical-level detections.
     *
     * @return Counter for critical events
     */
    Counter criticalDetections();

    /**
     * Meter of responses triggered (both warning and critical).
     *
     * @return Meter for response actions
     */
    Meter responsesTriggered();

    /**
     * Counter of responses currently pending (async not completed).
     *
     * @return Counter for in-flight responses
     */
    Counter pendingResponses();

    /**
     * Counter of responses that failed (exceptionally completed).
     *
     * @return Counter for failed responses
     */
    Counter failedResponses();

    /**
     * Counter of responses skipped due to cooldown.
     *
     * @return Counter for cooldown skips
     */
    Counter cooldownSkips();

    /**
     * Histogram of member count per evaluation.
     *
     * @return Histogram of tracked member counts
     */
    Histogram trackedMemberCount();

    /**
     * Counter of provider errors during polling.
     *
     * @return Counter for provider errors
     */
    Counter providerErrors();

    /**
     * Counter of responses skipped due to rate limiting.
     * <p>
     * Phase 5: Anti-feedback mechanism tracks when responses are
     * skipped because maxResponsesPerInterval was exceeded.
     * </p>
     *
     * @return Counter for rate-limited skips
     */
    Counter rateLimitedSkips();

    /**
     * Counter of signals deduplicated (same event from multiple layers).
     * <p>
     * Phase 5: Tracks signal deduplication to prevent amplification
     * when multiple layers detect the same underlying event.
     * </p>
     *
     * @return Counter for deduplicated signals
     */
    Counter deduplicatedSignals();

    /**
     * Histogram of responses per evaluation interval.
     * <p>
     * Phase 5: Tracks distribution of response counts per interval
     * to monitor rate limiting effectiveness.
     * </p>
     *
     * @return Histogram of response counts
     */
    Histogram responsesPerInterval();

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
        private static final Timer NO_OP_TIMER = new Timer();
        private static final Histogram NO_OP_HISTOGRAM = new Histogram(
            new com.codahale.metrics.UniformReservoir());
        private static final Counter NO_OP_COUNTER = new Counter();
        private static final Meter NO_OP_METER = new Meter();

        @Override
        public Timer layerPollDuration() {
            return NO_OP_TIMER;
        }

        @Override
        public Timer evaluationCycleDuration() {
            return NO_OP_TIMER;
        }

        @Override
        public Histogram aggregatedScoreDistribution() {
            return NO_OP_HISTOGRAM;
        }

        @Override
        public Counter warningDetections() {
            return NO_OP_COUNTER;
        }

        @Override
        public Counter criticalDetections() {
            return NO_OP_COUNTER;
        }

        @Override
        public Meter responsesTriggered() {
            return NO_OP_METER;
        }

        @Override
        public Counter pendingResponses() {
            return NO_OP_COUNTER;
        }

        @Override
        public Counter failedResponses() {
            return NO_OP_COUNTER;
        }

        @Override
        public Counter cooldownSkips() {
            return NO_OP_COUNTER;
        }

        @Override
        public Histogram trackedMemberCount() {
            return NO_OP_HISTOGRAM;
        }

        @Override
        public Counter providerErrors() {
            return NO_OP_COUNTER;
        }

        @Override
        public Counter rateLimitedSkips() {
            return NO_OP_COUNTER;
        }

        @Override
        public Counter deduplicatedSignals() {
            return NO_OP_COUNTER;
        }

        @Override
        public Histogram responsesPerInterval() {
            return NO_OP_HISTOGRAM;
        }
    }
}
