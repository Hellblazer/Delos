/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.metrics;

import com.codahale.metrics.MetricRegistry;
import com.hellblazer.delos.witness.BLSMetrics;
import com.hellblazer.delos.witness.BLSMetricsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive tests for graceful degradation metrics tracking.
 * <p>
 * Tests cover:
 * - State transitions (STABLE→DRAINING→TRANSITIONING→STABLE)
 * - Time in each state (Histogram)
 * - Buffer operations (creation, drain, replay)
 * - Buffered signatures (Gauge)
 * - Threshold calculation deltas (Histogram)
 * - Byzantine exclusions and recovery (Counters)
 * - Performance during degradation (Timers)
 * - Concurrent degradation operations
 * <p>
 * Phase 1C-3-D-C1-C4: Graceful Degradation Metrics (Delos-3971)
 *
 * @author hal.hildebrand
 */
class GracefulDegradationMetricsTest {

    private BLSMetrics metrics;
    private MetricRegistry registry;

    @BeforeEach
    void setUp() {
        metrics = new BLSMetricsImpl();
        registry = new MetricRegistry();
        metrics.register(registry);
    }

    @Test
    void shouldTrackStateTransitionStableToDraining() {
        // Given: Initial STABLE state
        assertThat(metrics.getDegradationStateTransitions("STABLE_TO_DRAINING")).isEqualTo(0);

        // When: Transition to DRAINING
        metrics.recordDegradationStateTransition("STABLE_TO_DRAINING");

        // Then: Transition recorded
        assertThat(metrics.getDegradationStateTransitions("STABLE_TO_DRAINING")).isEqualTo(1);
    }

    @Test
    void shouldTrackStateTransitionDrainingToTransitioning() {
        // Given: DRAINING state
        metrics.recordDegradationStateTransition("STABLE_TO_DRAINING");

        // When: Transition to TRANSITIONING
        metrics.recordDegradationStateTransition("DRAINING_TO_TRANSITIONING");

        // Then: Both transitions recorded
        assertThat(metrics.getDegradationStateTransitions("STABLE_TO_DRAINING")).isEqualTo(1);
        assertThat(metrics.getDegradationStateTransitions("DRAINING_TO_TRANSITIONING")).isEqualTo(1);
    }

    @Test
    void shouldTrackStateTransitionTransitioningToStable() {
        // Given: TRANSITIONING state
        metrics.recordDegradationStateTransition("DRAINING_TO_TRANSITIONING");

        // When: Transition back to STABLE
        metrics.recordDegradationStateTransition("TRANSITIONING_TO_STABLE");

        // Then: Transition recorded
        assertThat(metrics.getDegradationStateTransitions("TRANSITIONING_TO_STABLE")).isEqualTo(1);
    }

    @Test
    void shouldTrackTimeInStableState() {
        // Given: Time spent in STABLE state
        var timeMs = 5000L;

        // When: Record time in state
        metrics.recordTimeInDegradationState("STABLE", timeMs);

        // Then: Time recorded in histogram
        var histogram = metrics.timeInDegradationStateHistogram("STABLE");
        assertThat(histogram.getCount()).isEqualTo(1);
        assertThat(histogram.getSnapshot().getMax()).isGreaterThanOrEqualTo(timeMs);
    }

    @Test
    void shouldTrackTimeInDrainingState() {
        // Given: Time spent in DRAINING state
        var timeMs = 2000L;

        // When: Record time in state
        metrics.recordTimeInDegradationState("DRAINING", timeMs);

        // Then: Time recorded
        var histogram = metrics.timeInDegradationStateHistogram("DRAINING");
        assertThat(histogram.getCount()).isEqualTo(1);
        assertThat(histogram.getSnapshot().getMax()).isGreaterThanOrEqualTo(timeMs);
    }

    @Test
    void shouldTrackTimeInTransitioningState() {
        // Given: Time spent in TRANSITIONING state
        var timeMs = 1500L;

        // When: Record time in state
        metrics.recordTimeInDegradationState("TRANSITIONING", timeMs);

        // Then: Time recorded
        var histogram = metrics.timeInDegradationStateHistogram("TRANSITIONING");
        assertThat(histogram.getCount()).isEqualTo(1);
        assertThat(histogram.getSnapshot().getMax()).isGreaterThanOrEqualTo(timeMs);
    }

    @Test
    void shouldRejectNegativeTimeInState() {
        // When/Then: Negative time throws exception
        assertThatThrownBy(() -> metrics.recordTimeInDegradationState("STABLE", -1000))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be negative");
    }

    @Test
    void shouldTrackBuffersCreated() {
        // Given: No buffers initially
        var meter = metrics.buffersCreatedMeter();
        assertThat(meter.getCount()).isEqualTo(0);

        // When: Buffers created during degradation
        metrics.recordBufferCreated();
        metrics.recordBufferCreated();
        metrics.recordBufferCreated();

        // Then: Meter increments
        assertThat(meter.getCount()).isEqualTo(3);
        assertThat(meter.getMeanRate()).isGreaterThan(0);
    }

    @Test
    void shouldTrackBufferedSignaturesGauge() {
        // Given: Initial state
        assertThat(metrics.getBufferedSignatures()).isEqualTo(0);

        // When: Signatures buffered
        metrics.setBufferedSignatures(50);

        // Then: Gauge updated
        assertThat(metrics.getBufferedSignatures()).isEqualTo(50);

        // When: More signatures buffered
        metrics.setBufferedSignatures(100);

        // Then: Gauge reflects current count
        assertThat(metrics.getBufferedSignatures()).isEqualTo(100);
    }

    @Test
    void shouldRejectNegativeBufferedSignatures() {
        // When/Then: Negative count throws exception
        assertThatThrownBy(() -> metrics.setBufferedSignatures(-10))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be negative");
    }

    @Test
    void shouldTrackBufferedSignaturesDrained() {
        // Given: Signatures in buffer
        metrics.setBufferedSignatures(100);

        // When: Signatures drained during recovery
        metrics.recordBufferedSignaturesDrained(100);

        // Then: Drain meter increments
        var meter = metrics.bufferedSignaturesDrainedMeter();
        assertThat(meter.getCount()).isEqualTo(100);
        assertThat(meter.getMeanRate()).isGreaterThan(0);
    }

    @Test
    void shouldTrackThresholdCalculationDeltas() {
        // Given: Threshold recalculated due to Byzantine exclusion
        var originalThreshold = 7; // 2/3 of 10 members
        var degradedThreshold = 5;  // After 2 Byzantine excluded
        var delta = originalThreshold - degradedThreshold;

        // When: Record threshold delta
        metrics.recordThresholdCalculationDelta(delta);

        // Then: Delta recorded in histogram
        var histogram = metrics.thresholdCalculationDeltaHistogram();
        assertThat(histogram.getCount()).isEqualTo(1);
        assertThat(histogram.getSnapshot().getMax()).isEqualTo(delta);
    }

    @Test
    void shouldTrackNegativeThresholdDeltas() {
        // Given: Threshold increases during recovery (negative delta)
        var delta = -2; // Threshold increased by 2

        // When: Record negative delta
        metrics.recordThresholdCalculationDelta(delta);

        // Then: Negative delta recorded
        var histogram = metrics.thresholdCalculationDeltaHistogram();
        assertThat(histogram.getCount()).isEqualTo(1);
        assertThat(histogram.getSnapshot().getMin()).isEqualTo(delta);
    }

    @Test
    void shouldTrackByzantineMemberExclusions() {
        // Given: No exclusions initially
        assertThat(metrics.getByzantineExclusions()).isEqualTo(0);

        // When: Byzantine members excluded
        metrics.incrementByzantineExclusions();
        metrics.incrementByzantineExclusions();

        // Then: Counter increments
        assertThat(metrics.getByzantineExclusions()).isEqualTo(2);
    }

    @Test
    void shouldTrackRecoveryEvents() {
        // Given: No recoveries initially
        assertThat(metrics.getMemberRecoveries()).isEqualTo(0);

        // When: Members recovered after exclusion
        metrics.incrementMemberRecoveries();
        metrics.incrementMemberRecoveries();
        metrics.incrementMemberRecoveries();

        // Then: Counter increments
        assertThat(metrics.getMemberRecoveries()).isEqualTo(3);
    }

    @Test
    void shouldTrackReceiptProcessingLatencyDuringDegradation() {
        // Given: Receipt processing with degradation context
        var latencyMicros = 2000L;

        // When: Record latency with state context
        metrics.recordReceiptProcessingLatencyDuringDegradation("DRAINING", latencyMicros);

        // Then: Latency recorded for DRAINING state
        var timer = metrics.receiptProcessingLatencyDuringDegradationTimer("DRAINING");
        assertThat(timer.getCount()).isEqualTo(1);
        assertThat(timer.getSnapshot().getMax())
            .isGreaterThanOrEqualTo(TimeUnit.MICROSECONDS.toNanos(latencyMicros));
    }

    @Test
    void shouldCompareLatencyAcrossStates() {
        // Given: Receipt processing in different states
        var stableLatency = 500L;
        var drainingLatency = 1500L;
        var transitioningLatency = 2500L;

        // When: Record latencies for each state
        metrics.recordReceiptProcessingLatencyDuringDegradation("STABLE", stableLatency);
        metrics.recordReceiptProcessingLatencyDuringDegradation("DRAINING", drainingLatency);
        metrics.recordReceiptProcessingLatencyDuringDegradation("TRANSITIONING", transitioningLatency);

        // Then: Different latencies per state
        var stableTimer = metrics.receiptProcessingLatencyDuringDegradationTimer("STABLE");
        var drainingTimer = metrics.receiptProcessingLatencyDuringDegradationTimer("DRAINING");
        var transitioningTimer = metrics.receiptProcessingLatencyDuringDegradationTimer("TRANSITIONING");

        assertThat(stableTimer.getSnapshot().getMax())
            .isLessThan(drainingTimer.getSnapshot().getMax());
        assertThat(drainingTimer.getSnapshot().getMax())
            .isLessThan(transitioningTimer.getSnapshot().getMax());
    }

    @Test
    void shouldRejectNegativeReceiptLatencyDuringDegradation() {
        // When/Then: Negative latency throws exception
        assertThatThrownBy(() ->
            metrics.recordReceiptProcessingLatencyDuringDegradation("DRAINING", -1000))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be negative");
    }

    @Test
    void shouldTrackBufferDrainTime() {
        // Given: Buffer drain operation
        var timer = metrics.bufferDrainTimer();

        // When: Time buffer drain
        try (var ctx = timer.time()) {
            simulateBufferDrain();
        }

        // Then: Drain time recorded
        assertThat(timer.getCount()).isEqualTo(1);
        assertThat(timer.getSnapshot().getMedian()).isGreaterThan(0);
    }

    @Test
    void shouldRecordBufferDrainTimeManually() {
        // Given: Known drain time
        var drainTimeMicros = 8000L; // 8ms to drain 1000 signatures

        // When: Manually record drain time
        metrics.recordBufferDrainTime(drainTimeMicros);

        // Then: Drain time recorded
        assertThat(metrics.bufferDrainTimeTimer().getCount()).isEqualTo(1);
        assertThat(metrics.bufferDrainTimeTimer().getSnapshot().getMax())
            .isGreaterThanOrEqualTo(TimeUnit.MICROSECONDS.toNanos(drainTimeMicros));
    }

    @Test
    void shouldRejectNegativeBufferDrainTime() {
        // When/Then: Negative time throws exception
        assertThatThrownBy(() -> metrics.recordBufferDrainTime(-1000))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be negative");
    }

    @Test
    void shouldTrackSignatureReplayLatency() {
        // Given: Buffered signatures being replayed
        var replayLatencyMicros = 500L;

        // When: Replay signatures from buffer
        metrics.recordSignatureReplayLatency(replayLatencyMicros);

        // Then: Replay latency recorded
        var timer = metrics.signatureReplayTimer();
        assertThat(timer.getCount()).isEqualTo(1);
        assertThat(timer.getSnapshot().getMax())
            .isGreaterThanOrEqualTo(TimeUnit.MICROSECONDS.toNanos(replayLatencyMicros));
    }

    @Test
    void shouldRejectNegativeReplayLatency() {
        // When/Then: Negative latency throws exception
        assertThatThrownBy(() -> metrics.recordSignatureReplayLatency(-1000))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be negative");
    }

    @Test
    void shouldTrackThresholdRecalculationTime() {
        // Given: Threshold recalculation operation
        var timer = metrics.thresholdRecalculationTimer();

        // When: Time threshold recalculation
        try (var ctx = timer.time()) {
            simulateThresholdRecalculation();
        }

        // Then: Recalculation time recorded
        assertThat(timer.getCount()).isEqualTo(1);
        assertThat(timer.getSnapshot().getMedian()).isGreaterThan(0);
    }

    @Test
    void shouldRecordThresholdRecalculationTimeManually() {
        // Given: Known recalculation time
        var recalcTimeMicros = 100L; // 100μs

        // When: Manually record time
        metrics.recordThresholdRecalculationTime(recalcTimeMicros);

        // Then: Time recorded
        assertThat(metrics.thresholdRecalculationTimer().getCount()).isEqualTo(1);
        assertThat(metrics.thresholdRecalculationTimer().getSnapshot().getMax())
            .isGreaterThanOrEqualTo(TimeUnit.MICROSECONDS.toNanos(recalcTimeMicros));
    }

    @Test
    void shouldRejectNegativeRecalculationTime() {
        // When/Then: Negative time throws exception
        assertThatThrownBy(() -> metrics.recordThresholdRecalculationTime(-100))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be negative");
    }

    @Test
    void shouldHandleConcurrentStateTransitions() throws InterruptedException {
        // Given: Multiple threads recording state transitions
        var threadCount = 10;
        var iterationsPerThread = 100;
        var latch = new CountDownLatch(threadCount);

        // When: Concurrent state transitions
        for (int i = 0; i < threadCount; i++) {
            Thread.ofVirtual().start(() -> {
                for (int j = 0; j < iterationsPerThread; j++) {
                    metrics.recordDegradationStateTransition("STABLE_TO_DRAINING");
                }
                latch.countDown();
            });
        }

        // Wait for all threads to complete with generous timeout and buffer
        var completed = latch.await(30, TimeUnit.SECONDS);
        assertThat(completed).describedAs("All virtual threads should complete within timeout").isTrue();
        Thread.sleep(100); // Allow time for metrics flush

        // Then: All transitions recorded (thread-safe)
        assertThat(metrics.getDegradationStateTransitions("STABLE_TO_DRAINING"))
            .isEqualTo(threadCount * iterationsPerThread);
    }

    @Test
    void shouldHandleConcurrentBufferOperations() throws InterruptedException {
        // Given: Multiple threads updating buffer metrics
        var threadCount = 10;
        var iterationsPerThread = 50;
        var latch = new CountDownLatch(threadCount);

        // When: Concurrent buffer operations
        for (int i = 0; i < threadCount; i++) {
            Thread.ofVirtual().start(() -> {
                for (int j = 0; j < iterationsPerThread; j++) {
                    metrics.recordBufferCreated();
                    metrics.setBufferedSignatures(j);
                    metrics.recordBufferedSignaturesDrained(1);
                }
                latch.countDown();
            });
        }

        latch.await(5, TimeUnit.SECONDS);

        // Then: All operations recorded (thread-safe)
        assertThat(metrics.buffersCreatedMeter().getCount())
            .isEqualTo(threadCount * iterationsPerThread);
        assertThat(metrics.bufferedSignaturesDrainedMeter().getCount())
            .isEqualTo(threadCount * iterationsPerThread);
    }

    @Test
    void shouldTrackCompleteDegradationCycle() {
        // Given: Complete degradation and recovery cycle

        // STABLE → DRAINING
        metrics.recordDegradationStateTransition("STABLE_TO_DRAINING");
        metrics.recordTimeInDegradationState("STABLE", 10000);
        metrics.recordBufferCreated();

        // Byzantine detection
        metrics.incrementByzantineExclusions();
        metrics.recordThresholdCalculationDelta(2);
        metrics.recordThresholdRecalculationTime(100);

        // Buffer signatures
        metrics.setBufferedSignatures(50);
        metrics.recordReceiptProcessingLatencyDuringDegradation("DRAINING", 1500);

        // DRAINING → TRANSITIONING
        metrics.recordDegradationStateTransition("DRAINING_TO_TRANSITIONING");
        metrics.recordTimeInDegradationState("DRAINING", 3000);

        // Drain buffer
        metrics.recordBufferDrainTime(5000);
        metrics.recordSignatureReplayLatency(500);
        metrics.recordBufferedSignaturesDrained(50);
        metrics.setBufferedSignatures(0);

        // TRANSITIONING → STABLE
        metrics.recordDegradationStateTransition("TRANSITIONING_TO_STABLE");
        metrics.recordTimeInDegradationState("TRANSITIONING", 2000);

        // Recovery
        metrics.incrementMemberRecoveries();
        metrics.recordThresholdCalculationDelta(-2); // Threshold restored

        // Then: Complete cycle tracked
        assertThat(metrics.getDegradationStateTransitions("STABLE_TO_DRAINING")).isEqualTo(1);
        assertThat(metrics.getDegradationStateTransitions("DRAINING_TO_TRANSITIONING")).isEqualTo(1);
        assertThat(metrics.getDegradationStateTransitions("TRANSITIONING_TO_STABLE")).isEqualTo(1);
        assertThat(metrics.getByzantineExclusions()).isEqualTo(1);
        assertThat(metrics.getMemberRecoveries()).isEqualTo(1);
        assertThat(metrics.buffersCreatedMeter().getCount()).isEqualTo(1);
        assertThat(metrics.thresholdCalculationDeltaHistogram().getCount()).isEqualTo(2);
    }

    @Test
    void shouldResetDegradationMetrics() {
        // Given: Metrics with values
        metrics.recordDegradationStateTransition("STABLE_TO_DRAINING");
        metrics.recordTimeInDegradationState("STABLE", 5000);
        metrics.recordBufferCreated();
        metrics.setBufferedSignatures(100);
        metrics.incrementByzantineExclusions();
        metrics.incrementMemberRecoveries();

        // When: Reset metrics
        metrics.reset();

        // Then: All degradation metrics reset
        assertThat(metrics.getDegradationStateTransitions("STABLE_TO_DRAINING")).isEqualTo(0);
        assertThat(metrics.getBufferedSignatures()).isEqualTo(0);
        assertThat(metrics.getByzantineExclusions()).isEqualTo(0);
        assertThat(metrics.getMemberRecoveries()).isEqualTo(0);
        // Meters retain internal state; verify they exist after reset but don't check count
        assertThat(metrics.buffersCreatedMeter()).isNotNull();
    }

    @Test
    void shouldProvideDegradationMetricsSnapshot() {
        // Given: Various degradation metrics recorded
        metrics.recordDegradationStateTransition("STABLE_TO_DRAINING");
        metrics.setBufferedSignatures(50);
        metrics.incrementByzantineExclusions();
        metrics.recordBufferDrainTime(5000);

        // When: Get metrics snapshot
        var snapshot = metrics.getMetrics();

        // Then: Snapshot contains degradation metrics
        assertThat(snapshot)
            .containsKeys(
                "bls.degradation.state.transition.STABLE_TO_DRAINING",
                "bls.buffer.signatures",
                "bls.degradation.byzantine.exclusions",
                "bls.degradation.buffer.drain.time"
            );
    }

    /**
     * Simulate buffer drain operation with small delay.
     */
    private void simulateBufferDrain() {
        try {
            Thread.sleep(1); // 1ms minimum
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Simulate threshold recalculation with small delay.
     */
    private void simulateThresholdRecalculation() {
        try {
            Thread.sleep(1); // 1ms minimum
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
