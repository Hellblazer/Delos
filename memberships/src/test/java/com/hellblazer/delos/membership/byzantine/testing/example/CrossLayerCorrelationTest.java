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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests cross-layer correlation of Byzantine signals.
 * <p>
 * Byzantine actors often exhibit anomalies across multiple layers
 * simultaneously. Cross-layer correlation provides:
 * <ul>
 *   <li>Higher confidence in detection</li>
 *   <li>Reduced false positive rate</li>
 *   <li>Better characterization of attack patterns</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class CrossLayerCorrelationTest extends AbstractByzantineClusterTest {

    @Test
    void shouldCorrelateAcrossAllLayers() {
        var member = createMember("all-layer-byzantine");

        // Inject on all three layers
        var fireflies = harness.injectFault(IntelligenceConfig.LAYER_FIREFLIES, member, FaultType.EQUIVOCATION);
        var thoth = harness.injectFault(IntelligenceConfig.LAYER_THOTH, member, FaultType.TIMING_ATTACK);
        var gorgoneion = harness.injectFault(IntelligenceConfig.LAYER_GORGONEION, member, FaultType.SIGNATURE_FORGERY);

        try {
            var result = evaluate(member);

            // Should have signals from all layers
            assertThat(result.layerScores()).hasSize(3);
            assertThat(result.layerScores()).containsKeys(
                IntelligenceConfig.LAYER_FIREFLIES,
                IntelligenceConfig.LAYER_THOTH,
                IntelligenceConfig.LAYER_GORGONEION
            );

            // Combined score should be high (weighted average of different fault weights)
            // Equivocation=0.9, TimingAttack=0.6, SignatureForgery=1.0 -> avg ~0.83
            assertThat(result.compositeScore()).isGreaterThan(0.8);
            assertThat(result.isCritical()).isTrue();
        } finally {
            fireflies.restore();
            thoth.restore();
            gorgoneion.restore();
        }
    }

    @Test
    void shouldWeightLayersByContribution() {
        var member = createMember("weighted-byzantine");

        // Inject different severity faults
        var minor = harness.injectFault(IntelligenceConfig.LAYER_FIREFLIES, member, FaultType.DELAY);  // Low weight
        var major = harness.injectFault(IntelligenceConfig.LAYER_GORGONEION, member, FaultType.SIGNATURE_FORGERY);  // High weight

        try {
            var result = evaluate(member);

            // Signature forgery should dominate
            var gorgoneionScore = result.layerScores().get(IntelligenceConfig.LAYER_GORGONEION);
            var firefliesScore = result.layerScores().get(IntelligenceConfig.LAYER_FIREFLIES);

            assertThat(gorgoneionScore).isGreaterThan(firefliesScore);
        } finally {
            minor.restore();
            major.restore();
        }
    }

    @Test
    void shouldDetectPartialLayerAnomaly() {
        var member = createMember("partial-byzantine");

        // Only inject on one layer
        try (var fault = harness.injectFault(IntelligenceConfig.LAYER_THOTH, member, FaultType.TIMING_ATTACK)) {
            var result = evaluate(member);

            // Should be detected but not maximum severity
            assertThat(result.isAnomalous()).isTrue();
            assertThat(result.compositeScore()).isBetween(0.4, 0.8);
            assertThat(result.layerScores()).containsOnlyKeys(IntelligenceConfig.LAYER_THOTH);
        }
    }

    @Test
    void shouldNotFalsePositiveFromSingleLayerNoise() {
        // This test verifies that transient single-layer anomalies
        // don't trigger cross-layer alerts when other layers are clean
        var member = createMember("noisy-but-honest");

        // Inject very low-weight fault on one layer
        try (var fault = harness.injectFault(IntelligenceConfig.LAYER_FIREFLIES, member, FaultType.DELAY)) {
            var result = evaluate(member);

            // Should be detected but with low score
            assertThat(result.compositeScore()).isLessThan(0.4);
            // Should not trigger critical response
            assertThat(result.isCritical()).isFalse();
        }
    }

    @Test
    void shouldTrackLayerContributionsInMetrics() {
        var member = createMember("tracked-byzantine");

        // Inject on two layers
        var f1 = harness.injectFault(IntelligenceConfig.LAYER_FIREFLIES, member, FaultType.EQUIVOCATION);
        var f2 = harness.injectFault(IntelligenceConfig.LAYER_THOTH, member, FaultType.TIMING_ATTACK);

        try {
            evaluate(member);

            var metrics = getMetrics();
            var contributions = metrics.getLayerContributions();

            assertThat(contributions).containsKeys(
                IntelligenceConfig.LAYER_FIREFLIES,
                IntelligenceConfig.LAYER_THOTH
            );

            // Contributions should sum to approximately 1.0
            var total = contributions.values().stream().mapToDouble(d -> d).sum();
            assertThat(total).isCloseTo(1.0, within(0.01));
        } finally {
            f1.restore();
            f2.restore();
        }
    }

    @Test
    void shouldDistinguishByzantineFromNetworkPartition() {
        // Network partition affects connectivity but not signatures
        // Byzantine affects both
        var partitioned = createMember("partitioned-node");
        var byzantine = createMember("byzantine-node");

        // Partitioned node only has delay (network issue)
        var p1 = harness.injectFault(IntelligenceConfig.LAYER_FIREFLIES, partitioned, FaultType.DELAY);

        // Byzantine has multiple fault types
        var b1 = harness.injectFault(IntelligenceConfig.LAYER_FIREFLIES, byzantine, FaultType.EQUIVOCATION);
        var b2 = harness.injectFault(IntelligenceConfig.LAYER_GORGONEION, byzantine, FaultType.SIGNATURE_FORGERY);

        try {
            var partitionResult = evaluate(partitioned);
            var byzantineResult = evaluate(byzantine);

            // Byzantine should score much higher
            assertThat(byzantineResult.compositeScore())
                .isGreaterThan(partitionResult.compositeScore());

            // Byzantine should be critical, partitioned should not
            assertThat(byzantineResult.isCritical()).isTrue();
            assertThat(partitionResult.isCritical()).isFalse();
        } finally {
            p1.restore();
            b1.restore();
            b2.restore();
        }
    }

    @Test
    void shouldCorrelateProgressively() {
        var member = createMember("progressive-byzantine");
        var handles = new java.util.ArrayList<com.hellblazer.delos.membership.byzantine.testing.FaultInjectionHandle>();

        // Start with one layer (delay = 0.2 weight)
        handles.add(harness.injectFault(IntelligenceConfig.LAYER_FIREFLIES, member, FaultType.DELAY));
        var result1 = evaluate(member);

        // Add second layer (timing = 0.6 weight) -> avg with delay
        handles.add(harness.injectFault(IntelligenceConfig.LAYER_THOTH, member, FaultType.TIMING_ATTACK));
        var result2 = evaluate(member);

        // Add third layer (signature forgery = 1.0 weight)
        handles.add(harness.injectFault(IntelligenceConfig.LAYER_GORGONEION, member, FaultType.SIGNATURE_FORGERY));
        var result3 = evaluate(member);

        try {
            // Score should increase with each layer (weighted averages)
            assertThat(result2.compositeScore()).isGreaterThan(result1.compositeScore());
            assertThat(result3.compositeScore()).isGreaterThan(result2.compositeScore());
            // Final score is weighted average: (0.2 + 0.6 + 1.0) / 3 = 0.6
            assertThat(result3.compositeScore()).isGreaterThan(0.5);
        } finally {
            handles.forEach(h -> h.restore());
        }
    }

    @Test
    void shouldHandleConcurrentMultiMemberAnomalies() {
        // Create multiple members with varying fault patterns
        var members = createMembers(5, "concurrent");

        // Inject different patterns
        var handles = new java.util.ArrayList<com.hellblazer.delos.membership.byzantine.testing.FaultInjectionHandle>();

        // Member 0: All layers with high-weight faults (should be critical)
        handles.add(harness.injectFault(IntelligenceConfig.LAYER_FIREFLIES, members.get(0), FaultType.EQUIVOCATION));  // 0.9
        handles.add(harness.injectFault(IntelligenceConfig.LAYER_THOTH, members.get(0), FaultType.EQUIVOCATION));       // 0.9
        handles.add(harness.injectFault(IntelligenceConfig.LAYER_GORGONEION, members.get(0), FaultType.SIGNATURE_FORGERY)); // 1.0

        // Member 1: Single layer with medium weight (should be warning but not critical)
        handles.add(harness.injectFault(IntelligenceConfig.LAYER_FIREFLIES, members.get(1), FaultType.EQUIVOCATION));  // 0.9

        // Members 2-4: No faults (honest)

        try {
            // Evaluate all
            var results = new java.util.HashMap<Integer, com.hellblazer.delos.membership.byzantine.testing.DetectionResult>();
            for (int i = 0; i < members.size(); i++) {
                results.put(i, evaluate(members.get(i)));
            }

            // Full Byzantine should be anomalous with high score
            assertThat(results.get(0).isAnomalous()).isTrue();
            assertThat(results.get(0).compositeScore()).isGreaterThan(0.9);

            // Single layer high-weight should be anomalous
            assertThat(results.get(1).isAnomalous()).isTrue();
            assertThat(results.get(1).compositeScore()).isGreaterThan(0.8);

            // Honest should be clean
            assertThat(results.get(2).isAnomalous()).isFalse();
            assertThat(results.get(3).isAnomalous()).isFalse();
            assertThat(results.get(4).isAnomalous()).isFalse();
        } finally {
            handles.forEach(h -> h.restore());
        }
    }
}
