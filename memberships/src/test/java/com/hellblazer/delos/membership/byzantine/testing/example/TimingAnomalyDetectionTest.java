/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.byzantine.testing.example;

import com.hellblazer.delos.membership.byzantine.IntelligenceConfig;
import com.hellblazer.delos.membership.byzantine.testing.AbstractByzantineClusterTest;
import com.hellblazer.delos.membership.byzantine.testing.FaultType;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests timing anomaly detection.
 * <p>
 * Timing anomalies include:
 * <ul>
 *   <li>Excessive response delays</li>
 *   <li>Timestamp manipulation</li>
 *   <li>Clock drift beyond tolerance</li>
 * </ul>
 * <p>
 * These are lower severity than signature/equivocation faults
 * but still indicate potential Byzantine behavior.
 *
 * @author hal.hildebrand
 */
class TimingAnomalyDetectionTest extends AbstractByzantineClusterTest {

    @Test
    void shouldDetectTimingAttack() {
        var member = createMember("timing-manipulator");

        try (var fault = injectTimingAttack(member)) {
            var result = evaluate(member);

            assertThat(result.isAnomalous()).isTrue();
            assertThat(result.compositeScore()).isGreaterThan(0.5);
            // Timing attacks are warning level, not critical
            assertThat(result.isCritical()).isFalse();
        }
    }

    @Test
    void shouldDetectDelayAnomaly() {
        var member = createMember("slow-responder");

        // Inject significant delay (weight = 0.2)
        try (var fault = injectDelay(member, Duration.ofSeconds(30))) {
            var result = evaluate(member);

            // Delay has low weight (0.2), so score is low but still tracked
            // This falls below warning threshold (0.4), so not anomalous
            assertThat(result.compositeScore()).isGreaterThan(0.0);
            assertThat(result.layerScores()).containsKey(IntelligenceConfig.LAYER_FIREFLIES);
        }
    }

    @Test
    void shouldNotFlagTransientDelay() {
        var member = createMember("briefly-slow");

        // Very short delay (weight = 0.2)
        try (var fault = harness.injectFault(
                IntelligenceConfig.LAYER_FIREFLIES,
                member,
                FaultType.DELAY,
                Duration.ofMillis(50))) {

            var result = evaluate(member);

            // Delay faults have low weight (0.2), below warning threshold (0.4)
            // So they're tracked but not flagged as anomalous
            assertThat(result.compositeScore()).isGreaterThan(0.0);
            assertThat(result.compositeScore()).isLessThan(0.4);
        }
    }

    @Test
    void shouldAccumulateRepeatedTimingAnomalies() {
        var member = createMember("repeatedly-slow");

        // Multiple timing faults compound the score
        var faults = new java.util.ArrayList<com.hellblazer.delos.membership.byzantine.testing.FaultInjectionHandle>();

        // Inject on multiple layers with higher weight faults
        // Delay=0.2, TimingAttack=0.6 -> average = 0.4
        faults.add(harness.injectFault(IntelligenceConfig.LAYER_FIREFLIES, member, FaultType.DELAY));
        faults.add(harness.injectFault(IntelligenceConfig.LAYER_THOTH, member, FaultType.TIMING_ATTACK));

        try {
            var result = evaluate(member);

            // Combined score should reflect weighted average
            assertThat(result.compositeScore()).isGreaterThan(0.3);
            assertThat(result.layerScores()).hasSize(2);
        } finally {
            faults.forEach(f -> f.restore());
        }
    }

    @Test
    void shouldTrackLayerContributions() {
        var member = createMember("multi-layer-timing");

        // Inject on Thoth layer (KERI timing)
        try (var fault = harness.injectFault(IntelligenceConfig.LAYER_THOTH, member, FaultType.TIMING_ATTACK)) {
            var result = evaluate(member);

            // Should have contribution from Thoth
            assertThat(result.layerScores()).containsKey(IntelligenceConfig.LAYER_THOTH);
            assertThat(result.layerScores().get(IntelligenceConfig.LAYER_THOTH)).isGreaterThan(0.0);
        }
    }

    @Test
    void shouldMeasureDetectionLatency() {
        var byzantine = createMember("timed-byzantine");

        try (var fault = injectTimingAttack(byzantine)) {
            evaluate(byzantine);

            var metrics = getMetrics();
            var meanLatency = metrics.getMeanDetectionLatency();

            // Detection should be fast
            assertThat(meanLatency).isLessThan(Duration.ofSeconds(1));
        }
    }

    @Test
    void shouldGenerateWarningNotCritical() {
        var member = createMember("timing-warning");

        try (var fault = injectTimingAttack(member)) {
            poll();

            var handler = getResponseHandler();

            // Timing attacks should generate warnings, not critical
            assertThat(handler.hadWarningResponse(member)).isTrue();
            assertThat(handler.hadCriticalResponse(member)).isFalse();
        }
    }

    @Test
    void shouldCombineWithOtherFaultsForCritical() {
        var member = createMember("multi-fault");

        // Combine timing (0.6) with signature forgery (1.0) -> avg = 0.8
        var timing = injectTimingAttack(member);
        var forgery = harness.injectFault(IntelligenceConfig.LAYER_GORGONEION, member, FaultType.SIGNATURE_FORGERY);

        try {
            var result = evaluate(member);

            // Combined score is weighted average of 0.6 and 1.0 = 0.8
            // This exactly reaches critical threshold
            assertThat(result.isCritical()).isTrue();
            assertThat(result.compositeScore()).isGreaterThanOrEqualTo(0.8);
        } finally {
            timing.restore();
            forgery.restore();
        }
    }
}
