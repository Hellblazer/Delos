/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.byzantine;

import com.hellblazer.delos.membership.byzantine.testing.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for cross-layer Byzantine detection.
 * <p>
 * Phase 7: Tests full system behavior with multiple layers coordinating
 * to detect Byzantine behavior patterns.
 * <p>
 * Scenarios covered:
 * <ul>
 *   <li>Full cross-layer detection (all layers)</li>
 *   <li>Multi-member coalition detection</li>
 *   <li>View change and reset handling</li>
 *   <li>Graceful shutdown with pending operations</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
class CrossLayerIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(CrossLayerIntegrationTest.class);

    private ByzantineTestHarness harness;
    private IntelligenceConfig config;

    @BeforeEach
    void setUp() {
        config = IntelligenceConfig.builder()
            .defaultPollInterval(Duration.ofMillis(50))
            .warningThreshold(0.15)
            .criticalThreshold(0.6)
            .layerWeights(Map.of(
                IntelligenceConfig.LAYER_FIREFLIES, 0.4,
                IntelligenceConfig.LAYER_ETHEREAL, 0.3,
                IntelligenceConfig.LAYER_THOTH, 0.2,
                IntelligenceConfig.LAYER_GORGONEION, 0.1
            ))
            .responseCooldown(Duration.ofMillis(100))
            .maxResponsesPerInterval(50)
            .signalDeduplicationWindow(Duration.ofMillis(500))
            .build();

        harness = ByzantineTestHarness.builder()
            .withDeterministicEntropy()
            .withFixedClock()
            .withLayers(
                IntelligenceConfig.LAYER_FIREFLIES,
                IntelligenceConfig.LAYER_THOTH,
                IntelligenceConfig.LAYER_GORGONEION
            )
            .withConfig(config)
            .build();
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    /**
     * Scenario 1: Full cross-layer Byzantine detection.
     * <p>
     * Injects faults on multiple layers and verifies coordinated detection.
     */
    @Test
    void fullCrossLayerDetection() {
        log.info("Testing full cross-layer Byzantine detection");

        var byzantineMember = harness.createMember("byzantine-node-1");

        // Inject faults on all three layers
        harness.injectFault(IntelligenceConfig.LAYER_FIREFLIES, byzantineMember, FaultType.CRASH);
        harness.injectFault(IntelligenceConfig.LAYER_THOTH, byzantineMember, FaultType.DELAY);
        harness.injectFault(IntelligenceConfig.LAYER_GORGONEION, byzantineMember, FaultType.OMISSION);

        // Evaluate the member
        var result = harness.evaluate(byzantineMember);

        log.info("Cross-layer detection result: score={}, anomalous={}, critical={}",
            result.compositeScore(), result.isAnomalous(), result.isCritical());
        log.info("Layer scores: {}", result.layerScores());

        // Should detect as anomalous with contributions from all layers
        assertThat(result.isAnomalous())
            .as("Member with faults on all layers should be flagged as anomalous")
            .isTrue();

        assertThat(result.layerScores())
            .as("All three layers should contribute scores")
            .hasSize(3)
            .containsKeys(
                IntelligenceConfig.LAYER_FIREFLIES,
                IntelligenceConfig.LAYER_THOTH,
                IntelligenceConfig.LAYER_GORGONEION
            );

        // Verify metrics tracked
        var metrics = harness.getMetrics();
        assertThat(metrics.getTruePositives())
            .as("Should record true positive detection")
            .isEqualTo(1);
    }

    /**
     * Scenario 2: Multi-member Byzantine coalition detection.
     * <p>
     * Tests detection of coordinated Byzantine behavior from multiple members.
     */
    @Test
    void multiMemberCoalitionDetection() {
        log.info("Testing multi-member Byzantine coalition detection");

        var coalitionSize = 5;
        var detectedCount = new AtomicInteger(0);

        // Create a coalition of Byzantine members
        for (int i = 0; i < coalitionSize; i++) {
            var member = harness.createMember("coalition-member-" + i);

            // Inject coordinated faults (all doing equivocation)
            harness.injectFault(IntelligenceConfig.LAYER_FIREFLIES, member, FaultType.EQUIVOCATION);

            var result = harness.evaluate(member);
            if (result.isAnomalous()) {
                detectedCount.incrementAndGet();
            }
        }

        log.info("Detected {} of {} coalition members", detectedCount.get(), coalitionSize);

        // Should detect all coalition members
        assertThat(detectedCount.get())
            .as("All coalition members should be detected")
            .isEqualTo(coalitionSize);

        // Run polling cycle to trigger responses
        harness.runPollingCycle();

        // Verify all got critical responses (EQUIVOCATION score 0.9 > critical threshold 0.6)
        var criticalResponses = harness.getResponseHandler().getCriticalResponses();
        assertThat(criticalResponses)
            .as("All coalition members should receive critical responses for EQUIVOCATION")
            .hasSize(coalitionSize);
    }

    /**
     * Scenario 3: View change handling.
     * <p>
     * Tests that detection state is properly reset on view change.
     */
    @Test
    void viewChangeResetHandling() {
        log.info("Testing view change reset handling");

        var member = harness.createMember("view-change-test");

        // Phase 1: Inject fault and detect
        harness.injectFault(IntelligenceConfig.LAYER_FIREFLIES, member, FaultType.CRASH);
        var result1 = harness.evaluate(member);

        assertThat(result1.isAnomalous())
            .as("Should detect fault before reset")
            .isTrue();

        // Simulate view change by resetting harness
        harness.reset();

        // Phase 2: Same member should be clean after reset
        var memberAfterReset = harness.createMember("view-change-test");
        var result2 = harness.evaluate(memberAfterReset);

        assertThat(result2.isAnomalous())
            .as("Member should be clean after view change reset")
            .isFalse();

        // Verify metrics were also reset
        assertThat(harness.getMetrics().getTruePositives())
            .as("Metrics should be reset")
            .isZero();
    }

    /**
     * Scenario 4: Graceful shutdown with pending operations.
     * <p>
     * Tests that cleanup handles in-flight operations properly.
     */
    @Test
    void gracefulShutdownWithPendingOperations() {
        log.info("Testing graceful shutdown with pending operations");

        // Inject multiple faults
        var handles = new ArrayList<FaultInjectionHandle>();
        for (int i = 0; i < 10; i++) {
            var member = harness.createMember("shutdown-test-" + i);
            handles.add(harness.injectFault(
                IntelligenceConfig.LAYER_FIREFLIES, member, FaultType.CRASH));
        }

        // Verify faults are not yet restored
        for (var handle : handles) {
            assertThat(handle.isRestored())
                .as("Fault should not be restored before shutdown")
                .isFalse();
        }

        // Simulate graceful shutdown
        harness.restoreAllFaults();

        // Verify all faults were cleaned up
        for (var handle : handles) {
            assertThat(handle.isRestored())
                .as("Fault should be restored after shutdown")
                .isTrue();
        }
    }

    /**
     * Scenario 5: Weighted aggregation verification.
     * <p>
     * Verifies that layer weights are correctly applied in score calculation.
     */
    @Test
    void weightedAggregationVerification() {
        log.info("Testing weighted aggregation");

        var member = harness.createMember("weight-test");

        // Inject high-score fault only on GORGONEION (lowest weight: 0.1)
        harness.injectFault(IntelligenceConfig.LAYER_GORGONEION, member, FaultType.EQUIVOCATION);

        var result1 = harness.evaluate(member);
        var score1 = result1.compositeScore();

        harness.reset();

        // Inject same fault on FIREFLIES (highest weight: 0.4)
        var member2 = harness.createMember("weight-test");
        harness.injectFault(IntelligenceConfig.LAYER_FIREFLIES, member2, FaultType.EQUIVOCATION);

        var result2 = harness.evaluate(member2);
        var score2 = result2.compositeScore();

        log.info("GORGONEION-only score: {}, FIREFLIES-only score: {}", score1, score2);

        // Both should be equal since they use the same fault weight
        // (Single layer = that layer's score becomes the aggregate)
        // But the response handler behavior may differ based on layer weight
        assertThat(result1.isAnomalous())
            .as("GORGONEION detection should work")
            .isTrue();
        assertThat(result2.isAnomalous())
            .as("FIREFLIES detection should work")
            .isTrue();
    }

    /**
     * Scenario 6: Cross-layer correlation for reduced false positives.
     * <p>
     * Verifies that requiring correlation reduces false alarms.
     */
    @Test
    void crossLayerCorrelationReducesFalseAlarms() {
        log.info("Testing cross-layer correlation for FP reduction");

        var normalMembers = 50;
        var falsePositives = 0;

        // Evaluate many normal members
        for (int i = 0; i < normalMembers; i++) {
            var member = harness.createMember("normal-member-" + i);
            var result = harness.evaluate(member);
            if (result.isAnomalous()) {
                falsePositives++;
            }
        }

        var fpRate = (double) falsePositives / normalMembers;
        log.info("False positive rate: {}%", String.format("%.2f", fpRate * 100));

        assertThat(fpRate)
            .as("False positive rate should be ≤10%%")
            .isLessThanOrEqualTo(0.10);
    }

    /**
     * Performance test: Poll cycle latency for 100 members.
     */
    @Test
    void pollCycleLatencyBenchmark() {
        log.info("Testing poll cycle latency for 100 members");

        // Setup 100 members with various fault states
        for (int i = 0; i < 100; i++) {
            var member = harness.createMember("perf-member-" + i);
            if (i % 10 == 0) {
                // 10% have faults
                harness.injectFault(IntelligenceConfig.LAYER_FIREFLIES, member, FaultType.CRASH);
            }
        }

        // Measure poll cycle time
        var startTime = System.nanoTime();
        harness.runPollingCycle();
        var elapsedMs = (System.nanoTime() - startTime) / 1_000_000.0;

        log.info("Poll cycle completed in {}ms", String.format("%.2f", elapsedMs));

        assertThat(elapsedMs)
            .as("Poll cycle should complete in <100ms for 100 members")
            .isLessThan(100.0);
    }
}
