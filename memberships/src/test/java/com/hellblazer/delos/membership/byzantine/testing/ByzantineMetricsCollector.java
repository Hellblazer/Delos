/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.byzantine.testing;

import com.hellblazer.delos.stereotomy.identifier.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Collects metrics during Byzantine fault testing.
 * <p>
 * Tracks:
 * <ul>
 *   <li>Detection rates (true positives, false positives, false negatives)</li>
 *   <li>Detection latency</li>
 *   <li>Layer contribution to detections</li>
 *   <li>Fault type detection accuracy</li>
 * </ul>
 * <p>
 * Usage:
 * <pre>{@code
 * var metrics = new ByzantineMetricsCollector();
 * metrics.recordInjection(memberId, FaultType.EQUIVOCATION);
 *
 * // ... run detection cycle ...
 *
 * var result = coordinator.evaluateMember(memberId);
 * metrics.recordDetection(memberId, result);
 *
 * // Validate metrics
 * assertThat(metrics.getTruePositiveRate()).isGreaterThan(0.95);
 * }</pre>
 *
 * @author hal.hildebrand
 */
public class ByzantineMetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(ByzantineMetricsCollector.class);

    // Injection tracking
    private final Map<Identifier, FaultInjection> injectedFaults;
    private final LongAdder totalInjections;

    // Detection tracking
    private final Map<Identifier, DetectionEvent> detections;
    private final LongAdder truePositives;
    private final LongAdder falsePositives;
    private final LongAdder falseNegatives;
    private final LongAdder trueNegatives;

    // Latency tracking
    private final List<Duration> detectionLatencies;

    // Per-layer tracking
    private final Map<String, AtomicInteger> layerContributions;

    // Per-fault-type tracking
    private final Map<FaultType, DetectionStats> faultTypeStats;

    /**
     * Creates a new metrics collector.
     */
    public ByzantineMetricsCollector() {
        this.injectedFaults = new ConcurrentHashMap<>();
        this.totalInjections = new LongAdder();
        this.detections = new ConcurrentHashMap<>();
        this.truePositives = new LongAdder();
        this.falsePositives = new LongAdder();
        this.falseNegatives = new LongAdder();
        this.trueNegatives = new LongAdder();
        this.detectionLatencies = Collections.synchronizedList(new ArrayList<>());
        this.layerContributions = new ConcurrentHashMap<>();
        this.faultTypeStats = new ConcurrentHashMap<>();
    }

    // ========== Recording Methods ==========

    /**
     * Records a fault injection.
     *
     * @param memberId  the member fault was injected for
     * @param faultType the type of fault injected
     */
    public void recordInjection(Identifier memberId, FaultType faultType) {
        var injection = new FaultInjection(faultType, Instant.now());
        injectedFaults.put(memberId, injection);
        totalInjections.increment();
        faultTypeStats.computeIfAbsent(faultType, k -> new DetectionStats()).injected.increment();
        log.debug("Recorded injection: {} for {}", faultType, memberId);
    }

    /**
     * Records a detection result.
     *
     * @param memberId the member evaluated
     * @param result   the detection result
     */
    public void recordDetection(Identifier memberId, DetectionResult result) {
        var injection = injectedFaults.get(memberId);
        var event = new DetectionEvent(result, Instant.now());
        detections.put(memberId, event);

        if (injection != null) {
            // There was a fault injected
            if (result.isAnomalous()) {
                // Detected! True positive
                truePositives.increment();
                var latency = Duration.between(injection.injectedAt, event.detectedAt);
                detectionLatencies.add(latency);
                faultTypeStats.computeIfAbsent(injection.type, k -> new DetectionStats()).detected.increment();

                // Track layer contributions
                for (var layer : result.layerScores().keySet()) {
                    layerContributions.computeIfAbsent(layer, k -> new AtomicInteger()).incrementAndGet();
                }

                log.debug("True positive: {} detected {} in {}", memberId, injection.type, latency);
            } else {
                // Not detected! False negative
                falseNegatives.increment();
                faultTypeStats.computeIfAbsent(injection.type, k -> new DetectionStats()).missed.increment();
                log.debug("False negative: {} missed {}", memberId, injection.type);
            }
        } else {
            // No fault was injected
            if (result.isAnomalous()) {
                // False positive
                falsePositives.increment();
                log.debug("False positive: {} incorrectly flagged", memberId);
            } else {
                // Correctly identified as normal
                trueNegatives.increment();
            }
        }
    }

    /**
     * Records a detection for a member without an injection (for false positive testing).
     *
     * @param memberId the member
     * @param result   the detection result
     */
    public void recordNonInjectedDetection(Identifier memberId, DetectionResult result) {
        if (result.isAnomalous()) {
            falsePositives.increment();
            log.debug("False positive: {} incorrectly flagged", memberId);
        } else {
            trueNegatives.increment();
        }
    }

    // ========== Metrics Retrieval ==========

    /**
     * Gets the true positive rate (sensitivity/recall).
     * TPR = TP / (TP + FN)
     *
     * @return true positive rate (0.0 to 1.0)
     */
    public double getTruePositiveRate() {
        long tp = truePositives.sum();
        long fn = falseNegatives.sum();
        if (tp + fn == 0) return 1.0;
        return (double) tp / (tp + fn);
    }

    /**
     * Gets the false positive rate.
     * FPR = FP / (FP + TN)
     *
     * @return false positive rate (0.0 to 1.0)
     */
    public double getFalsePositiveRate() {
        long fp = falsePositives.sum();
        long tn = trueNegatives.sum();
        if (fp + tn == 0) return 0.0;
        return (double) fp / (fp + tn);
    }

    /**
     * Gets the precision.
     * Precision = TP / (TP + FP)
     *
     * @return precision (0.0 to 1.0)
     */
    public double getPrecision() {
        long tp = truePositives.sum();
        long fp = falsePositives.sum();
        if (tp + fp == 0) return 1.0;
        return (double) tp / (tp + fp);
    }

    /**
     * Gets the F1 score.
     * F1 = 2 * (precision * recall) / (precision + recall)
     *
     * @return F1 score (0.0 to 1.0)
     */
    public double getF1Score() {
        var precision = getPrecision();
        var recall = getTruePositiveRate();
        if (precision + recall == 0) return 0.0;
        return 2 * (precision * recall) / (precision + recall);
    }

    /**
     * Gets the mean detection latency.
     *
     * @return mean latency or Duration.ZERO if no detections
     */
    public Duration getMeanDetectionLatency() {
        synchronized (detectionLatencies) {
            if (detectionLatencies.isEmpty()) return Duration.ZERO;
            var totalMillis = detectionLatencies.stream()
                                                 .mapToLong(Duration::toMillis)
                                                 .sum();
            return Duration.ofMillis(totalMillis / detectionLatencies.size());
        }
    }

    /**
     * Gets the P95 detection latency.
     *
     * @return P95 latency
     */
    public Duration getP95DetectionLatency() {
        synchronized (detectionLatencies) {
            if (detectionLatencies.isEmpty()) return Duration.ZERO;
            var sorted = detectionLatencies.stream()
                                            .sorted()
                                            .toList();
            var p95Index = (int) Math.ceil(sorted.size() * 0.95) - 1;
            return sorted.get(Math.max(0, p95Index));
        }
    }

    /**
     * Gets detection rate per fault type.
     *
     * @return map of fault type to detection rate
     */
    public Map<FaultType, Double> getDetectionRateByFaultType() {
        var result = new EnumMap<FaultType, Double>(FaultType.class);
        for (var entry : faultTypeStats.entrySet()) {
            var stats = entry.getValue();
            long injected = stats.injected.sum();
            long detected = stats.detected.sum();
            result.put(entry.getKey(), injected == 0 ? 0.0 : (double) detected / injected);
        }
        return result;
    }

    /**
     * Gets contribution percentage per layer.
     *
     * @return map of layer name to contribution percentage
     */
    public Map<String, Double> getLayerContributions() {
        var total = layerContributions.values().stream()
                                       .mapToInt(AtomicInteger::get)
                                       .sum();
        if (total == 0) return Map.of();

        var result = new HashMap<String, Double>();
        for (var entry : layerContributions.entrySet()) {
            result.put(entry.getKey(), (double) entry.getValue().get() / total);
        }
        return result;
    }

    /**
     * Gets total count of true positives.
     *
     * @return true positive count
     */
    public long getTruePositives() {
        return truePositives.sum();
    }

    /**
     * Gets total count of false positives.
     *
     * @return false positive count
     */
    public long getFalsePositives() {
        return falsePositives.sum();
    }

    /**
     * Gets total count of false negatives.
     *
     * @return false negative count
     */
    public long getFalseNegatives() {
        return falseNegatives.sum();
    }

    /**
     * Gets total count of true negatives.
     *
     * @return true negative count
     */
    public long getTrueNegatives() {
        return trueNegatives.sum();
    }

    /**
     * Gets total injection count.
     *
     * @return total injections
     */
    public long getTotalInjections() {
        return totalInjections.sum();
    }

    /**
     * Generates a summary report.
     *
     * @return formatted summary string
     */
    public String getSummary() {
        return String.format("""
            Byzantine Detection Metrics
            ===========================
            Total Injections: %d
            True Positives:   %d
            False Positives:  %d
            False Negatives:  %d
            True Negatives:   %d

            Detection Rate (TPR): %.2f%%
            False Positive Rate:  %.2f%%
            Precision:            %.2f%%
            F1 Score:             %.2f

            Mean Detection Latency: %s
            P95 Detection Latency:  %s
            """,
            totalInjections.sum(),
            truePositives.sum(),
            falsePositives.sum(),
            falseNegatives.sum(),
            trueNegatives.sum(),
            getTruePositiveRate() * 100,
            getFalsePositiveRate() * 100,
            getPrecision() * 100,
            getF1Score(),
            getMeanDetectionLatency(),
            getP95DetectionLatency());
    }

    /**
     * Resets all metrics.
     */
    public void reset() {
        injectedFaults.clear();
        detections.clear();
        detectionLatencies.clear();
        layerContributions.clear();
        faultTypeStats.clear();
        // Note: LongAdder doesn't have reset, so we create new collector for clean slate
    }

    // ========== Internal Classes ==========

    private record FaultInjection(FaultType type, Instant injectedAt) {}

    private record DetectionEvent(DetectionResult result, Instant detectedAt) {}

    private static class DetectionStats {
        final LongAdder injected = new LongAdder();
        final LongAdder detected = new LongAdder();
        final LongAdder missed = new LongAdder();
    }
}
