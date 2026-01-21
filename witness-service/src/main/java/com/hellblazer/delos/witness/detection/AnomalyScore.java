/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import com.hellblazer.delos.stereotomy.identifier.Identifier;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-member anomaly score with sliding window history.
 * <p>
 * Uses exponential moving average to weight recent events more heavily.
 * Thread-safe implementation for concurrent access.
 * </p>
 *
 * @author hal.hildebrand
 */
public class AnomalyScore {

    private static final int DEFAULT_HISTORY_SIZE = 1000;

    private final Identifier memberId;
    private final int historySize;
    private final Deque<ScoredEvent> history;
    private final AtomicReference<Double> currentScore;
    private final Object lock = new Object();

    public AnomalyScore(Identifier memberId, int historySize) {
        this.memberId = Objects.requireNonNull(memberId, "memberId cannot be null");
        this.historySize = historySize > 0 ? historySize : DEFAULT_HISTORY_SIZE;
        this.history = new ArrayDeque<>();
        this.currentScore = new AtomicReference<>(0.0);
    }

    /**
     * Record a new event affecting anomaly score.
     *
     * @param scoreContribution Score contribution (can be negative for recovery)
     * @param eventDescription  Human-readable event description
     */
    public void recordEvent(double scoreContribution, String eventDescription) {
        synchronized (lock) {
            history.addLast(new ScoredEvent(
                scoreContribution,
                eventDescription,
                Instant.now()
            ));

            // Keep history bounded
            while (history.size() > historySize) {
                history.removeFirst();
            }

            // Recalculate exponential moving average
            recalculateScore();
        }
    }

    /**
     * Apply decay to reduce historical events' influence.
     *
     * @param decayRate Decay multiplier (0.0-1.0)
     */
    public void applyDecay(double decayRate) {
        synchronized (lock) {
            var decayedScore = currentScore.get() * decayRate;
            currentScore.set(Math.max(0.0, decayedScore));
        }
    }

    /**
     * Get current anomaly score (0.0 = normal, 1.0 = maximum suspect).
     *
     * @return Current score capped at [0.0, 1.0]
     */
    public double getScore() {
        return Math.min(1.0, Math.max(0.0, currentScore.get()));
    }

    /**
     * Get recent events for debugging/alerting.
     *
     * @param count Number of recent events to retrieve
     * @return List of recent events (most recent last)
     */
    public List<ScoredEvent> getRecentEvents(int count) {
        synchronized (lock) {
            return history.stream()
                .skip(Math.max(0, history.size() - count))
                .toList();
        }
    }

    /**
     * Get member identifier.
     */
    public Identifier getMemberId() {
        return memberId;
    }

    private void recalculateScore() {
        if (history.isEmpty()) {
            currentScore.set(0.0);
            return;
        }

        // Exponential moving average (weight recent events more)
        double ema = 0.0;
        double alpha = 2.0 / (history.size() + 1);

        for (var event : history) {
            ema = alpha * event.scoreContribution() + (1 - alpha) * ema;
        }

        currentScore.set(Math.max(0.0, ema));
    }

    /**
     * Scored event record.
     *
     * @param scoreContribution Contribution to anomaly score
     * @param eventDescription  Human-readable description
     * @param recordedAt        Timestamp when event recorded
     */
    public record ScoredEvent(
        double scoreContribution,
        String eventDescription,
        Instant recordedAt
    ) {}
}
