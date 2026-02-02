/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Test-first implementation for WitnessMetrics instrumentation.
 * Tests metric registration, update operations, and reporter configuration.
 */
class WitnessMetricsTest {

    @Test
    void shouldCreateMetricsWithRegistry() {
        var registry = new SimpleMeterRegistry();
        var metrics = new WitnessMetrics(registry);

        assertThat(metrics).isNotNull();
        assertThat(metrics.getRegistry()).isSameAs(registry);
    }

    @Test
    void shouldRegisterReceiptCollectionLatencyHistogram() {
        var registry = new SimpleMeterRegistry();
        var metrics = new WitnessMetrics(registry);

        // Trigger metric registration by recording a value
        metrics.recordReceiptCollectionLatency(1);

        DistributionSummary summary = registry.find("witness.receipt.collection.latency").summary();
        assertThat(summary).isNotNull();
    }

    @Test
    void shouldRecordReceiptCollectionLatency() {
        var registry = new SimpleMeterRegistry();
        var metrics = new WitnessMetrics(registry);

        metrics.recordReceiptCollectionLatency(25);
        metrics.recordReceiptCollectionLatency(30);
        metrics.recordReceiptCollectionLatency(15);

        DistributionSummary summary = registry.find("witness.receipt.collection.latency").summary();
        assertThat(summary).isNotNull();
        assertThat(summary.count()).isEqualTo(3);
        assertThat(summary.mean()).isCloseTo(23.33, within(0.1));
    }

    @Test
    void shouldRegisterThresholdAchievementRateGauge() {
        var registry = new SimpleMeterRegistry();
        var metrics = new WitnessMetrics(registry);

        metrics.setThresholdAchievementRate(0.85);

        Gauge gauge = registry.find("witness.threshold.achievement.rate").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isCloseTo(0.85, within(0.01));
    }

    @Test
    void shouldUpdateThresholdAchievementRate() {
        var registry = new SimpleMeterRegistry();
        var metrics = new WitnessMetrics(registry);

        metrics.setThresholdAchievementRate(0.75);
        metrics.setThresholdAchievementRate(0.90);

        Gauge gauge = registry.find("witness.threshold.achievement.rate").gauge();
        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isCloseTo(0.90, within(0.01));
    }

    @Test
    void shouldRegisterViewChangeCoordinationTimer() {
        var registry = new SimpleMeterRegistry();
        var metrics = new WitnessMetrics(registry);

        // Trigger metric registration by recording a value
        metrics.recordViewChangeCoordinationDuration(1000000); // 1ms

        Timer timer = registry.find("witness.view.change.coordination.time").timer();
        assertThat(timer).isNotNull();
    }

    @Test
    void shouldRecordViewChangeCoordinationTime() {
        var registry = new SimpleMeterRegistry();
        var metrics = new WitnessMetrics(registry);

        var startNanos = System.nanoTime();
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        var durationNanos = System.nanoTime() - startNanos;
        metrics.recordViewChangeCoordinationDuration(durationNanos);

        Timer timer = registry.find("witness.view.change.coordination.time").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.mean(java.util.concurrent.TimeUnit.NANOSECONDS)).isGreaterThan(0);
    }

    @Test
    void shouldRegisterCommitteeSelectionTimer() {
        var registry = new SimpleMeterRegistry();
        var metrics = new WitnessMetrics(registry);

        // Trigger metric registration by recording a value
        metrics.recordCommitteeSelectionDuration(1000); // 1μs

        Timer timer = registry.find("witness.committee.selection.time").timer();
        assertThat(timer).isNotNull();
    }

    @Test
    void shouldRecordCommitteeSelectionTimeMicroseconds() {
        var registry = new SimpleMeterRegistry();
        var metrics = new WitnessMetrics(registry);

        // Record nanosecond-level timing (150μs = 150,000ns)
        metrics.recordCommitteeSelectionDuration(150_000);
        metrics.recordCommitteeSelectionDuration(200_000);

        Timer timer = registry.find("witness.committee.selection.time").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(2);
    }

    @Test
    void shouldRegisterInFlightCollectionsGauge() {
        var registry = new SimpleMeterRegistry();
        var metrics = new WitnessMetrics(registry);

        metrics.setInFlightCollections(5);

        Gauge gauge = registry.find("witness.in.flight.collections").gauge();
        assertThat(gauge).isNotNull();
        assertThat((int) gauge.value()).isEqualTo(5);
    }

    @Test
    void shouldUpdateInFlightCollections() {
        var registry = new SimpleMeterRegistry();
        var metrics = new WitnessMetrics(registry);

        metrics.setInFlightCollections(3);
        metrics.setInFlightCollections(7);
        metrics.setInFlightCollections(2);

        Gauge gauge = registry.find("witness.in.flight.collections").gauge();
        assertThat(gauge).isNotNull();
        assertThat((int) gauge.value()).isEqualTo(2);
    }

    @Test
    void shouldRegisterReceiptGossipLatencyHistogram() {
        var registry = new SimpleMeterRegistry();
        var metrics = new WitnessMetrics(registry);

        // Trigger metric registration by recording a value
        metrics.recordReceiptGossipLatency(1);

        DistributionSummary summary = registry.find("witness.receipt.gossip.latency").summary();
        assertThat(summary).isNotNull();
    }

    @Test
    void shouldRecordReceiptGossipLatency() {
        var registry = new SimpleMeterRegistry();
        var metrics = new WitnessMetrics(registry);

        metrics.recordReceiptGossipLatency(5);
        metrics.recordReceiptGossipLatency(8);
        metrics.recordReceiptGossipLatency(12);

        DistributionSummary summary = registry.find("witness.receipt.gossip.latency").summary();
        assertThat(summary).isNotNull();
        assertThat(summary.count()).isEqualTo(3);
        assertThat(summary.mean()).isCloseTo(8.33, within(0.1));
    }

    @Test
    void shouldHaveAllMetricsIncludingPhase1B3() {
        var registry = new SimpleMeterRegistry();
        var metrics = new WitnessMetrics(registry);

        // Initialize gauges
        metrics.setThresholdAchievementRate(0.0);
        metrics.setInFlightCollections(0);
        metrics.setBlsKeysRegistered(0);
        metrics.setBlsKeysCoverage(0.0);
        metrics.setTransitionReadiness(0);
        metrics.setTransitionInProgress(0);

        // Verify all metrics registered (original 6 + 8 Phase 1B-3 = 14+ metrics)
        var allMeters = registry.getMeters();
        assertThat(allMeters.size()).isGreaterThanOrEqualTo(14);

        // Verify key metrics exist
        assertThat(registry.find("witness.receipt.collection.latency").summary()).isNotNull();
        assertThat(registry.find("witness.receipt.gossip.latency").summary()).isNotNull();
        assertThat(registry.find("witness.view.change.coordination.time").timer()).isNotNull();
        assertThat(registry.find("witness.committee.selection.time").timer()).isNotNull();
        assertThat(registry.find("witness.threshold.achievement.rate").gauge()).isNotNull();
        assertThat(registry.find("witness.in.flight.collections").gauge()).isNotNull();
        assertThat(registry.find("witness.bls.keys.registered").gauge()).isNotNull();
        assertThat(registry.find("witness.byzantine.shunned").counter()).isNotNull();
    }
}
