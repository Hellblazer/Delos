/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.witness.metrics.BLSMetrics;
import org.junit.jupiter.api.Test;
import org.joou.ULong;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BLSReceiptAggregator instrumentation with BLSMetrics.
 * Validates that aggregation metrics are properly tracked.
 *
 * @author hal.hildebrand
 */
public class AggregatorInstrumentationTest {

    private static final DigestAlgorithm DIGEST_ALGO = DigestAlgorithm.DEFAULT;

    @Test
    public void testAggregationsPerformedTracked() {
        // Given
        var aggregationsCount = new AtomicInteger(0);
        var metrics = createMockMetrics(aggregationsCount, null, null, null, null, null, null);

        var event = createTestEvent();
        var aggregator = new BLSReceiptAggregator(java.time.Duration.ofMinutes(10), metrics);

        // When - reach threshold
        aggregator.accumulate(event, Identifier.NONE, 0, createMockSignature(), 3, 0L);
        aggregator.accumulate(event, Identifier.NONE, 1, createMockSignature(), 3, 0L);
        aggregator.accumulate(event, Identifier.NONE, 2, createMockSignature(), 3, 0L);

        // Then
        assertEquals(1, aggregationsCount.get(), "Should track aggregation performed");
    }

    @Test
    public void testAggregationBatchSizeTracked() {
        // Given
        var batchSize = new AtomicInteger(-1);
        var metrics = createMockMetrics(null, batchSize, null, null, null, null, null);

        var event = createTestEvent();
        var aggregator = new BLSReceiptAggregator(java.time.Duration.ofMinutes(10), metrics);

        // When - aggregate 5 signatures
        for (int i = 0; i < 5; i++) {
            aggregator.accumulate(event, Identifier.NONE,i, createMockSignature(), 5, 0L);
        }

        // Then
        assertEquals(5, batchSize.get(), "Should track batch size");
    }

    @Test
    public void testAggregateSizeTracked() {
        // Given
        var aggregateSize = new AtomicInteger(-1);
        var metrics = createMockMetrics(null, null, aggregateSize, null, null, null, null);

        var event = createTestEvent();
        var aggregator = new BLSReceiptAggregator(java.time.Duration.ofMinutes(10), metrics);

        // When - reach threshold
        aggregator.accumulate(event, Identifier.NONE, 0, createMockSignature(), 3, 0L);
        aggregator.accumulate(event, Identifier.NONE, 1, createMockSignature(), 3, 0L);
        aggregator.accumulate(event, Identifier.NONE, 2, createMockSignature(), 3, 0L);

        // Then
        assertEquals(48, aggregateSize.get(), "Should track aggregate size (48 bytes for BLS12-381)");
    }

    @Test
    public void testCompressionRatioTracked() {
        // Given
        var compressionRatio = new AtomicReference<Double>();
        var metrics = createMockMetrics(null, null, null, compressionRatio, null, null, null);

        var event = createTestEvent();
        var aggregator = new BLSReceiptAggregator(java.time.Duration.ofMinutes(10), metrics);

        // When - aggregate 5 signatures
        for (int i = 0; i < 5; i++) {
            aggregator.accumulate(event, Identifier.NONE,i, createMockSignature(), 5, 0L);
        }

        // Then
        assertNotNull(compressionRatio.get(), "Should record compression ratio");
        // 5 signatures * 48 bytes = 240 bytes individual
        // 48 bytes aggregate + bitmap overhead
        // Ratio should be > 1.0 (good compression)
        assertTrue(compressionRatio.get() > 1.0, "Compression ratio should be > 1.0");
    }

    @Test
    public void testCommitteeParticipationTracked() {
        // Given
        var participation = new AtomicInteger(-1);
        var metrics = createMockMetrics(null, null, null, null, participation, null, null);

        var event = createTestEvent();
        var aggregator = new BLSReceiptAggregator(java.time.Duration.ofMinutes(10), metrics);

        // When - 7 out of 10 members sign
        for (int i = 0; i < 7; i++) {
            aggregator.accumulate(event, Identifier.NONE,i, createMockSignature(), 10, 0L);
        }

        // Then
        assertEquals(7, participation.get(), "Should track committee participation");
    }

    @Test
    public void testSignerBitmapOverheadTracked() {
        // Given
        var bitmapOverhead = new AtomicInteger(-1);
        var metrics = createMockMetrics(null, null, null, null, null, bitmapOverhead, null);

        var event = createTestEvent();
        var aggregator = new BLSReceiptAggregator(java.time.Duration.ofMinutes(10), metrics);

        // When - reach threshold
        aggregator.accumulate(event, Identifier.NONE, 0, createMockSignature(), 3, 0L);
        aggregator.accumulate(event, Identifier.NONE, 1, createMockSignature(), 3, 0L);
        aggregator.accumulate(event, Identifier.NONE, 2, createMockSignature(), 3, 0L);

        // Then
        assertTrue(bitmapOverhead.get() > 0, "Should track bitmap overhead");
    }

    @Test
    public void testMultipleAggregationsTracked() {
        // Given
        var aggregationsCount = new AtomicInteger(0);
        var metrics = createMockMetrics(aggregationsCount, null, null, null, null, null, null);

        var aggregator = new BLSReceiptAggregator(java.time.Duration.ofMinutes(10), metrics);

        // When - create 3 aggregations for different events
        for (int i = 0; i < 3; i++) {
            var event = new EventCoordinates(Identifier.NONE, ULong.valueOf(i), createTestDigest(), "test");
            aggregator.accumulate(event, Identifier.NONE, 0, createMockSignature(), 2, 0L);
            aggregator.accumulate(event, Identifier.NONE, 1, createMockSignature(), 2, 0L);
        }

        // Then
        assertEquals(3, aggregationsCount.get(), "Should track all aggregations");
    }

    @Test
    public void testLargeCommitteeCompression() {
        // Given
        var batchSize = new AtomicInteger(-1);
        var compressionRatio = new AtomicReference<Double>();
        var bitmapOverhead = new AtomicInteger(-1);
        var metrics = createMockMetrics(null, batchSize, null, compressionRatio, null, bitmapOverhead, null);

        var event = createTestEvent();
        var aggregator = new BLSReceiptAggregator(java.time.Duration.ofMinutes(10), metrics);

        // When - aggregate 20 signatures
        for (int i = 0; i < 20; i++) {
            aggregator.accumulate(event, Identifier.NONE,i, createMockSignature(), 20, 0L);
        }

        // Then
        assertEquals(20, batchSize.get());
        assertTrue(bitmapOverhead.get() > 0);
        // 20 * 48 = 960 bytes individual
        // 48 bytes aggregate + bitmap
        // Should achieve significant compression
        assertTrue(compressionRatio.get() > 10.0, "Large committee should achieve > 10x compression");
    }

    @Test
    public void testMinimalCommitteeMetrics() {
        // Given
        var batchSize = new AtomicInteger(-1);
        var participation = new AtomicInteger(-1);
        var compressionRatio = new AtomicReference<Double>();
        var metrics = createMockMetrics(null, batchSize, null, compressionRatio, participation, null, null);

        var event = createTestEvent();
        var aggregator = new BLSReceiptAggregator(java.time.Duration.ofMinutes(10), metrics);

        // When - minimal committee (1 member)
        aggregator.accumulate(event, Identifier.NONE,0, createMockSignature(), 1, 0L);

        // Then
        assertEquals(1, batchSize.get());
        assertEquals(1, participation.get());
        assertNotNull(compressionRatio.get());
        // 1 signature = 48 bytes
        // Aggregate = 48 + bitmap overhead
        // Compression ratio should be < 1 (no compression benefit)
        assertTrue(compressionRatio.get() < 1.0, "Single signature should have no compression benefit");
    }

    @Test
    public void testConcurrentAggregationTracking() {
        // Given
        var aggregationsCount = new AtomicInteger(0);
        var metrics = createMockMetrics(aggregationsCount, null, null, null, null, null, null);

        var aggregator = new BLSReceiptAggregator(java.time.Duration.ofMinutes(10), metrics);

        // When - concurrent aggregations
        var threads = new Thread[10];
        for (int i = 0; i < 10; i++) {
            final int eventIndex = i;
            threads[i] = new Thread(() -> {
                var event = new EventCoordinates(Identifier.NONE, ULong.valueOf(eventIndex), createTestDigest(), "test");
                aggregator.accumulate(event, Identifier.NONE, 0, createMockSignature(), 2, 0L);
                aggregator.accumulate(event, Identifier.NONE, 1, createMockSignature(), 2, 0L);
            });
        }

        for (var thread : threads) {
            thread.start();
        }

        for (var thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                fail("Interrupted");
            }
        }

        // Then
        assertEquals(10, aggregationsCount.get(), "Should track all concurrent aggregations");
    }

    @Test
    public void testMetricsNullSafety() {
        // Given - no metrics
        var event = createTestEvent();
        var aggregator = new BLSReceiptAggregator(java.time.Duration.ofMinutes(10), null);

        // When - aggregate without metrics
        var result1 = aggregator.accumulate(event, Identifier.NONE,0, createMockSignature(), 3, 0L);
        var result2 = aggregator.accumulate(event, Identifier.NONE,1, createMockSignature(), 3, 0L);
        var result3 = aggregator.accumulate(event, Identifier.NONE,2, createMockSignature(), 3, 0L);

        // Then - should work without NPE
        assertNotNull(result1);
        assertNotNull(result2);
        assertTrue(result3.isThresholdMet());

        var aggregate = aggregator.getAggregate(event);
        assertTrue(aggregate.isPresent(), "Should create aggregate without metrics");
    }

    @Test
    public void testAggregationErrorsNotCounted() {
        // Given
        var errors = new AtomicInteger(0);
        var metrics = createMockMetrics(null, null, null, null, null, null, errors);

        var event = createTestEvent();
        var aggregator = new BLSReceiptAggregator(java.time.Duration.ofMinutes(10), metrics);

        // When - successful aggregation (no errors)
        aggregator.accumulate(event, Identifier.NONE, 0, createMockSignature(), 3, 0L);
        aggregator.accumulate(event, Identifier.NONE, 1, createMockSignature(), 3, 0L);
        aggregator.accumulate(event, Identifier.NONE, 2, createMockSignature(), 3, 0L);

        // Then
        assertEquals(0, errors.get(), "Should not count errors for successful aggregation");
    }

    // Helper methods

    private BLSMetrics createMockMetrics(
        AtomicInteger aggregationsPerformed,
        AtomicInteger batchSize,
        AtomicInteger aggregateSize,
        AtomicReference<Double> compressionRatio,
        AtomicInteger committeeParticipation,
        AtomicInteger bitmapOverhead,
        AtomicInteger aggregationErrors
    ) {
        return new BLSMetrics() {
            @Override
            public void recordReceiptLatency(long latencyMicros) {}

            @Override
            public void incrementRejectedEpoch() {}

            @Override
            public void incrementRejectedViewRef() {}

            @Override
            public void incrementRejectedLate() {}

            @Override
            public void incrementRejectedDuplicate() {}

            @Override
            public void setActiveAccumulators(int count) {}

            @Override
            public void recordCompletedAccumulation() {}

            @Override
            public void incrementAccumulatorCreated() {}

            @Override
            public void incrementAccumulatorDiscarded() {}

            @Override
            public void incrementAggregationsPerformed() {
                if (aggregationsPerformed != null) aggregationsPerformed.incrementAndGet();
            }

            @Override
            public void recordAggregationBatchSize(int size) {
                if (batchSize != null) batchSize.set(size);
            }

            @Override
            public void recordAggregateSize(int sizeBytes) {
                if (aggregateSize != null) aggregateSize.set(sizeBytes);
            }

            @Override
            public void recordCompressionRatio(double ratio) {
                if (compressionRatio != null) compressionRatio.set(ratio);
            }

            @Override
            public void incrementAggregationErrors() {
                if (aggregationErrors != null) aggregationErrors.incrementAndGet();
            }

            @Override
            public void recordCommitteeParticipation(int signerCount) {
                if (committeeParticipation != null) committeeParticipation.set(signerCount);
            }

            @Override
            public void recordSignerBitmapOverhead(int bitmapBytes) {
                if (bitmapOverhead != null) bitmapOverhead.set(bitmapBytes);
            }

            @Override
            public com.codahale.metrics.Histogram signerBitmapOverheadHistogram() {
                return new com.codahale.metrics.Histogram(new com.codahale.metrics.SlidingTimeWindowArrayReservoir(60, java.util.concurrent.TimeUnit.SECONDS));
            }

            @Override
            public com.codahale.metrics.Meter emptyAccumulatorCleanupMeter() {
                return new com.codahale.metrics.Meter();
            }

            // Stub methods for all other BLSMetrics abstract methods
            // These are implemented as no-ops since AggregatorInstrumentationTest
            // only tests specific metrics via the targeted callbacks above
            @Override
            public void register(com.codahale.metrics.MetricRegistry registry) {}

            @Override
            public void reset() {}

            @Override
            public java.util.Map<String, com.codahale.metrics.Metric> getMetrics() {
                return java.util.Map.of();
            }

            @Override
            public void incrementSignaturesReceived() {}

            @Override
            public void incrementSignaturesAccepted() {}

            @Override
            public com.codahale.metrics.Timer signatureVerifyTimer() {
                return new com.codahale.metrics.Timer();
            }

            @Override
            public void recordVerifyLatency(long latencyMicros) {}

            @Override
            public long getVerifyCount() {
                return 0;
            }

            @Override
            public com.codahale.metrics.Timer aggregationTimer() {
                return new com.codahale.metrics.Timer();
            }

            @Override
            public void recordAggregationLatency(long latencyMicros) {}

            @Override
            public com.codahale.metrics.Timer thresholdTimer() {
                return new com.codahale.metrics.Timer();
            }

            @Override
            public void recordThresholdTime(long durationMicros) {}

            @Override
            public com.codahale.metrics.Counter rejectedEpochCounter() {
                return new com.codahale.metrics.Counter();
            }

            @Override
            public com.codahale.metrics.Counter rejectedViewRefCounter() {
                return new com.codahale.metrics.Counter();
            }

            @Override
            public com.codahale.metrics.Counter rejectedLateCounter() {
                return new com.codahale.metrics.Counter();
            }

            @Override
            public com.codahale.metrics.Counter rejectedDuplicateCounter() {
                return new com.codahale.metrics.Counter();
            }

            @Override
            public int getActiveAccumulators() {
                return 0;
            }

            @Override
            public com.codahale.metrics.Meter completedAccumulationsMeter() {
                return new com.codahale.metrics.Meter();
            }

            @Override
            public long getReceiptCount() {
                return 0;
            }

            @Override
            public com.codahale.metrics.Histogram thresholdPercentageHistogram() {
                return new com.codahale.metrics.Histogram(new com.codahale.metrics.SlidingTimeWindowArrayReservoir(60, java.util.concurrent.TimeUnit.SECONDS));
            }

            @Override
            public com.codahale.metrics.Timer bufferDrainTimer() {
                return new com.codahale.metrics.Timer();
            }

            @Override
            public void incrementRejectedInvalid() {}

            @Override
            public com.codahale.metrics.Counter rejectedInvalidCounter() {
                return new com.codahale.metrics.Counter();
            }

            @Override
            public com.codahale.metrics.Histogram aggregationBatchSizeHistogram() {
                return new com.codahale.metrics.Histogram(new com.codahale.metrics.SlidingTimeWindowArrayReservoir(60, java.util.concurrent.TimeUnit.SECONDS));
            }

            @Override
            public com.codahale.metrics.Histogram aggregateSizeHistogram() {
                return new com.codahale.metrics.Histogram(new com.codahale.metrics.SlidingTimeWindowArrayReservoir(60, java.util.concurrent.TimeUnit.SECONDS));
            }

            @Override
            public com.codahale.metrics.Histogram compressionRatioHistogram() {
                return new com.codahale.metrics.Histogram(new com.codahale.metrics.SlidingTimeWindowArrayReservoir(60, java.util.concurrent.TimeUnit.SECONDS));
            }

            @Override
            public com.codahale.metrics.Histogram committeeParticipationHistogram() {
                return new com.codahale.metrics.Histogram(new com.codahale.metrics.SlidingTimeWindowArrayReservoir(60, java.util.concurrent.TimeUnit.SECONDS));
            }

            @Override
            public com.codahale.metrics.Histogram signerBitmapOverheadHistogram() {
                return new com.codahale.metrics.Histogram(new com.codahale.metrics.SlidingTimeWindowArrayReservoir(60, java.util.concurrent.TimeUnit.SECONDS));
            }

            @Override
            public com.codahale.metrics.Meter emptyAccumulatorCleanupMeter() {
                return new com.codahale.metrics.Meter();
            }

            @Override
            public void incrementViewChangesInitiated() {}

            @Override
            public long getViewChangesInitiated() {
                return 0;
            }

            @Override
            public com.codahale.metrics.Timer viewChangeDurationTimer() {
                return new com.codahale.metrics.Timer();
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

            @Override
            public com.codahale.metrics.Meter committeeReconfigurationsMeter() {
                return new com.codahale.metrics.Meter();
            }

            @Override
            public void recordThresholdRecalculation() {}

            @Override
            public com.codahale.metrics.Meter thresholdRecalculationsMeter() {
                return new com.codahale.metrics.Meter();
            }

            @Override
            public void recordDegradationStateTransition(String transition) {}

            @Override
            public long getDegradationStateTransitions(String transition) {
                return 0;
            }

            @Override
            public void recordTimeInDegradationState(String state, long timeMs) {}

            @Override
            public com.codahale.metrics.Histogram timeInDegradationStateHistogram(String state) {
                return new com.codahale.metrics.Histogram(new com.codahale.metrics.SlidingTimeWindowArrayReservoir(60, java.util.concurrent.TimeUnit.SECONDS));
            }

            @Override
            public void recordBufferCreated() {}

            @Override
            public com.codahale.metrics.Meter buffersCreatedMeter() {
                return new com.codahale.metrics.Meter();
            }

            @Override
            public int getBufferedSignatures() {
                return 0;
            }

            @Override
            public void recordBufferedSignaturesDrained(int count) {}

            @Override
            public com.codahale.metrics.Meter bufferedSignaturesDrainedMeter() {
                return new com.codahale.metrics.Meter();
            }

            @Override
            public void recordThresholdCalculationDelta(int delta) {}

            @Override
            public com.codahale.metrics.Histogram thresholdCalculationDeltaHistogram() {
                return new com.codahale.metrics.Histogram(new com.codahale.metrics.SlidingTimeWindowArrayReservoir(60, java.util.concurrent.TimeUnit.SECONDS));
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

            @Override
            public com.codahale.metrics.Timer receiptProcessingLatencyDuringDegradationTimer(String state) {
                return new com.codahale.metrics.Timer();
            }

            @Override
            public void recordBufferDrainTime(long drainTimeMicros) {}

            @Override
            public void recordSignatureReplayLatency(long replayLatencyMicros) {}

            @Override
            public com.codahale.metrics.Timer signatureReplayTimer() {
                return new com.codahale.metrics.Timer();
            }

            @Override
            public com.codahale.metrics.Timer thresholdRecalculationTimer() {
                return new com.codahale.metrics.Timer();
            }

            @Override
            public void recordThresholdRecalculationTime(long recalcTimeMicros) {}
        };
    }

    private EventCoordinates createTestEvent() {
        return new EventCoordinates(Identifier.NONE, ULong.valueOf(1), createTestDigest(), "test");
    }

    private Digest createTestDigest() {
        return DIGEST_ALGO.digest(("test" + System.nanoTime()).getBytes());
    }

    private BLSSignature createMockSignature() {
        // Create a mock signature (48 bytes for BLS12-381 G1)
        var bytes = new byte[48];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
        }
        return new BLSSignature(bytes);
    }
}
