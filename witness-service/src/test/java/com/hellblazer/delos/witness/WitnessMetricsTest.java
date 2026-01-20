/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Timer;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Slf4jReporter;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Test-first implementation for WitnessMetrics instrumentation.
 * Tests metric registration, update operations, and reporter configuration.
 */
class WitnessMetricsTest {

    @Test
    void shouldCreateMetricsWithRegistry() {
        var registry = new MetricRegistry();
        var metrics = new WitnessMetrics(registry);

        assertThat(metrics).isNotNull();
        assertThat(metrics.getRegistry()).isSameAs(registry);
    }

    @Test
    void shouldRegisterReceiptCollectionLatencyHistogram() {
        var registry = new MetricRegistry();
        var metrics = new WitnessMetrics(registry);

        var histogram = metrics.receiptCollectionLatency();
        assertThat(histogram).isNotNull();
        assertThat(registry.getHistograms()).containsKey("witness.receipt.collection.latency");
    }

    @Test
    void shouldRecordReceiptCollectionLatency() {
        var registry = new MetricRegistry();
        var metrics = new WitnessMetrics(registry);

        metrics.receiptCollectionLatency().update(25);
        metrics.receiptCollectionLatency().update(30);
        metrics.receiptCollectionLatency().update(15);

        var histogram = metrics.receiptCollectionLatency();
        assertThat(histogram.getCount()).isEqualTo(3);
        assertThat(histogram.getSnapshot().getMean()).isCloseTo(23.33, within(0.1));
    }

    @Test
    void shouldRegisterThresholdAchievementRateGauge() {
        var registry = new MetricRegistry();
        var metrics = new WitnessMetrics(registry);

        metrics.setThresholdAchievementRate(0.85);

        @SuppressWarnings("unchecked")
        var gauge = (Gauge<Double>) registry.getGauges().get("witness.threshold.achievement.rate");
        assertThat(gauge).isNotNull();
        assertThat(gauge.getValue()).isCloseTo(0.85, within(0.01));
    }

    @Test
    void shouldUpdateThresholdAchievementRate() {
        var registry = new MetricRegistry();
        var metrics = new WitnessMetrics(registry);

        metrics.setThresholdAchievementRate(0.75);
        metrics.setThresholdAchievementRate(0.90);

        @SuppressWarnings("unchecked")
        var gauge = (Gauge<Double>) registry.getGauges().get("witness.threshold.achievement.rate");
        assertThat(gauge.getValue()).isCloseTo(0.90, within(0.01));
    }

    @Test
    void shouldRegisterViewChangeCoordinationTimer() {
        var registry = new MetricRegistry();
        var metrics = new WitnessMetrics(registry);

        var timer = metrics.viewChangeCoordinationTime();
        assertThat(timer).isNotNull();
        assertThat(registry.getTimers()).containsKey("witness.view.change.coordination.time");
    }

    @Test
    void shouldRecordViewChangeCoordinationTime() {
        var registry = new MetricRegistry();
        var metrics = new WitnessMetrics(registry);

        var context = metrics.viewChangeCoordinationTime().time();
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            context.stop();
        }

        var timer = metrics.viewChangeCoordinationTime();
        assertThat(timer.getCount()).isEqualTo(1);
        assertThat(timer.getSnapshot().getMean()).isGreaterThan(0);
    }

    @Test
    void shouldRegisterCommitteeSelectionTimer() {
        var registry = new MetricRegistry();
        var metrics = new WitnessMetrics(registry);

        var timer = metrics.committeeSelectionTime();
        assertThat(timer).isNotNull();
        assertThat(registry.getTimers()).containsKey("witness.committee.selection.time");
    }

    @Test
    void shouldRecordCommitteeSelectionTimeMicroseconds() {
        var registry = new MetricRegistry();
        var metrics = new WitnessMetrics(registry);

        // Simulate microsecond-level timing
        metrics.committeeSelectionTime().update(150, TimeUnit.MICROSECONDS);
        metrics.committeeSelectionTime().update(200, TimeUnit.MICROSECONDS);

        var timer = metrics.committeeSelectionTime();
        assertThat(timer.getCount()).isEqualTo(2);
    }

    @Test
    void shouldRegisterInFlightCollectionsGauge() {
        var registry = new MetricRegistry();
        var metrics = new WitnessMetrics(registry);

        metrics.setInFlightCollections(5);

        @SuppressWarnings("unchecked")
        var gauge = (Gauge<Integer>) registry.getGauges().get("witness.in.flight.collections");
        assertThat(gauge).isNotNull();
        assertThat(gauge.getValue()).isEqualTo(5);
    }

    @Test
    void shouldUpdateInFlightCollections() {
        var registry = new MetricRegistry();
        var metrics = new WitnessMetrics(registry);

        metrics.setInFlightCollections(3);
        metrics.setInFlightCollections(7);
        metrics.setInFlightCollections(2);

        @SuppressWarnings("unchecked")
        var gauge = (Gauge<Integer>) registry.getGauges().get("witness.in.flight.collections");
        assertThat(gauge.getValue()).isEqualTo(2);
    }

    @Test
    void shouldRegisterReceiptGossipLatencyHistogram() {
        var registry = new MetricRegistry();
        var metrics = new WitnessMetrics(registry);

        var histogram = metrics.receiptGossipLatency();
        assertThat(histogram).isNotNull();
        assertThat(registry.getHistograms()).containsKey("witness.receipt.gossip.latency");
    }

    @Test
    void shouldRecordReceiptGossipLatency() {
        var registry = new MetricRegistry();
        var metrics = new WitnessMetrics(registry);

        metrics.receiptGossipLatency().update(5);
        metrics.receiptGossipLatency().update(8);
        metrics.receiptGossipLatency().update(12);

        var histogram = metrics.receiptGossipLatency();
        assertThat(histogram.getCount()).isEqualTo(3);
        assertThat(histogram.getSnapshot().getMean()).isCloseTo(8.33, within(0.1));
    }

    @Test
    void shouldCreateSlf4jReporter() {
        var registry = new MetricRegistry();
        var metrics = new WitnessMetrics(registry);

        var reporter = Slf4jReporter.forRegistry(metrics.getRegistry())
                                     .outputTo(LoggerFactory.getLogger(WitnessMetrics.class))
                                     .convertRatesTo(TimeUnit.SECONDS)
                                     .convertDurationsTo(TimeUnit.MILLISECONDS)
                                     .build();

        assertThat(reporter).isNotNull();
        reporter.report(); // Should not throw
        reporter.close();
    }

    @Test
    void shouldHaveAllMetricsIncludingPhase1B3() {
        var registry = new MetricRegistry();
        var metrics = new WitnessMetrics(registry);

        // Initialize gauges
        metrics.setThresholdAchievementRate(0.0);
        metrics.setInFlightCollections(0);
        metrics.setBlsKeysRegistered(0);
        metrics.setBlsKeysCoverage(0.0);
        metrics.setTransitionReadiness(0);
        metrics.setTransitionInProgress(0);

        // Verify all metrics registered (original 6 + 8 Phase 1B-3 = 14+ metrics)
        var allMetrics = registry.getMetrics();
        assertThat(allMetrics.size()).isGreaterThanOrEqualTo(14);

        // Verify by type
        assertThat(registry.getHistograms()).hasSize(2); // receiptCollectionLatency, receiptGossipLatency
        assertThat(registry.getTimers()).hasSize(2);     // viewChangeCoordinationTime, committeeSelectionTime
        assertThat(registry.getGauges().size()).isGreaterThanOrEqualTo(6); // Original 2 + Phase 1B-3 gauges
        assertThat(registry.getCounters().size()).isGreaterThanOrEqualTo(5); // Phase 1B-3 counters (new)
    }
}
