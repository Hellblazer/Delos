/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.byzantine.testing;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.byzantine.IntelligenceConfig;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for ByzantineMetricsCollector.
 */
class ByzantineMetricsCollectorTest {

    private ByzantineMetricsCollector metrics;

    @BeforeEach
    void setUp() {
        metrics = new ByzantineMetricsCollector();
    }

    @Test
    void shouldStartWithZeroMetrics() {
        assertThat(metrics.getTotalInjections()).isEqualTo(0);
        assertThat(metrics.getTruePositives()).isEqualTo(0);
        assertThat(metrics.getFalsePositives()).isEqualTo(0);
        assertThat(metrics.getFalseNegatives()).isEqualTo(0);
        assertThat(metrics.getTrueNegatives()).isEqualTo(0);
    }

    @Test
    void shouldRecordInjection() {
        var member = createMember("injected");

        metrics.recordInjection(member, FaultType.EQUIVOCATION);

        assertThat(metrics.getTotalInjections()).isEqualTo(1);
    }

    @Test
    void shouldRecordTruePositive() {
        var member = createMember("true-positive");

        metrics.recordInjection(member, FaultType.EQUIVOCATION);
        metrics.recordDetection(member, createDetectionResult(true, 0.9));

        assertThat(metrics.getTruePositives()).isEqualTo(1);
        assertThat(metrics.getFalseNegatives()).isEqualTo(0);
    }

    @Test
    void shouldRecordFalseNegative() {
        var member = createMember("false-negative");

        metrics.recordInjection(member, FaultType.CRASH);
        metrics.recordDetection(member, createDetectionResult(false, 0.2));

        assertThat(metrics.getFalseNegatives()).isEqualTo(1);
        assertThat(metrics.getTruePositives()).isEqualTo(0);
    }

    @Test
    void shouldRecordFalsePositive() {
        var member = createMember("false-positive");

        // No injection - member is honest
        metrics.recordNonInjectedDetection(member, createDetectionResult(true, 0.8));

        assertThat(metrics.getFalsePositives()).isEqualTo(1);
    }

    @Test
    void shouldRecordTrueNegative() {
        var member = createMember("true-negative");

        // No injection and not detected
        metrics.recordNonInjectedDetection(member, createDetectionResult(false, 0.1));

        assertThat(metrics.getTrueNegatives()).isEqualTo(1);
    }

    @Test
    void shouldCalculateTruePositiveRate() {
        // 3 true positives, 1 false negative -> TPR = 3/4 = 0.75
        for (int i = 0; i < 3; i++) {
            var member = createMember("tp-" + i);
            metrics.recordInjection(member, FaultType.EQUIVOCATION);
            metrics.recordDetection(member, createDetectionResult(true, 0.9));
        }

        var fnMember = createMember("fn");
        metrics.recordInjection(fnMember, FaultType.DELAY);
        metrics.recordDetection(fnMember, createDetectionResult(false, 0.2));

        assertThat(metrics.getTruePositiveRate()).isEqualTo(0.75);
    }

    @Test
    void shouldCalculateFalsePositiveRate() {
        // 1 false positive, 4 true negatives -> FPR = 1/5 = 0.2
        var fpMember = createMember("fp");
        metrics.recordNonInjectedDetection(fpMember, createDetectionResult(true, 0.8));

        for (int i = 0; i < 4; i++) {
            var member = createMember("tn-" + i);
            metrics.recordNonInjectedDetection(member, createDetectionResult(false, 0.1));
        }

        assertThat(metrics.getFalsePositiveRate()).isCloseTo(0.2, within(0.01));
    }

    @Test
    void shouldCalculatePrecision() {
        // 4 true positives, 1 false positive -> Precision = 4/5 = 0.8
        for (int i = 0; i < 4; i++) {
            var member = createMember("tp-prec-" + i);
            metrics.recordInjection(member, FaultType.EQUIVOCATION);
            metrics.recordDetection(member, createDetectionResult(true, 0.9));
        }

        var fpMember = createMember("fp-prec");
        metrics.recordNonInjectedDetection(fpMember, createDetectionResult(true, 0.8));

        assertThat(metrics.getPrecision()).isEqualTo(0.8);
    }

    @Test
    void shouldCalculateF1Score() {
        // Set up perfect detection
        for (int i = 0; i < 10; i++) {
            var member = createMember("perfect-" + i);
            metrics.recordInjection(member, FaultType.EQUIVOCATION);
            metrics.recordDetection(member, createDetectionResult(true, 0.9));
        }

        // Perfect TPR and Precision = 1.0, F1 = 1.0
        assertThat(metrics.getF1Score()).isEqualTo(1.0);
    }

    @Test
    void shouldTrackDetectionLatency() throws InterruptedException {
        var member = createMember("latency-test");

        metrics.recordInjection(member, FaultType.CRASH);
        Thread.sleep(10);  // Small delay
        metrics.recordDetection(member, createDetectionResult(true, 0.8));

        assertThat(metrics.getMeanDetectionLatency()).isGreaterThan(Duration.ZERO);
        assertThat(metrics.getP95DetectionLatency()).isGreaterThan(Duration.ZERO);
    }

    @Test
    void shouldReturnZeroLatencyWhenEmpty() {
        assertThat(metrics.getMeanDetectionLatency()).isEqualTo(Duration.ZERO);
        assertThat(metrics.getP95DetectionLatency()).isEqualTo(Duration.ZERO);
    }

    @Test
    void shouldTrackDetectionRateByFaultType() {
        // Inject different fault types
        var eq1 = createMember("eq-1");
        var eq2 = createMember("eq-2");
        var crash = createMember("crash-1");

        metrics.recordInjection(eq1, FaultType.EQUIVOCATION);
        metrics.recordDetection(eq1, createDetectionResult(true, 0.9));

        metrics.recordInjection(eq2, FaultType.EQUIVOCATION);
        metrics.recordDetection(eq2, createDetectionResult(true, 0.9));

        metrics.recordInjection(crash, FaultType.CRASH);
        metrics.recordDetection(crash, createDetectionResult(false, 0.2));  // Missed

        var rates = metrics.getDetectionRateByFaultType();

        assertThat(rates.get(FaultType.EQUIVOCATION)).isEqualTo(1.0);  // 2/2
        assertThat(rates.get(FaultType.CRASH)).isEqualTo(0.0);  // 0/1
    }

    @Test
    void shouldTrackLayerContributions() {
        var member = createMember("layer-contrib");

        metrics.recordInjection(member, FaultType.EQUIVOCATION);

        var layerScores = Map.of(
            IntelligenceConfig.LAYER_FIREFLIES, 0.8,
            IntelligenceConfig.LAYER_THOTH, 0.3
        );
        var result = new DetectionResult(0.8, Instant.now(), layerScores, true, false);
        metrics.recordDetection(member, result);

        var contributions = metrics.getLayerContributions();
        assertThat(contributions).containsKeys(
            IntelligenceConfig.LAYER_FIREFLIES,
            IntelligenceConfig.LAYER_THOTH
        );
    }

    @Test
    void shouldGenerateSummary() {
        var member = createMember("summary-test");
        metrics.recordInjection(member, FaultType.EQUIVOCATION);
        metrics.recordDetection(member, createDetectionResult(true, 0.9));

        var summary = metrics.getSummary();

        assertThat(summary).contains("Byzantine Detection Metrics");
        assertThat(summary).contains("Total Injections: 1");
        assertThat(summary).contains("True Positives:   1");
    }

    @Test
    void shouldHandleZeroDenominatorGracefully() {
        // No injections - TPR should be 1.0 (no misses possible)
        assertThat(metrics.getTruePositiveRate()).isEqualTo(1.0);

        // No positives at all - precision should be 1.0
        assertThat(metrics.getPrecision()).isEqualTo(1.0);

        // No negatives - FPR should be 0.0
        assertThat(metrics.getFalsePositiveRate()).isEqualTo(0.0);
    }

    private SelfAddressingIdentifier createMember(String seed) {
        return new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest(seed));
    }

    private DetectionResult createDetectionResult(boolean anomalous, double score) {
        return new DetectionResult(
            score,
            Instant.now(),
            Map.of(IntelligenceConfig.LAYER_FIREFLIES, score),
            anomalous,
            score >= 0.8
        );
    }
}
