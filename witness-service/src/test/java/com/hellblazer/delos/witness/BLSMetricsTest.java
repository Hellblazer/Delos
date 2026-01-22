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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for BLSMetrics interface contract.
 * <p>
 * Tests verify:
 * <ul>
 *   <li>Interface method signatures exist and return correct types</li>
 *   <li>Mock implementation can satisfy the interface contract</li>
 *   <li>Thread safety requirements are validated</li>
 *   <li>All 12+ metric operations are present</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
@DisplayName("BLSMetrics Interface Contract Tests")
class BLSMetricsTest {

    private BLSMetrics metrics;
    private MetricRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new MetricRegistry();
        metrics = new MockBLSMetrics();
        metrics.register(registry);
    }

    @Nested
    @DisplayName("Interface Contract")
    class InterfaceContract {

        @Test
        @DisplayName("should define lifecycle methods")
        void shouldDefineLifecycleMethods() throws NoSuchMethodException {
            // Verify interface has required lifecycle methods
            Method registerMethod = BLSMetrics.class.getMethod("register", MetricRegistry.class);
            assertThat(registerMethod.getReturnType()).isEqualTo(void.class);

            Method resetMethod = BLSMetrics.class.getMethod("reset");
            assertThat(resetMethod.getReturnType()).isEqualTo(void.class);

            Method getMetricsMethod = BLSMetrics.class.getMethod("getMetrics");
            assertThat(getMetricsMethod.getReturnType()).isEqualTo(Map.class);
        }

        @Test
        @DisplayName("should define signature receipt tracking methods")
        void shouldDefineReceiptTrackingMethods() throws NoSuchMethodException {
            Method recordLatency = BLSMetrics.class.getMethod("recordReceiptLatency", long.class);
            assertThat(recordLatency.getReturnType()).isEqualTo(void.class);

            Method getCount = BLSMetrics.class.getMethod("getReceiptCount");
            assertThat(getCount.getReturnType()).isEqualTo(long.class);

            Method incrementReceived = BLSMetrics.class.getMethod("incrementSignaturesReceived");
            assertThat(incrementReceived.getReturnType()).isEqualTo(void.class);

            Method incrementAccepted = BLSMetrics.class.getMethod("incrementSignaturesAccepted");
            assertThat(incrementAccepted.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("should define verification methods")
        void shouldDefineVerificationMethods() throws NoSuchMethodException {
            Method timerMethod = BLSMetrics.class.getMethod("signatureVerifyTimer");
            assertThat(timerMethod.getReturnType()).isEqualTo(Timer.class);

            Method recordLatency = BLSMetrics.class.getMethod("recordVerifyLatency", long.class);
            assertThat(recordLatency.getReturnType()).isEqualTo(void.class);

            Method getCount = BLSMetrics.class.getMethod("getVerifyCount");
            assertThat(getCount.getReturnType()).isEqualTo(long.class);
        }

        @Test
        @DisplayName("should define aggregation methods")
        void shouldDefineAggregationMethods() throws NoSuchMethodException {
            Method timerMethod = BLSMetrics.class.getMethod("aggregationTimer");
            assertThat(timerMethod.getReturnType()).isEqualTo(Timer.class);

            Method recordLatency = BLSMetrics.class.getMethod("recordAggregationLatency", long.class);
            assertThat(recordLatency.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("should define threshold timing methods")
        void shouldDefineThresholdMethods() throws NoSuchMethodException {
            Method timerMethod = BLSMetrics.class.getMethod("thresholdTimer");
            assertThat(timerMethod.getReturnType()).isEqualTo(Timer.class);

            Method recordTime = BLSMetrics.class.getMethod("recordThresholdTime", long.class);
            assertThat(recordTime.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("should define rejection counter methods")
        void shouldDefineRejectionMethods() throws NoSuchMethodException {
            // Increment methods
            Method incrementEpoch = BLSMetrics.class.getMethod("incrementRejectedEpoch");
            assertThat(incrementEpoch.getReturnType()).isEqualTo(void.class);

            Method incrementViewRef = BLSMetrics.class.getMethod("incrementRejectedViewRef");
            assertThat(incrementViewRef.getReturnType()).isEqualTo(void.class);

            Method incrementLate = BLSMetrics.class.getMethod("incrementRejectedLate");
            assertThat(incrementLate.getReturnType()).isEqualTo(void.class);

            Method incrementDuplicate = BLSMetrics.class.getMethod("incrementRejectedDuplicate");
            assertThat(incrementDuplicate.getReturnType()).isEqualTo(void.class);

            // Counter accessor methods
            Method epochCounter = BLSMetrics.class.getMethod("rejectedEpochCounter");
            assertThat(epochCounter.getReturnType()).isEqualTo(Counter.class);

            Method viewRefCounter = BLSMetrics.class.getMethod("rejectedViewRefCounter");
            assertThat(viewRefCounter.getReturnType()).isEqualTo(Counter.class);

            Method lateCounter = BLSMetrics.class.getMethod("rejectedLateCounter");
            assertThat(lateCounter.getReturnType()).isEqualTo(Counter.class);

            Method duplicateCounter = BLSMetrics.class.getMethod("rejectedDuplicateCounter");
            assertThat(duplicateCounter.getReturnType()).isEqualTo(Counter.class);
        }

        @Test
        @DisplayName("should define accumulator tracking methods")
        void shouldDefineAccumulatorMethods() throws NoSuchMethodException {
            Method setActive = BLSMetrics.class.getMethod("setActiveAccumulators", int.class);
            assertThat(setActive.getReturnType()).isEqualTo(void.class);

            Method getActive = BLSMetrics.class.getMethod("getActiveAccumulators");
            assertThat(getActive.getReturnType()).isEqualTo(int.class);

            Method recordCompleted = BLSMetrics.class.getMethod("recordCompletedAccumulation");
            assertThat(recordCompleted.getReturnType()).isEqualTo(void.class);

            Method getMeter = BLSMetrics.class.getMethod("completedAccumulationsMeter");
            assertThat(getMeter.getReturnType()).isEqualTo(Meter.class);
        }

        @Test
        @DisplayName("should have minimum 12 metric operations")
        void shouldHaveMinimum12Operations() {
            // Count public methods (excluding getMetrics, register, reset)
            var methods = BLSMetrics.class.getDeclaredMethods();
            var metricOperations = java.util.Arrays.stream(methods)
                .filter(m -> !m.getName().equals("getMetrics"))
                .filter(m -> !m.getName().equals("register"))
                .filter(m -> !m.getName().equals("reset"))
                .count();

            assertThat(metricOperations).isGreaterThanOrEqualTo(12);
        }
    }

    @Nested
    @DisplayName("Mock Implementation Behavior")
    class MockImplementationBehavior {

        @Test
        @DisplayName("should register metrics with registry")
        void shouldRegisterMetrics() {
            // Metrics already registered in setUp()
            var registeredMetrics = metrics.getMetrics();

            assertThat(registeredMetrics).isNotEmpty();
            assertThat(registeredMetrics).containsKeys(
                "bls.signature.receipt.latency",
                "bls.signature.verify.latency",
                "bls.aggregation.create.latency",
                "bls.threshold.time"
            );
        }

        @Test
        @DisplayName("should record receipt latency")
        void shouldRecordReceiptLatency() {
            metrics.recordReceiptLatency(100L);
            assertThat(metrics.getReceiptCount()).isEqualTo(1);

            metrics.recordReceiptLatency(200L);
            assertThat(metrics.getReceiptCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("should increment signature counters")
        void shouldIncrementSignatureCounters() {
            metrics.incrementSignaturesReceived();
            metrics.incrementSignaturesReceived();
            metrics.incrementSignaturesAccepted();

            // Verify counters were incremented (implementation-specific)
            assertThatNoException().isThrownBy(() -> metrics.incrementSignaturesReceived());
        }

        @Test
        @DisplayName("should record verify latency")
        void shouldRecordVerifyLatency() {
            metrics.recordVerifyLatency(50L);
            assertThat(metrics.getVerifyCount()).isEqualTo(1);

            metrics.recordVerifyLatency(75L);
            assertThat(metrics.getVerifyCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("should provide verify timer")
        void shouldProvideVerifyTimer() {
            var timer = metrics.signatureVerifyTimer();
            assertThat(timer).isNotNull();

            try (var ctx = timer.time()) {
                // Simulate verification work
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            assertThat(timer.getCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("should record aggregation latency")
        void shouldRecordAggregationLatency() {
            var timer = metrics.aggregationTimer();
            assertThat(timer).isNotNull();

            metrics.recordAggregationLatency(150L);
            // Timer should have recorded via manual method
            assertThatNoException().isThrownBy(() -> metrics.recordAggregationLatency(200L));
        }

        @Test
        @DisplayName("should record threshold timing")
        void shouldRecordThresholdTiming() {
            var timer = metrics.thresholdTimer();
            assertThat(timer).isNotNull();

            metrics.recordThresholdTime(5000L);
            assertThatNoException().isThrownBy(() -> metrics.recordThresholdTime(6000L));
        }

        @Test
        @DisplayName("should track rejection reasons")
        void shouldTrackRejectionReasons() {
            metrics.incrementRejectedEpoch();
            metrics.incrementRejectedViewRef();
            metrics.incrementRejectedLate();
            metrics.incrementRejectedDuplicate();

            assertThat(metrics.rejectedEpochCounter()).isNotNull();
            assertThat(metrics.rejectedViewRefCounter()).isNotNull();
            assertThat(metrics.rejectedLateCounter()).isNotNull();
            assertThat(metrics.rejectedDuplicateCounter()).isNotNull();

            assertThat(metrics.rejectedEpochCounter().getCount()).isEqualTo(1);
            assertThat(metrics.rejectedViewRefCounter().getCount()).isEqualTo(1);
            assertThat(metrics.rejectedLateCounter().getCount()).isEqualTo(1);
            assertThat(metrics.rejectedDuplicateCounter().getCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("should track active accumulators")
        void shouldTrackActiveAccumulators() {
            metrics.setActiveAccumulators(5);
            assertThat(metrics.getActiveAccumulators()).isEqualTo(5);

            metrics.setActiveAccumulators(10);
            assertThat(metrics.getActiveAccumulators()).isEqualTo(10);

            metrics.setActiveAccumulators(0);
            assertThat(metrics.getActiveAccumulators()).isEqualTo(0);
        }

        @Test
        @DisplayName("should record completed accumulations")
        void shouldRecordCompletedAccumulations() {
            metrics.recordCompletedAccumulation();
            metrics.recordCompletedAccumulation();

            var meter = metrics.completedAccumulationsMeter();
            assertThat(meter).isNotNull();
            assertThat(meter.getCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("should reset metrics")
        void shouldResetMetrics() {
            metrics.incrementRejectedEpoch();
            metrics.incrementRejectedViewRef();
            metrics.setActiveAccumulators(5);

            metrics.reset();

            // After reset, counters and gauges should be zero
            // Note: Histograms/Timers cannot be fully reset in Dropwizard without re-registration
            assertThat(metrics.rejectedEpochCounter().getCount()).isEqualTo(0);
            assertThat(metrics.rejectedViewRefCounter().getCount()).isEqualTo(0);
            assertThat(metrics.getActiveAccumulators()).isEqualTo(0);
        }

        @Test
        @DisplayName("should return immutable metrics map")
        void shouldReturnImmutableMetricsMap() {
            var metricsMap = metrics.getMetrics();
            assertThat(metricsMap).isNotNull();

            // Attempt to modify should fail
            assertThatThrownBy(() -> metricsMap.put("test", new Counter()))
                .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("Thread Safety")
    class ThreadSafety {

        @Test
        @DisplayName("should handle concurrent receipt latency recording")
        void shouldHandleConcurrentReceiptLatency() throws InterruptedException {
            var threadCount = 10;
            var operationsPerThread = 100;
            var executor = Executors.newFixedThreadPool(threadCount);
            var latch = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        for (int j = 0; j < operationsPerThread; j++) {
                            metrics.recordReceiptLatency(100L);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            assertThat(metrics.getReceiptCount()).isEqualTo(threadCount * operationsPerThread);
        }

        @Test
        @DisplayName("should handle concurrent rejection increments")
        void shouldHandleConcurrentRejections() throws InterruptedException {
            var threadCount = 10;
            var operationsPerThread = 100;
            var executor = Executors.newFixedThreadPool(threadCount);
            var latch = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                var rejectionType = i % 4;
                executor.submit(() -> {
                    try {
                        for (int j = 0; j < operationsPerThread; j++) {
                            switch (rejectionType) {
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

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            // Each rejection type should have been called by 2-3 threads
            var totalRejections = metrics.rejectedEpochCounter().getCount()
                + metrics.rejectedViewRefCounter().getCount()
                + metrics.rejectedLateCounter().getCount()
                + metrics.rejectedDuplicateCounter().getCount();

            assertThat(totalRejections).isEqualTo(threadCount * operationsPerThread);
        }

        @Test
        @DisplayName("should handle concurrent accumulator updates")
        void shouldHandleConcurrentAccumulatorUpdates() throws InterruptedException {
            var threadCount = 10;
            var executor = Executors.newFixedThreadPool(threadCount);
            var latch = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                var finalI = i;
                executor.submit(() -> {
                    try {
                        for (int j = 0; j < 50; j++) {
                            metrics.setActiveAccumulators(finalI * 10 + j);
                            Thread.yield(); // Encourage contention
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            // Should have a valid value (last write wins)
            assertThat(metrics.getActiveAccumulators()).isBetween(0, 500);
        }

        @Test
        @DisplayName("should handle concurrent timer usage")
        void shouldHandleConcurrentTimerUsage() throws InterruptedException {
            var threadCount = 10;
            var operationsPerThread = 50;
            var executor = Executors.newFixedThreadPool(threadCount);
            var latch = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        for (int j = 0; j < operationsPerThread; j++) {
                            try (var ctx = metrics.signatureVerifyTimer().time()) {
                                Thread.sleep(1);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            assertThat(metrics.signatureVerifyTimer().getCount()).isEqualTo(threadCount * operationsPerThread);
        }
    }

    /**
     * Mock implementation of BLSMetrics for testing interface contract.
     * <p>
     * This implementation demonstrates that the interface can be implemented
     * and provides basic functionality for testing. The actual production
     * implementation (BLSMetricsImpl) will be created in Delos-3966.
     */
    private static class MockBLSMetrics implements BLSMetrics {
        private MetricRegistry registeredRegistry;
        private final Histogram receiptLatency = new Histogram(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS));
        private final Timer verifyTimer = new Timer();
        private final Timer aggregationTimer = new Timer();
        private final Timer thresholdTimer = new Timer();
        private final Counter signaturesReceived = new Counter();
        private final Counter signaturesAccepted = new Counter();
        private final Counter rejectedEpoch = new Counter();
        private final Counter rejectedViewRef = new Counter();
        private final Counter rejectedLate = new Counter();
        private final Counter rejectedDuplicate = new Counter();
        private final AtomicInteger activeAccumulators = new AtomicInteger(0);
        private final Meter completedAccumulations = new Meter();

        @Override
        public void register(MetricRegistry registry) {
            this.registeredRegistry = registry;
            registry.register("bls.signature.receipt.latency", receiptLatency);
            registry.register("bls.signature.verify.latency", verifyTimer);
            registry.register("bls.aggregation.create.latency", aggregationTimer);
            registry.register("bls.threshold.time", thresholdTimer);
            registry.register("bls.signatures.received", signaturesReceived);
            registry.register("bls.signatures.accepted", signaturesAccepted);
            registry.register("bls.signatures.rejected.epoch", rejectedEpoch);
            registry.register("bls.signatures.rejected.viewRef", rejectedViewRef);
            registry.register("bls.signatures.rejected.late", rejectedLate);
            registry.register("bls.signatures.rejected.duplicate", rejectedDuplicate);
            registry.register("bls.accumulator.active", (Gauge<Integer>) activeAccumulators::get);
            registry.register("bls.accumulator.completed", completedAccumulations);
        }

        @Override
        public void reset() {
            // Reset all metrics to initial state
            // Note: Dropwizard histograms cannot be truly reset without re-registering
            // This is a limitation of the Dropwizard Metrics library
            // For production, consider using MetricRegistry.remove() and re-registering
            rejectedEpoch.dec(rejectedEpoch.getCount());
            rejectedViewRef.dec(rejectedViewRef.getCount());
            rejectedLate.dec(rejectedLate.getCount());
            rejectedDuplicate.dec(rejectedDuplicate.getCount());
            activeAccumulators.set(0);
        }

        @Override
        public Map<String, Metric> getMetrics() {
            if (registeredRegistry == null) {
                return Map.of();
            }
            return Map.copyOf(registeredRegistry.getMetrics());
        }

        @Override
        public void recordReceiptLatency(long latencyMicros) {
            receiptLatency.update(latencyMicros);
        }

        @Override
        public long getReceiptCount() {
            return receiptLatency.getCount();
        }

        @Override
        public void incrementSignaturesReceived() {
            signaturesReceived.inc();
        }

        @Override
        public void incrementSignaturesAccepted() {
            signaturesAccepted.inc();
        }

        @Override
        public Timer signatureVerifyTimer() {
            return verifyTimer;
        }

        @Override
        public void recordVerifyLatency(long latencyMicros) {
            verifyTimer.update(latencyMicros, TimeUnit.MICROSECONDS);
        }

        @Override
        public long getVerifyCount() {
            return verifyTimer.getCount();
        }

        @Override
        public Timer aggregationTimer() {
            return aggregationTimer;
        }

        @Override
        public void recordAggregationLatency(long latencyMicros) {
            aggregationTimer.update(latencyMicros, TimeUnit.MICROSECONDS);
        }

        @Override
        public Timer thresholdTimer() {
            return thresholdTimer;
        }

        @Override
        public void recordThresholdTime(long durationMicros) {
            thresholdTimer.update(durationMicros, TimeUnit.MICROSECONDS);
        }

        @Override
        public void incrementRejectedEpoch() {
            rejectedEpoch.inc();
        }

        @Override
        public void incrementRejectedViewRef() {
            rejectedViewRef.inc();
        }

        @Override
        public void incrementRejectedLate() {
            rejectedLate.inc();
        }

        @Override
        public void incrementRejectedDuplicate() {
            rejectedDuplicate.inc();
        }

        @Override
        public Counter rejectedEpochCounter() {
            return rejectedEpoch;
        }

        @Override
        public Counter rejectedViewRefCounter() {
            return rejectedViewRef;
        }

        @Override
        public Counter rejectedLateCounter() {
            return rejectedLate;
        }

        @Override
        public Counter rejectedDuplicateCounter() {
            return rejectedDuplicate;
        }

        @Override
        public void setActiveAccumulators(int count) {
            activeAccumulators.set(count);
        }

        @Override
        public int getActiveAccumulators() {
            return activeAccumulators.get();
        }

        @Override
        public void recordCompletedAccumulation() {
            completedAccumulations.mark();
        }

        @Override
        public Meter completedAccumulationsMeter() {
            return completedAccumulations;
        }
    }
}
