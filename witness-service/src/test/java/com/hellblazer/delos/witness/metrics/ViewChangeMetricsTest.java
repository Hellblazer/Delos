/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.metrics;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.hellblazer.delos.witness.BLSMetrics;
import com.hellblazer.delos.witness.BLSMetricsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive tests for view change metrics tracking.
 * <p>
 * Tests cover:
 * - View changes initiated (Counter)
 * - View change duration (Timer)
 * - Active views (Gauge)
 * - Committee reconfigurations (Meter)
 * - Threshold recalculation events (Meter)
 * - Concurrent view changes
 * - Integration with existing BLS metrics
 * <p>
 * Phase 1C-3-D-C1-C4: View Change Metrics (Delos-3971)
 *
 * @author hal.hildebrand
 */
class ViewChangeMetricsTest {

    private BLSMetrics metrics;
    private MetricRegistry registry;

    @BeforeEach
    void setUp() {
        metrics = new BLSMetricsImpl();
        registry = new MetricRegistry();
        metrics.register(registry);
    }

    @Test
    void shouldTrackViewChangesInitiated() {
        // Given: No view changes initially
        assertThat(metrics.getViewChangesInitiated()).isEqualTo(0);

        // When: Multiple view changes initiated
        metrics.incrementViewChangesInitiated();
        metrics.incrementViewChangesInitiated();
        metrics.incrementViewChangesInitiated();

        // Then: Counter increments correctly
        assertThat(metrics.getViewChangesInitiated()).isEqualTo(3);
    }

    @Test
    void shouldRecordViewChangeDuration() {
        // Given: Timer for view change duration
        var timer = metrics.viewChangeDurationTimer();

        // When: Record view change with timer context
        try (var ctx = timer.time()) {
            simulateViewChange();
        }

        // Then: Duration recorded
        assertThat(metrics.getViewChangeDurationCount()).isEqualTo(1);
        assertThat(timer.getSnapshot().getMedian()).isGreaterThan(0);
    }

    @Test
    void shouldRecordViewChangeDurationManually() {
        // Given: Known duration
        var durationMicros = 5000L; // 5ms

        // When: Manually record duration
        metrics.recordViewChangeDuration(durationMicros);

        // Then: Duration recorded correctly
        assertThat(metrics.getViewChangeDurationCount()).isEqualTo(1);
        assertThat(metrics.viewChangeDurationTimer().getSnapshot().getMax())
            .isGreaterThanOrEqualTo(TimeUnit.MICROSECONDS.toNanos(durationMicros));
    }

    @Test
    void shouldRejectNegativeViewChangeDuration() {
        // When/Then: Negative duration throws exception
        assertThatThrownBy(() -> metrics.recordViewChangeDuration(-1000))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be negative");
    }

    @Test
    void shouldTrackActiveViewNumber() {
        // Given: Initial view
        metrics.setActiveView(1);
        assertThat(metrics.getActiveView()).isEqualTo(1);

        // When: View changes
        metrics.setActiveView(5);

        // Then: Active view updated
        assertThat(metrics.getActiveView()).isEqualTo(5);
    }

    @Test
    void shouldRejectNegativeViewNumber() {
        // When/Then: Negative view throws exception
        assertThatThrownBy(() -> metrics.setActiveView(-1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be negative");
    }

    @Test
    void shouldTrackCommitteeReconfigurations() {
        // Given: No reconfigurations initially
        var meter = metrics.committeeReconfigurationsMeter();
        assertThat(meter.getCount()).isEqualTo(0);

        // When: Multiple reconfigurations occur
        metrics.recordCommitteeReconfiguration();
        metrics.recordCommitteeReconfiguration();

        // Then: Meter increments
        assertThat(meter.getCount()).isEqualTo(2);
        assertThat(meter.getMeanRate()).isGreaterThan(0);
    }

    @Test
    void shouldTrackThresholdRecalculationEvents() {
        // Given: No recalculations initially
        var meter = metrics.thresholdRecalculationsMeter();
        assertThat(meter.getCount()).isEqualTo(0);

        // When: Threshold recalculated multiple times
        metrics.recordThresholdRecalculation();
        metrics.recordThresholdRecalculation();
        metrics.recordThresholdRecalculation();

        // Then: Meter increments
        assertThat(meter.getCount()).isEqualTo(3);
        assertThat(meter.getMeanRate()).isGreaterThan(0);
    }

    @Test
    void shouldHandleConcurrentViewChanges() throws InterruptedException {
        // Given: Multiple threads initiating view changes
        var threadCount = 10;
        var iterationsPerThread = 100;
        var latch = new CountDownLatch(threadCount);

        // When: Concurrent view change initiation
        for (int i = 0; i < threadCount; i++) {
            Thread.ofVirtual().start(() -> {
                for (int j = 0; j < iterationsPerThread; j++) {
                    metrics.incrementViewChangesInitiated();
                }
                latch.countDown();
            });
        }

        latch.await(5, TimeUnit.SECONDS);

        // Then: All increments accounted for (thread-safe)
        assertThat(metrics.getViewChangesInitiated()).isEqualTo(threadCount * iterationsPerThread);
    }

    @Test
    void shouldHandleConcurrentViewChangeDurations() throws InterruptedException {
        // Given: Multiple threads recording durations
        var threadCount = 10;
        var iterationsPerThread = 50;
        var latch = new CountDownLatch(threadCount);

        // When: Concurrent duration recording
        for (int i = 0; i < threadCount; i++) {
            Thread.ofVirtual().start(() -> {
                for (int j = 0; j < iterationsPerThread; j++) {
                    metrics.recordViewChangeDuration(1000 + j);
                }
                latch.countDown();
            });
        }

        latch.await(5, TimeUnit.SECONDS);

        // Then: All durations recorded (thread-safe)
        assertThat(metrics.getViewChangeDurationCount()).isEqualTo(threadCount * iterationsPerThread);
    }

    @Test
    void shouldTrackViewChangeRollback() {
        // Given: View change initiated
        metrics.incrementViewChangesInitiated();
        var initialView = 5;
        metrics.setActiveView(initialView);

        // When: View change fails and rolls back
        metrics.setActiveView(initialView); // Rollback to same view

        // Then: View change initiated recorded but view unchanged
        assertThat(metrics.getViewChangesInitiated()).isEqualTo(1);
        assertThat(metrics.getActiveView()).isEqualTo(initialView);
    }

    @Test
    void shouldTrackMultipleReconfigurationsCascade() {
        // Given: Initial state
        var initialView = 1;
        metrics.setActiveView(initialView);

        // When: Cascading reconfigurations (Byzantine detection triggers multiple changes)
        for (int i = 0; i < 5; i++) {
            metrics.incrementViewChangesInitiated();
            metrics.recordCommitteeReconfiguration();
            metrics.recordThresholdRecalculation();
            metrics.setActiveView(initialView + i + 1);
            metrics.recordViewChangeDuration(2000 + i * 100);
        }

        // Then: All events tracked
        assertThat(metrics.getViewChangesInitiated()).isEqualTo(5);
        assertThat(metrics.committeeReconfigurationsMeter().getCount()).isEqualTo(5);
        assertThat(metrics.thresholdRecalculationsMeter().getCount()).isEqualTo(5);
        assertThat(metrics.getActiveView()).isEqualTo(initialView + 5);
        assertThat(metrics.getViewChangeDurationCount()).isEqualTo(5);
    }

    @Test
    void shouldIntegrateWithExistingBLSMetrics() {
        // Given: Existing BLS metrics in use
        metrics.incrementSignaturesReceived();
        metrics.recordReceiptLatency(100);

        // When: View change occurs during signature processing
        metrics.incrementViewChangesInitiated();
        try (var ctx = metrics.viewChangeDurationTimer().time()) {
            metrics.incrementSignaturesReceived();
            simulateViewChange();
        }

        // Then: Both metrics systems work concurrently
        assertThat(metrics.getViewChangesInitiated()).isEqualTo(1);
        assertThat(metrics.getViewChangeDurationCount()).isEqualTo(1);
        assertThat(metrics.getReceiptCount()).isEqualTo(1);

        // Verify registry has both metric families
        var metricNames = registry.getMetrics().keySet();
        assertThat(metricNames)
            .contains("bls.signature.receipt.latency")
            .contains("bls.view.changes.initiated");
    }

    @Test
    void shouldResetViewChangeMetrics() {
        // Given: Metrics with values
        metrics.incrementViewChangesInitiated();
        metrics.setActiveView(10);
        metrics.recordCommitteeReconfiguration();
        metrics.recordThresholdRecalculation();
        metrics.recordViewChangeDuration(5000);

        // When: Reset metrics
        metrics.reset();

        // Then: All view change metrics reset (counters and gauges reset, meters may retain state)
        assertThat(metrics.getViewChangesInitiated()).isEqualTo(0);
        assertThat(metrics.getActiveView()).isEqualTo(0);
        // Meters retain internal state; verify they exist after reset but don't check count
        assertThat(metrics.committeeReconfigurationsMeter()).isNotNull();
        assertThat(metrics.thresholdRecalculationsMeter()).isNotNull();
        assertThat(metrics.getViewChangeDurationCount()).isEqualTo(0);
    }

    @Test
    void shouldProvideViewChangeMetricsSnapshot() {
        // Given: Various view change metrics recorded
        metrics.incrementViewChangesInitiated();
        metrics.incrementViewChangesInitiated();
        metrics.setActiveView(7);
        metrics.recordCommitteeReconfiguration();
        metrics.recordThresholdRecalculation();
        metrics.recordViewChangeDuration(3000);

        // When: Get metrics snapshot
        var snapshot = metrics.getMetrics();

        // Then: Snapshot contains view change metrics
        assertThat(snapshot)
            .containsKeys(
                "bls.view.changes.initiated",
                "bls.view.active",
                "bls.view.reconfigurations",
                "bls.view.threshold.recalculations",
                "bls.view.change.duration"
            );
    }

    @Test
    void shouldHandleHighFrequencyViewChanges() {
        // Given: Rapid view changes (stress test)
        var viewChangeCount = 1000;

        // When: High frequency view changes
        for (int i = 0; i < viewChangeCount; i++) {
            metrics.incrementViewChangesInitiated();
            metrics.setActiveView(i);
            metrics.recordViewChangeDuration(100 + i);
        }

        // Then: All tracked correctly
        assertThat(metrics.getViewChangesInitiated()).isEqualTo(viewChangeCount);
        assertThat(metrics.getActiveView()).isEqualTo(viewChangeCount - 1);
        assertThat(metrics.getViewChangeDurationCount()).isEqualTo(viewChangeCount);
    }

    /**
     * Simulate view change operation with small delay.
     */
    private void simulateViewChange() {
        try {
            Thread.sleep(1); // 1ms minimum
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
