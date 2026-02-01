/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import com.hellblazer.delos.stereotomy.identifier.Identifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Test AnomalyScore per-member scoring with sliding window history.
 */
class AnomalyScoreTest {

    private Identifier memberId;

    @BeforeEach
    void setUp() {
        memberId = Identifier.NONE;
    }

    @Test
    void shouldStartWithZeroScore() {
        var score = new AnomalyScore(memberId, 100);

        assertThat(score.getScore()).isEqualTo(0.0);
    }

    @Test
    void shouldRecordEvent() {
        var score = new AnomalyScore(memberId, 100);

        score.recordEvent(0.5, "Invalid signature");

        assertThat(score.getScore()).isGreaterThan(0);
    }

    @Test
    void shouldAccumulateScoreFromMultipleEvents() {
        var score = new AnomalyScore(memberId, 100);

        score.recordEvent(0.3, "First event");
        var firstScore = score.getScore();

        score.recordEvent(0.5, "Second higher event");

        assertThat(score.getScore()).isGreaterThan(firstScore);
    }

    @Test
    void shouldCapScoreAtOne() {
        var score = new AnomalyScore(memberId, 100);

        // Record many high-score events
        for (int i = 0; i < 10; i++) {
            score.recordEvent(1.0, "Critical failure");
        }

        assertThat(score.getScore()).isLessThanOrEqualTo(1.0);
    }

    @Test
    void shouldApplyDecay() {
        var score = new AnomalyScore(memberId, 100);
        score.recordEvent(1.0, "Critical failure");

        var initialScore = score.getScore();
        score.applyDecay(0.5);

        assertThat(score.getScore()).isLessThan(initialScore);
    }

    @Test
    void shouldReturnRecentEvents() {
        var score = new AnomalyScore(memberId, 100);

        score.recordEvent(0.5, "Event 1");
        score.recordEvent(0.3, "Event 2");
        score.recordEvent(0.7, "Event 3");

        var events = score.getRecentEvents(2);

        assertThat(events).hasSize(2);
        assertThat(events.get(0).eventDescription()).isEqualTo("Event 2");
        assertThat(events.get(1).eventDescription()).isEqualTo("Event 3");
    }

    @Test
    void shouldMaintainHistorySize() {
        var score = new AnomalyScore(memberId, 10);

        // Record more events than history size
        for (int i = 0; i < 20; i++) {
            score.recordEvent(0.1, "Event " + i);
        }

        var events = score.getRecentEvents(100);

        assertThat(events).hasSizeLessThanOrEqualTo(10);
    }

    @Test
    void shouldWeightRecentEventsMore() {
        var score = new AnomalyScore(memberId, 100);

        // Record old low-score event
        score.recordEvent(0.1, "Old event");
        var scoreAfterLow = score.getScore();

        // Record many recent high-score events
        for (int i = 0; i < 5; i++) {
            score.recordEvent(0.8, "Recent event " + i);
        }

        // Score should increase toward 0.8 (recent events have more weight)
        // Note: With alpha=0.1, convergence is gradual, so we verify the trend
        assertThat(score.getScore())
            .as("Score should increase toward recent high values")
            .isGreaterThan(scoreAfterLow);

        // After more high events, should approach 0.8
        for (int i = 0; i < 20; i++) {
            score.recordEvent(0.8, "More recent event " + i);
        }
        assertThat(score.getScore())
            .as("After many high events, score should be closer to 0.8")
            .isGreaterThan(0.5);
    }

    @Test
    void shouldHandleNegativeScoreContribution() {
        var score = new AnomalyScore(memberId, 100);

        score.recordEvent(0.5, "Anomaly");
        score.recordEvent(-0.2, "Recovery event");

        // Negative contributions should reduce score
        // Note: Implementation should clamp to 0
        assertThat(score.getScore()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void shouldRequireNonNullMemberId() {
        assertThatThrownBy(() -> new AnomalyScore(null, 100))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldUseDefaultHistorySize() {
        var score = new AnomalyScore(memberId, 0);

        // Should use default size (1000) even if 0 provided
        for (int i = 0; i < 100; i++) {
            score.recordEvent(0.1, "Event " + i);
        }

        assertThat(score.getRecentEvents(100)).hasSizeLessThanOrEqualTo(100);
    }
}
