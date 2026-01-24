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
import com.hellblazer.delos.cryptography.bls.BLSKeyPair;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.cryptography.bls.BLSTestFixtures;
import com.hellblazer.delos.cryptography.bls.impl.TekuBLSProvider;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.metrics.BLSMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.joou.ULong;

import java.util.ArrayList;
import java.util.List;
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
    private List<BLSKeyPair> testKeyPairs;
    private TekuBLSProvider blsProvider;

    @BeforeEach
    void setUp() {
        blsProvider = TekuBLSProvider.getInstance();

        // Generate a committee of 50 key pairs for testing
        var random = BLSTestFixtures.deterministicRandom(0x123456);
        testKeyPairs = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            testKeyPairs.add(BLSKeyPair.generate(random, blsProvider));
        }
    }

    @Test
    public void testAggregationsPerformedTracked() {
        // Given
        var aggregationsCount = new AtomicInteger(0);
        var metrics = createMockMetrics(aggregationsCount, null, null, null, null, null, null);

        var event = createTestEvent();
        var aggregator = new BLSReceiptAggregator(java.time.Duration.ofMinutes(10), metrics);

        // When - reach threshold with unique members
        aggregator.accumulate(event, createTestMember(0), 0, createSignature(0), 3, 0L);
        aggregator.accumulate(event, createTestMember(1), 1, createSignature(1), 3, 0L);
        aggregator.accumulate(event, createTestMember(2), 2, createSignature(2), 3, 0L);

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

        // When - aggregate 5 signatures with unique members
        for (int i = 0; i < 5; i++) {
            aggregator.accumulate(event, createTestMember(i), i, createSignature(i), 5, 0L);
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

        // When - reach threshold with unique members
        aggregator.accumulate(event, createTestMember(0), 0, createSignature(0), 3, 0L);
        aggregator.accumulate(event, createTestMember(1), 1, createSignature(1), 3, 0L);
        aggregator.accumulate(event, createTestMember(2), 2, createSignature(2), 3, 0L);

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

        // When - aggregate 5 signatures with unique members
        for (int i = 0; i < 5; i++) {
            aggregator.accumulate(event, createTestMember(i), i, createSignature(i), 5, 0L);
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

        // When - 7 out of 7 members sign (threshold = 7)
        for (int i = 0; i < 7; i++) {
            aggregator.accumulate(event, createTestMember(i), i, createSignature(i), 7, 0L);
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

        // When - reach threshold with unique members
        aggregator.accumulate(event, createTestMember(0), 0, createSignature(0), 3, 0L);
        aggregator.accumulate(event, createTestMember(1), 1, createSignature(1), 3, 0L);
        aggregator.accumulate(event, createTestMember(2), 2, createSignature(2), 3, 0L);

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
            aggregator.accumulate(event, createTestMember(0), 0, createSignature(i), 2, 0L);
            aggregator.accumulate(event, createTestMember(1), 1, createSignature(i+10), 2, 0L);
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
            aggregator.accumulate(event, createTestMember(i), i, createSignature(i), 20, 0L);
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
        aggregator.accumulate(event, createTestMember(0), 0, createSignature(0), 1, 0L);

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
            final int threadIndex = i;
            threads[i] = new Thread(() -> {
                var event = new EventCoordinates(Identifier.NONE, ULong.valueOf(eventIndex), createTestDigest(), "test");
                aggregator.accumulate(event, createTestMember(0), 0, createSignature(threadIndex), 2, 0L);
                aggregator.accumulate(event, createTestMember(1), 1, createSignature(threadIndex+10), 2, 0L);
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
        var result1 = aggregator.accumulate(event, createTestMember(0), 0, createSignature(0), 3, 0L);
        var result2 = aggregator.accumulate(event, createTestMember(1), 1, createSignature(1), 3, 0L);
        var result3 = aggregator.accumulate(event, createTestMember(2), 2, createSignature(2), 3, 0L);

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
        aggregator.accumulate(event, createTestMember(0), 0, createSignature(0), 3, 0L);
        aggregator.accumulate(event, createTestMember(1), 1, createSignature(1), 3, 0L);
        aggregator.accumulate(event, createTestMember(2), 2, createSignature(2), 3, 0L);

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
            public void recordEmptyAccumulatorCleanup() {}

            @Override
            public void recordTimeToThreshold(long durationMicros) {}

            @Override
            public void recordThresholdPercentage(double percentage) {}

            @Override
            public void setBufferedSignatures(int count) {}

            @Override
            public void recordBufferDrainLatency(long latencyMicros) {}
        };
    }

    private EventCoordinates createTestEvent() {
        return new EventCoordinates(Identifier.NONE, ULong.valueOf(1), createTestDigest(), "test");
    }

    private Digest createTestDigest() {
        return DIGEST_ALGO.digest(("test" + System.nanoTime()).getBytes());
    }

    private BLSSignature createSignature(int index) {
        // Sign a test message with the key pair for this index
        var keyPair = testKeyPairs.get(index % testKeyPairs.size());
        var message = DIGEST_ALGO.digest(("test-message-" + index).getBytes()).getBytes();
        return keyPair.sign(message);
    }

    private Identifier createTestMember(int index) {
        // Create unique test identifier by including index in the digest
        return new SelfAddressingIdentifier(DIGEST_ALGO.digest(("member-" + index).getBytes()));
    }
}
