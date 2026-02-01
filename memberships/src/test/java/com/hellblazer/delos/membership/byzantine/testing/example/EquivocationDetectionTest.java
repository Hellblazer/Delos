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

import static org.assertj.core.api.Assertions.*;

/**
 * Tests equivocation detection.
 * <p>
 * Equivocation is when a Byzantine node sends conflicting messages
 * to different nodes. This is one of the highest-severity Byzantine
 * behaviors and should be detected with high confidence.
 *
 * @author hal.hildebrand
 */
class EquivocationDetectionTest extends AbstractByzantineClusterTest {

    @Test
    void shouldDetectSingleEquivocation() {
        var member = createMember("equivocating-node");

        // Inject equivocation fault
        try (var fault = injectEquivocation(member)) {
            var result = evaluate(member);

            // Equivocation should be detected with high score
            assertThat(result.isAnomalous()).isTrue();
            assertThat(result.compositeScore()).isGreaterThan(0.8);
            assertThat(result.isCritical()).isTrue();
        }
    }

    @Test
    void shouldDetectEquivocationWithLayerCorrelation() {
        var member = createMember("multi-layer-equivocator");

        // Inject on multiple layers
        var handles = injectMultiLayer(member,
            java.util.List.of(IntelligenceConfig.LAYER_FIREFLIES, IntelligenceConfig.LAYER_THOTH),
            FaultType.EQUIVOCATION);

        try {
            var result = evaluate(member);

            // Multi-layer equivocation should be detected with high severity
            // (weighted average, equivocation = 0.9 weight on each layer)
            assertThat(result.isAnomalous()).isTrue();
            assertThat(result.compositeScore()).isGreaterThan(0.8);
            assertThat(result.layerScores()).hasSize(2);
        } finally {
            handles.forEach(h -> h.restore());
        }
    }

    @Test
    void shouldNotFlagHonestNode() {
        var honest = createMember("honest-node");

        // No fault injection
        var result = evaluate(honest);

        assertThat(result.isAnomalous()).isFalse();
        assertThat(result.compositeScore()).isEqualTo(0.0);
    }

    @Test
    void shouldRecoverAfterFaultRestoration() {
        var member = createMember("recovering-node");

        // Inject and verify detection
        var fault = injectEquivocation(member);
        assertAnomalous(member);

        fault.restore();

        // After restoration, new evaluation should show clean state
        // (since we're re-evaluating and the fault injector no longer has the fault)
        harness.reset();  // Clear cached profiles
        var result = evaluate(member);
        assertThat(result.isAnomalous()).isFalse();
    }

    @Test
    void shouldTrackMetricsCorrectly() {
        var byzantine = createMember("byzantine");
        var honest = createMember("honest");

        // Inject fault for byzantine node
        try (var fault = injectEquivocation(byzantine)) {
            // Evaluate both
            evaluate(byzantine);  // Should be detected (true positive)
            evaluate(honest);     // Should not be detected (true negative)

            var metrics = getMetrics();
            assertThat(metrics.getTruePositives()).isEqualTo(1);
            assertThat(metrics.getTrueNegatives()).isGreaterThanOrEqualTo(0);
            assertThat(metrics.getFalsePositives()).isEqualTo(0);
            assertThat(metrics.getFalseNegatives()).isEqualTo(0);
        }
    }

    @Test
    void shouldTriggerCriticalResponse() {
        var member = createMember("critical-node");

        try (var fault = injectEquivocation(member)) {
            poll();  // Run polling cycle

            // Check response handler was triggered
            var handler = getResponseHandler();
            assertThat(handler.hadCriticalResponse(member)).isTrue();
        }
    }

    @Test
    void shouldDistinguishBetweenEquivocatorsAndHonest() {
        var nodes = createMembers(10, "node");
        var byzantineIndices = java.util.List.of(2, 5, 7);

        // Inject faults for specific nodes
        var handles = new java.util.ArrayList<com.hellblazer.delos.membership.byzantine.testing.FaultInjectionHandle>();
        for (int idx : byzantineIndices) {
            handles.add(injectEquivocation(nodes.get(idx)));
        }

        try {
            // Evaluate all nodes
            for (int i = 0; i < nodes.size(); i++) {
                var result = evaluate(nodes.get(i));
                if (byzantineIndices.contains(i)) {
                    assertThat(result.isAnomalous())
                        .as("Node %d should be detected as Byzantine", i)
                        .isTrue();
                } else {
                    assertThat(result.isAnomalous())
                        .as("Node %d should be detected as honest", i)
                        .isFalse();
                }
            }

            // Verify metrics
            var metrics = getMetrics();
            assertThat(metrics.getTruePositiveRate()).isEqualTo(1.0);
            assertThat(metrics.getFalsePositiveRate()).isEqualTo(0.0);
        } finally {
            handles.forEach(h -> h.restore());
        }
    }
}
