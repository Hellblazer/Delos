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
 * Uses exponential moving average (EMA) to weight recent events more heavily.
 * Thread-safe implementation for concurrent access.
 * </p>
 * <p>
 * <b>Performance (Phase 0 Fix)</b>: Uses O(1) incremental EMA update instead of
 * O(N) full recalculation. With constant alpha (default 0.1), each recordEvent()
 * simply computes: ema = alpha * contribution + (1 - alpha) * ema
 * </p>
 *
 * @author hal.hildebrand
 */
public class AnomalyScore {

    private static final int DEFAULT_HISTORY_SIZE = 1000;
    private static final double DEFAULT_ALPHA = 0.1;

    private final Identifier memberId;
    private final int historySize;
    private final double alpha;
    private final Deque<ScoredEvent> history;
    private final AtomicReference<Double> currentScore;
    private final Object lock = new Object();

    // Running EMA value for O(1) updates
    private double ema = 0.0;

    /**
     * Create AnomalyScore with default alpha (0.1).
     *
     * @param memberId    Member identifier
     * @param historySize Maximum history size for event tracking
     */
    public AnomalyScore(Identifier memberId, int historySize) {
        this(memberId, historySize, DEFAULT_ALPHA);
    }

    /**
     * Create AnomalyScore with configurable alpha.
     *
     * @param memberId    Member identifier
     * @param historySize Maximum history size for event tracking
     * @param alpha       EMA smoothing factor (0.0-1.0). Higher = more weight on recent events.
     */
    public AnomalyScore(Identifier memberId, int historySize, double alpha) {
        this.memberId = Objects.requireNonNull(memberId, "memberId cannot be null");
        this.historySize = historySize > 0 ? historySize : DEFAULT_HISTORY_SIZE;
        this.alpha = (alpha > 0 && alpha <= 1.0) ? alpha : DEFAULT_ALPHA;
        this.history = new ArrayDeque<>();
        this.currentScore = new AtomicReference<>(0.0);
    }

    /**
     * Record a new event affecting anomaly score.
     * <p>
     * O(1) operation - uses incremental EMA update instead of full recalculation.
     * </p>
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

            // O(1) incremental EMA update
            updateEmaIncremental(scoreContribution);
        }
    }

    /**
     * Apply decay to reduce historical events' influence.
     *
     * @param decayRate Decay multiplier (0.0-1.0)
     */
    public void applyDecay(double decayRate) {
        synchronized (lock) {
            ema = ema * decayRate;
            currentScore.set(Math.max(0.0, ema));
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

    /**
     * O(1) incremental EMA update.
     * <p>
     * Phase 0 Fix: Replaces O(N) recalculateScore() which iterated entire history.
     * Formula: ema = alpha * contribution + (1 - alpha) * ema
     * </p>
     *
     * @param scoreContribution New event's score contribution
     */
    private void updateEmaIncremental(double scoreContribution) {
        ema = alpha * scoreContribution + (1 - alpha) * ema;
        currentScore.set(Math.max(0.0, ema));
    }

    /**
     * Get the configured alpha value.
     *
     * @return EMA smoothing factor
     */
    public double getAlpha() {
        return alpha;
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
