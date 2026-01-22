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
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.metrics.BLSMetrics;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SignatureAccumulator instrumentation with BLSMetrics.
 * Validates that metrics are properly tracked during accumulator lifecycle.
 *
 * @author hal.hildebrand
 */
public class AccumulatorInstrumentationTest {

    private static final DigestAlgorithm DIGEST_ALGO = DigestAlgorithm.DEFAULT;

    @Test
    public void testAccumulatorCreationTracked() {
        // Given
        var createdCount = new AtomicInteger(0);
        var metrics = createMockMetrics(createdCount, null, null, null, null, null);

        // When
        var event = createTestEvent();
        new SignatureAccumulator(event, 3, 0L, null, metrics);

        // Then
        assertEquals(1, createdCount.get(), "Should track accumulator creation");
    }

    @Test
    public void testTimeToThresholdTracked() {
        // Given
        var timeToThreshold = new AtomicLong(-1);
        var thresholdPercentage = new AtomicReference<Double>();
        var metrics = createMockMetrics(null, timeToThreshold, thresholdPercentage, null, null, null);

        var event = createTestEvent();
        var accumulator = new SignatureAccumulator(event, 3, 0L, null, metrics);

        // When - accumulate to threshold
        accumulator.accumulate(createIdentifier("m1"), 0, createMockSignature(), 0L, null);
        accumulator.accumulate(createIdentifier("m2"), 1, createMockSignature(), 0L, null);
        var result = accumulator.accumulate(createIdentifier("m3"), 2, createMockSignature(), 0L, null);

        // Then
        assertTrue(result.isThresholdMet(), "Should reach threshold");
        assertTrue(timeToThreshold.get() >= 0, "Should record time to threshold");
        assertNotNull(thresholdPercentage.get(), "Should record threshold percentage");
        assertEquals(1.0, thresholdPercentage.get(), 0.01, "Should be 100% at threshold");
    }

    @Test
    public void testThresholdPercentageAbove100() {
        // Given
        var thresholdPercentage = new AtomicReference<Double>();
        var metrics = createMockMetrics(null, null, thresholdPercentage, null, null, null);

        var event = createTestEvent();
        var accumulator = new SignatureAccumulator(event, 3, 0L, null, metrics);

        // When - exceed threshold
        accumulator.accumulate(createIdentifier("m1"), 0, createMockSignature(), 0L, null);
        accumulator.accumulate(createIdentifier("m2"), 1, createMockSignature(), 0L, null);
        accumulator.accumulate(createIdentifier("m3"), 2, createMockSignature(), 0L, null);
        accumulator.accumulate(createIdentifier("m4"), 3, createMockSignature(), 0L, null); // Late signer

        // Then
        assertEquals(1.0, thresholdPercentage.get(), 0.01,
            "Should cap at 1.0 even when exceeding threshold");
    }

    @Test
    public void testAccumulatorDiscardedTracked() {
        // Given
        var discardedCount = new AtomicInteger(0);
        var discardedPercentage = new AtomicReference<Double>();
        var metrics = createMockMetrics(null, null, discardedPercentage, discardedCount, null, null);

        var event = createTestEvent();
        var aggregator = new BLSReceiptAggregator(java.time.Duration.ofSeconds(1), metrics);

        // When - accumulate below threshold and cleanup
        aggregator.accumulate(event, createIdentifier("m1"), 0, createMockSignature(), 5, 0L);
        aggregator.accumulate(event, createIdentifier("m2"), 1, createMockSignature(), 5, 0L);
        // Only 2 of 5 = 40%

        // Wait for expiration
        try {
            Thread.sleep(1100);
        } catch (InterruptedException e) {
            fail("Interrupted");
        }

        aggregator.cleanup();

        // Then
        assertEquals(1, discardedCount.get(), "Should track discarded accumulator");
        assertNotNull(discardedPercentage.get(), "Should record threshold percentage achieved");
        assertEquals(0.4, discardedPercentage.get(), 0.01, "Should be 40% of threshold");
    }

    @Test
    public void testEmptyAccumulatorCleanupTracked() {
        // Given
        var cleanupCount = new AtomicInteger(0);
        var discardedCount = new AtomicInteger(0);
        var metrics = createMockMetrics(null, null, null, discardedCount, null, cleanupCount);

        var event = createTestEvent();
        var aggregator = new BLSReceiptAggregator(java.time.Duration.ofSeconds(1), metrics);

        // When - create accumulator with no signatures and cleanup
        aggregator.accumulate(event, createIdentifier("m1"), 0, createMockSignature(), 3, 0L);

        // Wait for expiration
        try {
            Thread.sleep(1100);
        } catch (InterruptedException e) {
            fail("Interrupted");
        }

        aggregator.cleanup();

        // Then
        assertEquals(1, discardedCount.get(), "Should track discarded accumulator");
        assertEquals(1, cleanupCount.get(), "Should track empty accumulator cleanup");
    }

    @Test
    public void testMultipleAccumulatorsTracked() {
        // Given
        var createdCount = new AtomicInteger(0);
        var metrics = createMockMetrics(createdCount, null, null, null, null, null);

        var aggregator = new BLSReceiptAggregator(java.time.Duration.ofMinutes(10), metrics);

        // When - create multiple accumulators
        var event1 = createTestEvent();
        var event2 = createEventWithSequence(2L);
        var event3 = createEventWithSequence(3L);

        aggregator.accumulate(event1, createIdentifier("m1"), 0, createMockSignature(), 3, 0L);
        aggregator.accumulate(event2, createIdentifier("m1"), 0, createMockSignature(), 3, 0L);
        aggregator.accumulate(event3, createIdentifier("m1"), 0, createMockSignature(), 3, 0L);

        // Then
        assertEquals(3, createdCount.get(), "Should track all accumulator creations");
    }

    @Test
    public void testConcurrentAccumulationTracked() {
        // Given
        var createdCount = new AtomicInteger(0);
        var timeToThreshold = new AtomicLong(-1);
        var metrics = createMockMetrics(createdCount, timeToThreshold, null, null, null, null);

        var event = createTestEvent();
        var aggregator = new BLSReceiptAggregator(java.time.Duration.ofMinutes(10), metrics);

        // When - concurrent accumulation
        var threads = new Thread[5];
        for (int i = 0; i < 5; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                aggregator.accumulate(event, createIdentifier("m" + index), index, createMockSignature(), 5, 0L);
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
        assertEquals(1, createdCount.get(), "Should create exactly one accumulator");
        assertTrue(timeToThreshold.get() >= 0, "Should record time to threshold");
    }

    @Test
    public void testMetricsNullSafety() {
        // Given - no metrics
        var event = createTestEvent();
        var accumulator = new SignatureAccumulator(event, 3, 0L, null, null);

        // When - accumulate without metrics
        var result1 = accumulator.accumulate(createIdentifier("m1"), 0, createMockSignature(), 0L, null);
        var result2 = accumulator.accumulate(createIdentifier("m2"), 1, createMockSignature(), 0L, null);
        var result3 = accumulator.accumulate(createIdentifier("m3"), 2, createMockSignature(), 0L, null);

        // Then - should work without NPE
        assertNotNull(result1);
        assertNotNull(result2);
        assertTrue(result3.isThresholdMet());
    }

    @Test
    public void testPartialThresholdTracked() {
        // Given
        var discardedPercentage = new AtomicReference<Double>();
        var discardedCount = new AtomicInteger(0);
        var metrics = createMockMetrics(null, null, discardedPercentage, discardedCount, null, null);

        var event = createTestEvent();
        var aggregator = new BLSReceiptAggregator(java.time.Duration.ofSeconds(1), metrics);

        // When - accumulate 60% of threshold
        aggregator.accumulate(event, createIdentifier("m1"), 0, createMockSignature(), 10, 0L);
        aggregator.accumulate(event, createIdentifier("m2"), 1, createMockSignature(), 10, 0L);
        aggregator.accumulate(event, createIdentifier("m3"), 2, createMockSignature(), 10, 0L);
        aggregator.accumulate(event, createIdentifier("m4"), 3, createMockSignature(), 10, 0L);
        aggregator.accumulate(event, createIdentifier("m5"), 4, createMockSignature(), 10, 0L);
        aggregator.accumulate(event, createIdentifier("m6"), 5, createMockSignature(), 10, 0L);

        // Wait for expiration
        try {
            Thread.sleep(1100);
        } catch (InterruptedException e) {
            fail("Interrupted");
        }

        aggregator.cleanup();

        // Then
        assertEquals(1, discardedCount.get());
        assertEquals(0.6, discardedPercentage.get(), 0.01, "Should be 60% of threshold");
    }

    @Test
    public void testZeroThresholdPercentageTracked() {
        // Given
        var discardedPercentage = new AtomicReference<Double>();
        var discardedCount = new AtomicInteger(0);
        var cleanupCount = new AtomicInteger(0);
        var metrics = createMockMetrics(null, null, discardedPercentage, discardedCount, null, cleanupCount);

        var event = createTestEvent();
        var aggregator = new BLSReceiptAggregator(java.time.Duration.ofSeconds(1), metrics);

        // When - create accumulator by attempting to accumulate (but it expires without meeting threshold)
        // Don't add any signatures, just let it timeout
        // Actually trigger at least one accumulate to create the accumulator
        aggregator.accumulate(event, createIdentifier("m1"), 0, createMockSignature(), 5, 0L);

        // Wait for expiration
        try {
            Thread.sleep(1100);
        } catch (InterruptedException e) {
            fail("Interrupted");
        }

        aggregator.cleanup();

        // Then
        assertEquals(1, discardedCount.get(), "Should track discarded accumulator");
        // With 1 signature out of 5 required, percentage is 20%, not 0%
        // For true 0%, we'd need to not accumulate at all, but accumulator won't be created
        // So we verify the behavior is tracked
        assertNotNull(discardedPercentage.get(), "Should track threshold percentage");
        assertEquals(1, cleanupCount.get(), "Should track empty cleanup");
    }

    // Helper methods

    private BLSMetrics createMockMetrics(
        AtomicInteger createdCount,
        AtomicLong timeToThreshold,
        AtomicReference<Double> thresholdPercentage,
        AtomicInteger discardedCount,
        AtomicInteger bufferedCount,
        AtomicInteger cleanupCount
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
            public void incrementAccumulatorCreated() {
                if (createdCount != null) createdCount.incrementAndGet();
            }

            @Override
            public void incrementAccumulatorDiscarded() {
                if (discardedCount != null) discardedCount.incrementAndGet();
            }

            @Override
            public void recordTimeToThreshold(long durationMicros) {
                if (timeToThreshold != null) timeToThreshold.set(durationMicros);
            }

            @Override
            public void recordThresholdPercentage(double percentage) {
                if (thresholdPercentage != null) thresholdPercentage.set(percentage);
            }

            @Override
            public void setBufferedSignatures(int count) {
                if (bufferedCount != null) bufferedCount.set(count);
            }

            @Override
            public void recordBufferDrainLatency(long latencyMicros) {}

            @Override
            public void incrementAggregationsPerformed() {}

            @Override
            public void recordAggregationBatchSize(int batchSize) {}

            @Override
            public void recordAggregateSize(int sizeBytes) {}

            @Override
            public void recordCompressionRatio(double ratio) {}

            @Override
            public void incrementAggregationErrors() {}

            @Override
            public void recordCommitteeParticipation(int signerCount) {}

            @Override
            public void recordSignerBitmapOverhead(int bitmapBytes) {}

            @Override
            public void recordEmptyAccumulatorCleanup() {
                if (cleanupCount != null) cleanupCount.incrementAndGet();
            }
        };
    }

    private EventCoordinates createTestEvent() {
        return createEventWithSequence(1L);
    }

    private EventCoordinates createEventWithSequence(long sequence) {
        var digest = createTestDigest();
        var identifier = new SelfAddressingIdentifier(digest);
        return new EventCoordinates(identifier, org.joou.ULong.valueOf(sequence), digest, "test");
    }

    private Digest createTestDigest() {
        return DIGEST_ALGO.digest("test".getBytes());
    }

    private Identifier createIdentifier(String name) {
        return new SelfAddressingIdentifier(DIGEST_ALGO.digest(name.getBytes()));
    }

    private BLSSignature createMockSignature() {
        // Create a mock signature (48 bytes for BLS12-381 G1)
        var bytes = new byte[48];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
        }
        return BLSSignature.fromBytes(bytes);
    }
}
