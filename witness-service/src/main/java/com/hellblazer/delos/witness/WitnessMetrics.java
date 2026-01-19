/*
 * Copyright (c) 2024, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.witness;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Timer;
import com.codahale.metrics.MetricRegistry;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dropwizard Metrics instrumentation for KERI witness network operations.
 * <p>
 * Provides 6 instrumented operations:
 * <ul>
 *   <li>receiptCollectionLatency - Time from event signing to threshold achieved (Histogram, ms)</li>
 *   <li>thresholdAchievementRate - Percentage of events reaching threshold (Gauge, 0-1)</li>
 *   <li>viewChangeCoordinationTime - Duration of view change drain period (Timer, ms)</li>
 *   <li>committeeSelectionTime - Latency of bftSubset() call (Timer, μs)</li>
 *   <li>inFlightCollections - Number of active receipt collections (Gauge, count)</li>
 *   <li>receiptGossipLatency - Time from threshold to propagation (Histogram, ms)</li>
 * </ul>
 * </p>
 */
public class WitnessMetrics {

    private final MetricRegistry registry;
    private final Histogram receiptCollectionLatency;
    private final Histogram receiptGossipLatency;
    private final Timer viewChangeCoordinationTime;
    private final Timer committeeSelectionTime;
    private final AtomicReference<Double> thresholdAchievementRate = new AtomicReference<>(0.0);
    private final AtomicInteger inFlightCollections = new AtomicInteger(0);

    /**
     * Create metrics with the provided registry.
     *
     * @param registry Dropwizard MetricRegistry
     */
    public WitnessMetrics(MetricRegistry registry) {
        this.registry = registry;

        // Register histograms
        this.receiptCollectionLatency = registry.histogram("witness.receipt.collection.latency");
        this.receiptGossipLatency = registry.histogram("witness.receipt.gossip.latency");

        // Register timers
        this.viewChangeCoordinationTime = registry.timer("witness.view.change.coordination.time");
        this.committeeSelectionTime = registry.timer("witness.committee.selection.time");

        // Register gauges
        registry.register("witness.threshold.achievement.rate",
            (Gauge<Double>) thresholdAchievementRate::get);
        registry.register("witness.in.flight.collections",
            (Gauge<Integer>) inFlightCollections::get);
    }

    /**
     * Get the underlying metric registry.
     */
    public MetricRegistry getRegistry() {
        return registry;
    }

    /**
     * Get histogram for receipt collection latency (ms).
     * <p>
     * Measures time from event signing to M-of-N threshold achieved.
     * </p>
     */
    public Histogram receiptCollectionLatency() {
        return receiptCollectionLatency;
    }

    /**
     * Get histogram for receipt gossip latency (ms).
     * <p>
     * Measures time from threshold achievement to cluster-wide propagation.
     * </p>
     */
    public Histogram receiptGossipLatency() {
        return receiptGossipLatency;
    }

    /**
     * Get timer for view change coordination (ms).
     * <p>
     * Measures drain period + member set propagation during reconfiguration.
     * </p>
     */
    public Timer viewChangeCoordinationTime() {
        return viewChangeCoordinationTime;
    }

    /**
     * Get timer for committee selection (μs).
     * <p>
     * Measures latency of bftSubset() deterministic committee selection.
     * </p>
     */
    public Timer committeeSelectionTime() {
        return committeeSelectionTime;
    }

    /**
     * Set threshold achievement rate (0-1).
     * <p>
     * Percentage of events reaching M-of-N threshold vs total events.
     * </p>
     *
     * @param rate Achievement rate (0.0 = 0%, 1.0 = 100%)
     */
    public void setThresholdAchievementRate(double rate) {
        thresholdAchievementRate.set(rate);
    }

    /**
     * Set number of in-flight receipt collections.
     * <p>
     * Active collections waiting for M-of-N threshold.
     * </p>
     *
     * @param count Number of in-flight collections
     */
    public void setInFlightCollections(int count) {
        inFlightCollections.set(count);
    }
}
