/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import com.codahale.metrics.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BLSMetricsImpl.
 * <p>
 * Tests cover:
 * - Basic metric recording and retrieval
 * - Histogram bucket distributions
 * - Timer operations
 * - Counter operations
 * - Gauge updates
 * - Meter rate calculations
 * - Thread safety with concurrent access
 * - Registration lifecycle
 * - Error handling
 * <p>
 * Thread safety tests use 10+ concurrent threads to verify lock-free implementation.
 *
 * @author hal.hildebrand
 */
class BLSMetricsImplTest {

    private BLSMetricsImpl metrics;
    private MetricRegistry registry;

    @BeforeEach
    void setUp() {
        metrics = new BLSMetricsImpl();
        registry = new MetricRegistry();
        metrics.register(registry);
    }

    // =============================
    // Registration Tests
    // =============================

    @Test
    void testRegisterNullRegistryThrowsException() {
        var m = new BLSMetricsImpl();
        assertThrows(NullPointerException.class, () -> m.register(null));
    }

    @Test
    void testRegisterIdempotent() {
        // Can register multiple times with same registry
        assertDoesNotThrow(() -> metrics.register(registry));
        assertDoesNotThrow(() -> metrics.register(registry));
    }

    @Test
    void testRegisterDifferentRegistryThrowsException() {
        var otherRegistry = new MetricRegistry();
        assertThrows(IllegalStateException.class, () -> metrics.register(otherRegistry));
    }

    @Test
    void testGetMetricsReturnsAllBLSMetrics() {
        var metricMap = metrics.getMetrics();

        // Should have exactly 12 core metrics
        assertEquals(12, metricMap.size());

        // Verify all expected metrics present
        assertTrue(metricMap.containsKey("bls.signature.receipt.latency"));
        assertTrue(metricMap.containsKey("bls.signature.verify.latency"));
        assertTrue(metricMap.containsKey("bls.aggregation.create.latency"));
        assertTrue(metricMap.containsKey("bls.threshold.time"));
        assertTrue(metricMap.containsKey("bls.signatures.received"));
        assertTrue(metricMap.containsKey("bls.signatures.accepted"));
        assertTrue(metricMap.containsKey("bls.signatures.rejected.epoch"));
        assertTrue(metricMap.containsKey("bls.signatures.rejected.viewRef"));
        assertTrue(metricMap.containsKey("bls.signatures.rejected.late"));
        assertTrue(metricMap.containsKey("bls.signatures.rejected.duplicate"));
        assertTrue(metricMap.containsKey("bls.accumulator.active"));
        assertTrue(metricMap.containsKey("bls.accumulator.completed"));
    }

    @Test
    void testGetMetricsReturnsImmutableMap() {
        var metricMap = metrics.getMetrics();
        assertThrows(UnsupportedOperationException.class, () -> metricMap.put("test", new Counter()));
    }

    @Test
    void testGetMetricsWithoutRegistrationReturnsEmpty() {
        var m = new BLSMetricsImpl();
        assertTrue(m.getMetrics().isEmpty());
    }

    // =============================
    // Receipt Latency Tests
    // =============================

    @Test
    void testRecordReceiptLatency() {
        metrics.recordReceiptLatency(100);
        metrics.recordReceiptLatency(200);
        metrics.recordReceiptLatency(300);

        assertEquals(3, metrics.getReceiptCount());

        var histogram = (Histogram) registry.getHistograms().get("bls.signature.receipt.latency");
        assertNotNull(histogram);
        var snapshot = histogram.getSnapshot();
        assertEquals(200.0, snapshot.getMedian(), 1.0);
    }

    @Test
    void testRecordReceiptLatencyNegativeThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> metrics.recordReceiptLatency(-1));
    }

    @Test
    void testRecordReceiptLatencyZeroAllowed() {
        assertDoesNotThrow(() -> metrics.recordReceiptLatency(0));
        assertEquals(1, metrics.getReceiptCount());
    }

    @Test
    void testReceiptLatencyHistogramBuckets() {
        // Record latencies across different bucket ranges
        var latencies = new long[] { 1, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000 };

        for (var latency : latencies) {
            metrics.recordReceiptLatency(latency);
        }

        var histogram = (Histogram) registry.getHistograms().get("bls.signature.receipt.latency");
        var snapshot = histogram.getSnapshot();

        // Verify percentiles span expected range
        assertTrue(snapshot.getMin() >= 1);
        assertTrue(snapshot.getMax() <= 10000);
        assertTrue(snapshot.get75thPercentile() > snapshot.getMedian());
        assertTrue(snapshot.get95thPercentile() > snapshot.get75thPercentile());
    }

    // =============================
    // Signature Verification Tests
    // =============================

    @Test
    void testSignatureVerifyTimer() {
        var timer = metrics.signatureVerifyTimer();
        assertNotNull(timer);

        try (var context = timer.time()) {
            // Simulate work
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertTrue(metrics.getVerifyCount() > 0);
    }

    @Test
    void testRecordVerifyLatency() {
        metrics.recordVerifyLatency(500);
        metrics.recordVerifyLatency(600);

        assertEquals(2, metrics.getVerifyCount());

        var timer = registry.getTimers().get("bls.signature.verify.latency");
        assertNotNull(timer);
        assertTrue(timer.getMeanRate() > 0);
    }

    @Test
    void testRecordVerifyLatencyNegativeThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> metrics.recordVerifyLatency(-100));
    }

    // =============================
    // Aggregation Tests
    // =============================

    @Test
    void testAggregationTimer() {
        var timer = metrics.aggregationTimer();
        assertNotNull(timer);

        try (var context = timer.time()) {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        var registeredTimer = registry.getTimers().get("bls.aggregation.create.latency");
        assertNotNull(registeredTimer);
        assertTrue(registeredTimer.getCount() > 0);
    }

    @Test
    void testRecordAggregationLatency() {
        metrics.recordAggregationLatency(1000);
        metrics.recordAggregationLatency(1500);
        metrics.recordAggregationLatency(2000);

        var timer = registry.getTimers().get("bls.aggregation.create.latency");
        assertEquals(3, timer.getCount());
    }

    @Test
    void testRecordAggregationLatencyNegativeThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> metrics.recordAggregationLatency(-1));
    }

    // =============================
    // Threshold Timing Tests
    // =============================

    @Test
    void testThresholdTimer() {
        var timer = metrics.thresholdTimer();
        assertNotNull(timer);

        var context = timer.time();
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            context.stop();
        }

        var registeredTimer = registry.getTimers().get("bls.threshold.time");
        assertTrue(registeredTimer.getCount() > 0);
    }

    @Test
    void testRecordThresholdTime() {
        metrics.recordThresholdTime(5000);
        metrics.recordThresholdTime(6000);

        var timer = registry.getTimers().get("bls.threshold.time");
        assertEquals(2, timer.getCount());
        assertTrue(timer.getSnapshot().getMean() > 0);
    }

    @Test
    void testRecordThresholdTimeNegativeThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> metrics.recordThresholdTime(-1000));
    }

    // =============================
    // Throughput Meter Tests
    // =============================

    @Test
    void testIncrementSignaturesReceived() {
        IntStream.range(0, 100).forEach(i -> metrics.incrementSignaturesReceived());

        var meter = registry.getMeters().get("bls.signatures.received");
        assertEquals(100, meter.getCount());
        assertTrue(meter.getMeanRate() > 0);
    }

    @Test
    void testIncrementSignaturesAccepted() {
        IntStream.range(0, 75).forEach(i -> metrics.incrementSignaturesAccepted());

        var meter = registry.getMeters().get("bls.signatures.accepted");
        assertEquals(75, meter.getCount());
    }

    @Test
    void testMeterRateCalculations() throws InterruptedException {
        // Record events over time to test rate calculations
        for (int i = 0; i < 50; i++) {
            metrics.incrementSignaturesReceived();
            Thread.sleep(2); // Small delay to establish rate
        }

        var meter = registry.getMeters().get("bls.signatures.received");
        assertEquals(50, meter.getCount());

        // Mean rate should be positive
        assertTrue(meter.getMeanRate() > 0);

        // 1-minute rate should be calculable
        assertTrue(meter.getOneMinuteRate() >= 0);
    }

    // =============================
    // Rejection Counter Tests
    // =============================

    @Test
    void testIncrementRejectedEpoch() {
        metrics.incrementRejectedEpoch();
        metrics.incrementRejectedEpoch();

        assertEquals(2, metrics.rejectedEpochCounter().getCount());
    }

    @Test
    void testIncrementRejectedViewRef() {
        metrics.incrementRejectedViewRef();
        metrics.incrementRejectedViewRef();
        metrics.incrementRejectedViewRef();

        assertEquals(3, metrics.rejectedViewRefCounter().getCount());
    }

    @Test
    void testIncrementRejectedLate() {
        IntStream.range(0, 5).forEach(i -> metrics.incrementRejectedLate());

        assertEquals(5, metrics.rejectedLateCounter().getCount());
    }

    @Test
    void testIncrementRejectedDuplicate() {
        IntStream.range(0, 10).forEach(i -> metrics.incrementRejectedDuplicate());

        assertEquals(10, metrics.rejectedDuplicateCounter().getCount());
    }

    @Test
    void testRejectionCountersIndependent() {
        metrics.incrementRejectedEpoch();
        metrics.incrementRejectedViewRef();
        metrics.incrementRejectedLate();
        metrics.incrementRejectedDuplicate();

        assertEquals(1, metrics.rejectedEpochCounter().getCount());
        assertEquals(1, metrics.rejectedViewRefCounter().getCount());
        assertEquals(1, metrics.rejectedLateCounter().getCount());
        assertEquals(1, metrics.rejectedDuplicateCounter().getCount());
    }

    // =============================
    // Accumulator Health Tests
    // =============================

    @Test
    void testSetActiveAccumulators() {
        metrics.setActiveAccumulators(5);
        assertEquals(5, metrics.getActiveAccumulators());

        metrics.setActiveAccumulators(10);
        assertEquals(10, metrics.getActiveAccumulators());
    }

    @Test
    void testSetActiveAccumulatorsNegativeThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> metrics.setActiveAccumulators(-1));
    }

    @Test
    void testSetActiveAccumulatorsZeroAllowed() {
        metrics.setActiveAccumulators(5);
        assertDoesNotThrow(() -> metrics.setActiveAccumulators(0));
        assertEquals(0, metrics.getActiveAccumulators());
    }

    @Test
    void testRecordCompletedAccumulation() {
        IntStream.range(0, 20).forEach(i -> metrics.recordCompletedAccumulation());

        var meter = metrics.completedAccumulationsMeter();
        assertEquals(20, meter.getCount());
    }

    // =============================
    // Thread Safety Tests
    // =============================

    @Test
    void testConcurrentReceiptLatencyRecording() throws InterruptedException {
        var threadCount = 20;
        var operationsPerThread = 1000;
        var executor = Executors.newFixedThreadPool(threadCount);
        var latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        metrics.recordReceiptLatency(100 + j % 1000);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(threadCount * operationsPerThread, metrics.getReceiptCount());
    }

    @Test
    void testConcurrentCounterIncrements() throws InterruptedException {
        var threadCount = 16; // Use multiple of 4 for even distribution
        var operationsPerThread = 500;
        var executor = Executors.newFixedThreadPool(threadCount);
        var latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            var threadIndex = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        // Distribute across different rejection types
                        switch (threadIndex % 4) {
                            case 0 -> metrics.incrementRejectedEpoch();
                            case 1 -> metrics.incrementRejectedViewRef();
                            case 2 -> metrics.incrementRejectedLate();
                            case 3 -> metrics.incrementRejectedDuplicate();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        // Each counter type should have exactly 1/4 of total operations (16 threads, 4 counter types)
        var expectedPerCounter = (threadCount * operationsPerThread) / 4;
        assertEquals(expectedPerCounter, metrics.rejectedEpochCounter().getCount());
        assertEquals(expectedPerCounter, metrics.rejectedViewRefCounter().getCount());
        assertEquals(expectedPerCounter, metrics.rejectedLateCounter().getCount());
        assertEquals(expectedPerCounter, metrics.rejectedDuplicateCounter().getCount());
    }

    @Test
    void testConcurrentGaugeUpdates() throws InterruptedException {
        var threadCount = 10;
        var executor = Executors.newFixedThreadPool(threadCount);
        var latch = new CountDownLatch(threadCount);
        var maxValue = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            var value = i + 1;
            executor.submit(() -> {
                try {
                    metrics.setActiveAccumulators(value);
                    maxValue.updateAndGet(current -> Math.max(current, value));
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        // Final value should be one of the set values
        var finalValue = metrics.getActiveAccumulators();
        assertTrue(finalValue >= 1 && finalValue <= threadCount);
    }

    @Test
    void testConcurrentMeterMarking() throws InterruptedException {
        var threadCount = 20;
        var operationsPerThread = 500;
        var executor = Executors.newFixedThreadPool(threadCount);
        var latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        metrics.incrementSignaturesReceived();
                        if (j % 2 == 0) {
                            metrics.incrementSignaturesAccepted();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        var receivedMeter = registry.getMeters().get("bls.signatures.received");
        var acceptedMeter = registry.getMeters().get("bls.signatures.accepted");

        assertEquals(threadCount * operationsPerThread, receivedMeter.getCount());
        assertEquals((threadCount * operationsPerThread) / 2, acceptedMeter.getCount());
    }

    @Test
    void testConcurrentTimerOperations() throws InterruptedException {
        var threadCount = 15;
        var operationsPerThread = 100;
        var executor = Executors.newFixedThreadPool(threadCount);
        var latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        metrics.recordVerifyLatency(100 + j);
                        metrics.recordAggregationLatency(200 + j);
                        metrics.recordThresholdTime(300 + j);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        var expectedCount = threadCount * operationsPerThread;
        assertEquals(expectedCount, metrics.getVerifyCount());
        assertEquals(expectedCount, registry.getTimers().get("bls.aggregation.create.latency").getCount());
        assertEquals(expectedCount, registry.getTimers().get("bls.threshold.time").getCount());
    }

    // =============================
    // Reset Tests
    // =============================

    @Test
    void testReset() {
        // Record various metrics
        metrics.recordReceiptLatency(100);
        metrics.incrementRejectedEpoch();
        metrics.incrementRejectedViewRef();
        metrics.incrementRejectedLate();
        metrics.incrementRejectedDuplicate();
        metrics.setActiveAccumulators(5);

        // Reset
        metrics.reset();

        // Counters should be zero
        assertEquals(0, metrics.rejectedEpochCounter().getCount());
        assertEquals(0, metrics.rejectedViewRefCounter().getCount());
        assertEquals(0, metrics.rejectedLateCounter().getCount());
        assertEquals(0, metrics.rejectedDuplicateCounter().getCount());

        // Gauge should be zero
        assertEquals(0, metrics.getActiveAccumulators());

        // Histogram count remains (Dropwizard limitation)
        // But subsequent operations should work correctly
        metrics.recordReceiptLatency(200);
        assertTrue(metrics.getReceiptCount() > 0);
    }

    // =============================
    // Graceful Degradation Tests
    // =============================

    @Test
    void testOperationsWithoutRegistration() {
        var m = new BLSMetricsImpl();

        // All operations should no-op without throwing
        assertDoesNotThrow(() -> {
            m.recordReceiptLatency(100);
            m.incrementSignaturesReceived();
            m.incrementSignaturesAccepted();
            m.incrementRejectedEpoch();
            m.incrementRejectedViewRef();
            m.incrementRejectedLate();
            m.incrementRejectedDuplicate();
            m.setActiveAccumulators(5);
            m.recordCompletedAccumulation();
            m.recordVerifyLatency(200);
            m.recordAggregationLatency(300);
            m.recordThresholdTime(400);
        });

        // Counts should be zero
        assertEquals(0, m.getReceiptCount());
        assertEquals(0, m.getVerifyCount());
        assertEquals(5, m.getActiveAccumulators()); // Gauge works without registry
    }

    @Test
    void testTimerWithoutRegistrationReturnsNoOp() {
        var m = new BLSMetricsImpl();

        var timer = m.signatureVerifyTimer();
        assertNotNull(timer);

        // Should not throw
        assertDoesNotThrow(() -> {
            try (var context = timer.time()) {
                Thread.sleep(1);
            }
        });
    }
}
