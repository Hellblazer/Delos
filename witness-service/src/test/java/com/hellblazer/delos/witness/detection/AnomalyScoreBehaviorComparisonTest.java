/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import com.hellblazer.delos.stereotomy.identifier.Identifier;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compares old (dynamic alpha) vs new (constant alpha) EMA behavior.
 * <p>
 * Phase 0 Fix 5: Quantify behavioral difference to ensure no functional regression.
 * The O(1) fix changes from dynamic alpha (2.0/(history.size()+1)) to constant alpha (0.1).
 * </p>
 * <p>
 * Correction 3: avgDiff is mean absolute difference: (1/N) * Σ|old[i] - new[i]|
 * </p>
 *
 * @author hal.hildebrand
 */
class AnomalyScoreBehaviorComparisonTest {

    private static final Logger log = LoggerFactory.getLogger(AnomalyScoreBehaviorComparisonTest.class);

    /**
     * Simulates old EMA behavior with dynamic alpha.
     * <p>
     * Original algorithm: alpha = 2.0 / (history.size() + 1)
     * Recalculates from scratch on every event (O(N)).
     * </p>
     */
    private double[] simulateOldEma(double[] signals) {
        var results = new double[signals.length];
        var history = new java.util.ArrayList<Double>();

        for (int i = 0; i < signals.length; i++) {
            history.add(signals[i]);

            // Dynamic alpha based on current history size
            double alpha = 2.0 / (history.size() + 1);
            double ema = 0.0;

            // Full recalculation (O(N))
            for (var contribution : history) {
                ema = alpha * contribution + (1 - alpha) * ema;
            }

            results[i] = Math.max(0.0, ema);
        }

        return results;
    }

    /**
     * Simulates new EMA behavior with constant alpha.
     * <p>
     * New algorithm: ema = alpha * contribution + (1 - alpha) * ema
     * Incremental update (O(1)).
     * </p>
     */
    private double[] simulateNewEma(double[] signals, double alpha) {
        var results = new double[signals.length];
        double ema = 0.0;

        for (int i = 0; i < signals.length; i++) {
            ema = alpha * signals[i] + (1 - alpha) * ema;
            results[i] = Math.max(0.0, ema);
        }

        return results;
    }

    /**
     * Computes mean absolute difference: (1/N) * Σ|old[i] - new[i]|
     * <p>
     * Correction 3: Explicit documentation of avgDiff calculation.
     * </p>
     */
    private double computeMeanAbsoluteDifference(double[] oldResults, double[] newResults) {
        if (oldResults.length != newResults.length) {
            throw new IllegalArgumentException("Arrays must have same length");
        }
        double sum = 0.0;
        for (int i = 0; i < oldResults.length; i++) {
            sum += Math.abs(oldResults[i] - newResults[i]);
        }
        return sum / oldResults.length;
    }

    /**
     * Computes maximum absolute difference.
     */
    private double computeMaxDifference(double[] oldResults, double[] newResults) {
        double max = 0.0;
        for (int i = 0; i < oldResults.length; i++) {
            max = Math.max(max, Math.abs(oldResults[i] - newResults[i]));
        }
        return max;
    }

    /**
     * Computes Pearson correlation coefficient.
     */
    private double computeCorrelation(double[] x, double[] y) {
        int n = x.length;
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0, sumY2 = 0;

        for (int i = 0; i < n; i++) {
            sumX += x[i];
            sumY += y[i];
            sumXY += x[i] * y[i];
            sumX2 += x[i] * x[i];
            sumY2 += y[i] * y[i];
        }

        double numerator = n * sumXY - sumX * sumY;
        double denominator = Math.sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY));

        return denominator == 0 ? 0 : numerator / denominator;
    }

    /**
     * Generate test signals with realistic Byzantine detection patterns.
     */
    private double[] generateTestSignals(int count, long seed) {
        var random = new Random(seed);
        var signals = new double[count];

        for (int i = 0; i < count; i++) {
            // Mix of normal noise, spikes, and sustained anomalies
            if (random.nextDouble() < 0.05) {
                // 5% chance of spike
                signals[i] = 0.5 + random.nextDouble() * 0.5;
            } else if (random.nextDouble() < 0.1) {
                // 10% chance of recovery
                signals[i] = -0.1 * random.nextDouble();
            } else {
                // Normal noise
                signals[i] = random.nextDouble() * 0.2;
            }
        }

        return signals;
    }

    @Test
    void compareOldVsNewEmaBehavior() {
        var testSequence = generateTestSignals(1000, 42L);

        // Old behavior (dynamic alpha)
        var oldResults = simulateOldEma(testSequence);

        // New behavior (constant alpha = 0.1)
        var newResults = simulateNewEma(testSequence, 0.1);

        // Compute differences
        var maxDiff = computeMaxDifference(oldResults, newResults);
        var avgDiff = computeMeanAbsoluteDifference(oldResults, newResults);
        var correlation = computeCorrelation(oldResults, newResults);

        log.info("EMA Behavior Comparison (1000 events):");
        log.info("  Max difference: {}", String.format("%.6f", maxDiff));
        log.info("  Avg difference (mean absolute): {}", String.format("%.6f", avgDiff));
        log.info("  Correlation: {}", String.format("%.6f", correlation));

        // Correction 3: avgDiff (mean absolute difference) < 0.1
        assertThat(avgDiff)
            .as("Mean absolute difference between old and new EMA must be < 0.1")
            .isLessThan(0.1);

        // Additional sanity checks - correlation should be positive (not necessarily strong)
        // The algorithms differ (dynamic vs constant alpha) so perfect correlation not expected
        assertThat(correlation)
            .as("Correlation should be positive (both respond to same signals)")
            .isGreaterThan(0.0);
    }

    @Test
    void verifyNewEmaBehaviorIsConsistent() {
        var score = new AnomalyScore(Identifier.NONE, 1000, 0.1);

        // Record a known sequence
        score.recordEvent(0.5, "Event 1");
        score.recordEvent(0.3, "Event 2");
        score.recordEvent(0.8, "Event 3");

        // Expected: ema = 0.1 * 0.8 + 0.9 * (0.1 * 0.3 + 0.9 * (0.1 * 0.5))
        // = 0.08 + 0.9 * (0.03 + 0.9 * 0.05)
        // = 0.08 + 0.9 * (0.03 + 0.045)
        // = 0.08 + 0.9 * 0.075
        // = 0.08 + 0.0675
        // = 0.1475
        var expectedScore = 0.1 * 0.8 + 0.9 * (0.1 * 0.3 + 0.9 * (0.1 * 0.5));

        assertThat(score.getScore())
            .as("Score should match expected EMA calculation")
            .isCloseTo(expectedScore, org.assertj.core.api.Assertions.within(0.0001));
    }

    @Test
    void verifyAlphaConfigurability() {
        // Test with different alpha values
        var lowAlpha = new AnomalyScore(Identifier.NONE, 100, 0.05);
        var highAlpha = new AnomalyScore(Identifier.NONE, 100, 0.3);

        // Record same sequence
        for (int i = 0; i < 10; i++) {
            lowAlpha.recordEvent(0.5, "Event");
            highAlpha.recordEvent(0.5, "Event");
        }

        // Higher alpha should converge faster to signal value
        assertThat(highAlpha.getScore())
            .as("Higher alpha should have faster convergence")
            .isGreaterThan(lowAlpha.getScore());
    }

    @Test
    void verifyDefaultAlphaIsPointOne() {
        // Using default constructor (without explicit alpha)
        var defaultScore = new AnomalyScore(Identifier.NONE, 100);
        var explicitScore = new AnomalyScore(Identifier.NONE, 100, 0.1);

        // Record same sequence
        defaultScore.recordEvent(0.5, "Event");
        explicitScore.recordEvent(0.5, "Event");

        assertThat(defaultScore.getScore())
            .as("Default alpha should be 0.1")
            .isEqualTo(explicitScore.getScore());
    }
}
