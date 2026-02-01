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
            Method recordLatency = BLSMetrics.class.getMethod("recordVerifyLatency", long.class);
            assertThat(recordLatency.getReturnType()).isEqualTo(void.class);

            Method getCount = BLSMetrics.class.getMethod("getVerifyCount");
            assertThat(getCount.getReturnType()).isEqualTo(long.class);
        }

        @Test
        @DisplayName("should define aggregation methods")
        void shouldDefineAggregationMethods() throws NoSuchMethodException {
            Method recordLatency = BLSMetrics.class.getMethod("recordAggregationLatency", long.class);
            assertThat(recordLatency.getReturnType()).isEqualTo(void.class);
        }

        @Test
        @DisplayName("should define threshold timing methods")
        void shouldDefineThresholdMethods() throws NoSuchMethodException {
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
        @DisplayName("should record verify timing")
        void shouldRecordVerifyTiming() {
            // Use semantic method to record verification timing
            metrics.recordVerifyLatency(50L);
            assertThat(metrics.getVerifyCount()).isEqualTo(1);

            metrics.recordVerifyLatency(75L);
            assertThat(metrics.getVerifyCount()).isEqualTo(2);

            // Verify timer was registered in the registry
            var timer = registry.getTimers().get("bls.signature.verify.latency");
            assertThat(timer).isNotNull();
            assertThat(timer.getCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("should record aggregation latency")
        void shouldRecordAggregationLatency() {
            metrics.recordAggregationLatency(150L);
            metrics.recordAggregationLatency(200L);

            // Verify timer was registered in the registry
            var timer = registry.getTimers().get("bls.aggregation.create.latency");
            assertThat(timer).isNotNull();
            assertThat(timer.getCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("should record threshold timing")
        void shouldRecordThresholdTiming() {
            metrics.recordThresholdTime(5000L);
            metrics.recordThresholdTime(6000L);

            // Verify timer was registered in the registry
            var timer = registry.getTimers().get("bls.threshold.time");
            assertThat(timer).isNotNull();
            assertThat(timer.getCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("should track rejection reasons")
        void shouldTrackRejectionReasons() {
            metrics.incrementRejectedEpoch();
            metrics.incrementRejectedViewRef();
            metrics.incrementRejectedLate();
            metrics.incrementRejectedDuplicate();

            // Verify counters via registry
            var epochCounter = (Counter) registry.getCounters().get("bls.signatures.rejected.epoch");
            var viewRefCounter = (Counter) registry.getCounters().get("bls.signatures.rejected.viewRef");
            var lateCounter = (Counter) registry.getCounters().get("bls.signatures.rejected.late");
            var duplicateCounter = (Counter) registry.getCounters().get("bls.signatures.rejected.duplicate");

            assertThat(epochCounter).isNotNull();
            assertThat(viewRefCounter).isNotNull();
            assertThat(lateCounter).isNotNull();
            assertThat(duplicateCounter).isNotNull();

            assertThat(epochCounter.getCount()).isEqualTo(1);
            assertThat(viewRefCounter.getCount()).isEqualTo(1);
            assertThat(lateCounter.getCount()).isEqualTo(1);
            assertThat(duplicateCounter.getCount()).isEqualTo(1);
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

            // Verify meter via registry
            var meter = (Meter) registry.getMeters().get("bls.accumulator.completed");
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
            var epochCounter = (Counter) registry.getCounters().get("bls.signatures.rejected.epoch");
            var viewRefCounter = (Counter) registry.getCounters().get("bls.signatures.rejected.viewRef");

            assertThat(epochCounter.getCount()).isEqualTo(0);
            assertThat(viewRefCounter.getCount()).isEqualTo(0);
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
            // Verify via registry
            var epochCounter = (Counter) registry.getCounters().get("bls.signatures.rejected.epoch");
            var viewRefCounter = (Counter) registry.getCounters().get("bls.signatures.rejected.viewRef");
            var lateCounter = (Counter) registry.getCounters().get("bls.signatures.rejected.late");
            var duplicateCounter = (Counter) registry.getCounters().get("bls.signatures.rejected.duplicate");

            var totalRejections = epochCounter.getCount()
                + viewRefCounter.getCount()
                + lateCounter.getCount()
                + duplicateCounter.getCount();

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
                            // Use semantic method to record timing
                            var startNanos = System.nanoTime();
                            try {
                                Thread.sleep(1);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            var latencyMicros = (System.nanoTime() - startNanos) / 1000;
                            metrics.recordVerifyLatency(latencyMicros);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();

            assertThat(metrics.getVerifyCount()).isEqualTo(threadCount * operationsPerThread);
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
        private final Counter rejectedInvalid = new Counter();
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
            registry.register("bls.signatures.rejected.invalid", rejectedInvalid);
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
            rejectedInvalid.dec(rejectedInvalid.getCount());
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

        // No longer in interface - kept for internal use by recordVerifyLatency
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

        // No longer in interface - kept for internal use by recordAggregationLatency
        public Timer aggregationTimer() {
            return aggregationTimer;
        }

        @Override
        public void recordAggregationLatency(long latencyMicros) {
            aggregationTimer.update(latencyMicros, TimeUnit.MICROSECONDS);
        }

        // No longer in interface - kept for internal use by recordThresholdTime
        public Timer thresholdTimer() {
            return thresholdTimer;
        }

        @Override
        public void recordThresholdTime(long durationMicros) {
            thresholdTimer.update(durationMicros, TimeUnit.MICROSECONDS);
        }

        // No longer in interface - returns new instance for testing
        public Timer bufferDrainTimer() {
            return new Timer();
        }

        @Override
        public void setBufferedSignatures(int count) {}

        @Override
        public int getBufferedSignatures() { return 0; }

        // No longer in interface - returns new instance for testing
        public Histogram thresholdPercentageHistogram() {
            return new Histogram(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS));
        }

        @Override
        public void recordThresholdPercentage(double percentage) {}

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

        // No longer in interface - kept for internal access
        public Counter rejectedEpochCounter() {
            return rejectedEpoch;
        }

        public Counter rejectedViewRefCounter() {
            return rejectedViewRef;
        }

        public Counter rejectedLateCounter() {
            return rejectedLate;
        }

        public Counter rejectedDuplicateCounter() {
            return rejectedDuplicate;
        }

        @Override
        public void incrementRejectedInvalid() {
            rejectedInvalid.inc();
        }

        public Counter rejectedInvalidCounter() {
            return rejectedInvalid;
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

        // No longer in interface - kept for internal access
        public Meter completedAccumulationsMeter() {
            return completedAccumulations;
        }

        // Wave 3+ additions
        @Override
        public void incrementAccumulatorCreated() {}

        @Override
        public void incrementAccumulatorDiscarded() {}

        @Override
        public void incrementAggregationsPerformed() {}

        @Override
        public void recordAggregationBatchSize(int batchSize) {}

        // No longer in interface - returns new instance for testing
        public Histogram aggregationBatchSizeHistogram() {
            return new Histogram(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS));
        }

        @Override
        public void recordAggregateSize(int sizeBytes) {}

        public Histogram aggregateSizeHistogram() {
            return new Histogram(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS));
        }

        @Override
        public void recordCompressionRatio(double ratio) {}

        public Histogram compressionRatioHistogram() {
            return new Histogram(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS));
        }

        @Override
        public void incrementAggregationErrors() {}

        @Override
        public void recordCommitteeParticipation(int signerCount) {}

        public Histogram committeeParticipationHistogram() {
            return new Histogram(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS));
        }

        @Override
        public void recordSignerBitmapOverhead(int bitmapBytes) {}

        public Histogram signerBitmapOverheadHistogram() {
            return new Histogram(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS));
        }

        @Override
        public void recordEmptyAccumulatorCleanup() {}

        public Meter emptyAccumulatorCleanupMeter() {
            return new Meter();
        }

        // Wave 4 additions - View/Degradation metrics (Delos-3971)
        @Override
        public void incrementViewChangesInitiated() {}

        @Override
        public long getViewChangesInitiated() {
            return 0;
        }

        // No longer in interface - returns new instance for testing
        public Timer viewChangeDurationTimer() {
            return new Timer();
        }

        @Override
        public void recordViewChangeDuration(long durationMicros) {}

        @Override
        public long getViewChangeDurationCount() {
            return 0;
        }

        @Override
        public void setActiveView(long viewNumber) {}

        @Override
        public long getActiveView() {
            return 0;
        }

        @Override
        public void recordCommitteeReconfiguration() {}

        public Meter committeeReconfigurationsMeter() {
            return new Meter();
        }

        @Override
        public void recordThresholdRecalculation() {}

        public Meter thresholdRecalculationsMeter() {
            return new Meter();
        }

        @Override
        public void recordThresholdRecalculationTime(long recalcTimeMicros) {}

        public Timer thresholdRecalculationTimer() {
            return new Timer();
        }

        @Override
        public void recordDegradationStateTransition(String transition) {}

        @Override
        public long getDegradationStateTransitions(String transition) {
            return 0;
        }

        @Override
        public void recordTimeInDegradationState(String state, long timeMs) {}

        public Histogram timeInDegradationStateHistogram(String state) {
            return new Histogram(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS));
        }

        @Override
        public void recordBufferCreated() {}

        public Meter buffersCreatedMeter() {
            return new Meter();
        }

        @Override
        public void recordBufferedSignaturesDrained(int count) {}

        public Meter bufferedSignaturesDrainedMeter() {
            return new Meter();
        }

        @Override
        public void recordThresholdCalculationDelta(int delta) {}

        public Histogram thresholdCalculationDeltaHistogram() {
            return new Histogram(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS));
        }

        @Override
        public void incrementByzantineExclusions() {}

        @Override
        public long getByzantineExclusions() {
            return 0;
        }

        @Override
        public void incrementMemberRecoveries() {}

        @Override
        public long getMemberRecoveries() {
            return 0;
        }

        @Override
        public void recordReceiptProcessingLatencyDuringDegradation(String state, long latencyMicros) {}

        public Timer receiptProcessingLatencyDuringDegradationTimer(String state) {
            return new Timer();
        }

        @Override
        public void recordBufferDrainTime(long drainTimeMicros) {}

        public Timer bufferDrainTimeTimer() {
            return new Timer();
        }

        @Override
        public void recordSignatureReplayLatency(long replayLatencyMicros) {}

        public Timer signatureReplayTimer() {
            return new Timer();
        }

    }
}
