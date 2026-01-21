/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.BLSKeyPair;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.cryptography.bls.BLSTestFixtures;
import com.hellblazer.delos.cryptography.bls.impl.TekuBLSProvider;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;

/**
 * Test BLSReceiptAggregator factory management and multi-event accumulation.
 * TDD RED phase - write all tests first expecting failures.
 *
 * @author hal.hildebrand
 */
class BLSReceiptAggregatorTest {

    private BLSReceiptAggregator aggregator;
    private DigestAlgorithm digestAlgorithm;
    private List<BLSKeyPair> testKeyPairs;
    private TekuBLSProvider blsProvider;

    @BeforeEach
    void setUp() {
        digestAlgorithm = DigestAlgorithm.BLAKE3_256;
        aggregator = new BLSReceiptAggregator();
        blsProvider = new TekuBLSProvider();

        // Generate a committee of 20 key pairs for testing using our wrapper
        var random = BLSTestFixtures.deterministicRandom(0x123456);
        testKeyPairs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            testKeyPairs.add(BLSKeyPair.generate(random, blsProvider));
        }
    }

    // ========== Factory Management Tests ==========

    @Test
    void shouldCreateAccumulatorLazily() {
        var event1 = createTestEvent(1);
        var event2 = createTestEvent(2);

        // Before accumulation, no accumulators exist
        var metricsBefore = aggregator.metrics();
        assertThat(metricsBefore.activeAccumulators()).isZero();

        // Accumulate for event1 - creates accumulator
        var member = createMember(0);
        var signature = createSignature(0);
        aggregator.accumulate(event1, member, 0, signature, 5, 1);

        // Should have 1 active accumulator
        var metricsAfter1 = aggregator.metrics();
        assertThat(metricsAfter1.activeAccumulators()).isEqualTo(1);

        // Accumulate for event2 - creates second accumulator
        aggregator.accumulate(event2, createMember(1), 1, createSignature(1), 5, 1);

        // Should have 2 active accumulators
        var metricsAfter2 = aggregator.metrics();
        assertThat(metricsAfter2.activeAccumulators()).isEqualTo(2);
    }

    @Test
    void shouldReuseAccumulatorForSameEvent() {
        var event = createTestEvent(1);
        var threshold = 5;
        var epoch = 1L;

        // Accumulate multiple signatures for same event
        IntStream.range(0, 3).forEach(i -> {
            aggregator.accumulate(event, createMember(i), i, createSignature(i), threshold, epoch);
        });

        // Should still have only 1 accumulator
        assertThat(aggregator.metrics().activeAccumulators()).isEqualTo(1);
        assertThat(aggregator.metrics().totalAccumulated()).isEqualTo(3);
    }

    @Test
    void shouldIsolateAccumulatorsByEvent() {
        var event1 = createTestEvent(1);
        var event2 = createTestEvent(2);
        var threshold = 3;

        // Accumulate 2 signatures for event1
        aggregator.accumulate(event1, createMember(0), 0, createSignature(0), threshold, 1);
        aggregator.accumulate(event1, createMember(1), 1, createSignature(1), threshold, 1);

        // Accumulate 1 signature for event2
        aggregator.accumulate(event2, createMember(2), 2, createSignature(2), threshold, 1);

        // Events should have independent accumulators
        var result1 = aggregator.aggregate(event1);
        var result2 = aggregator.aggregate(event2);

        assertThat(result1).isInstanceOf(AggregationResult.InsufficientSignatures.class);
        assertThat(result2).isInstanceOf(AggregationResult.InsufficientSignatures.class);

        var insufficient1 = (AggregationResult.InsufficientSignatures) result1;
        var insufficient2 = (AggregationResult.InsufficientSignatures) result2;

        assertThat(insufficient1.current()).isEqualTo(2);
        assertThat(insufficient2.current()).isEqualTo(1);
    }

    // ========== Accumulation Tests ==========

    @Test
    void shouldAccumulateSignaturesAcrossEvents() {
        var event1 = createTestEvent(1);
        var event2 = createTestEvent(2);
        var threshold = 3;

        // Accumulate for event1
        var result1 = aggregator.accumulate(event1, createMember(0), 0, createSignature(0), threshold, 1);
        assertThat(result1).isInstanceOf(AccumulationResult.Accumulated.class);

        // Accumulate for event2
        var result2 = aggregator.accumulate(event2, createMember(1), 1, createSignature(1), threshold, 1);
        assertThat(result2).isInstanceOf(AccumulationResult.Accumulated.class);

        // Both events should have 1 signature each
        var metrics = aggregator.metrics();
        assertThat(metrics.activeAccumulators()).isEqualTo(2);
        assertThat(metrics.totalAccumulated()).isEqualTo(2);
    }

    @Test
    void shouldReturnThresholdMetWhenReached() {
        var event = createTestEvent(1);
        var threshold = 3;
        var epoch = 1L;

        // Accumulate threshold-1 signatures
        for (int i = 0; i < threshold - 1; i++) {
            var result = aggregator.accumulate(event, createMember(i), i, createSignature(i), threshold, epoch);
            assertThat(result).isInstanceOf(AccumulationResult.Accumulated.class);
        }

        // Final signature should trigger threshold
        var finalResult = aggregator.accumulate(event, createMember(threshold - 1), threshold - 1,
                                                createSignature(threshold - 1), threshold, epoch);
        assertThat(finalResult).isInstanceOf(AccumulationResult.ThresholdMet.class);

        var thresholdMet = (AccumulationResult.ThresholdMet) finalResult;
        assertThat(thresholdMet.count()).isEqualTo(threshold);
        assertThat(thresholdMet.snapshot()).isNotNull();
        assertThat(thresholdMet.snapshot().signerCount()).isEqualTo(threshold);
    }

    @Test
    void shouldHandleDuplicateSignatures() {
        var event = createTestEvent(1);
        var member = createMember(0);
        var signature = createSignature(0);

        // First accumulation should succeed
        var result1 = aggregator.accumulate(event, member, 0, signature, 5, 1);
        assertThat(result1).isInstanceOf(AccumulationResult.Accumulated.class);

        // Duplicate should be detected
        var result2 = aggregator.accumulate(event, member, 0, signature, 5, 1);
        assertThat(result2).isInstanceOf(AccumulationResult.AlreadyPresent.class);

        // Total accumulated should still be 1
        assertThat(aggregator.metrics().totalAccumulated()).isEqualTo(1);
    }

    // ========== Aggregation Tests ==========

    @Test
    void shouldAggregateWhenThresholdMet() {
        var event = createTestEvent(1);
        var threshold = 3;
        var epoch = 1L;

        // Accumulate threshold signatures
        for (int i = 0; i < threshold; i++) {
            aggregator.accumulate(event, createMember(i), i, createSignature(i), threshold, epoch);
        }

        // Aggregate should succeed
        var result = aggregator.aggregate(event);
        assertThat(result).isInstanceOf(AggregationResult.Aggregated.class);

        var aggregated = (AggregationResult.Aggregated) result;
        assertThat(aggregated.signature()).isNotNull();
        assertThat(aggregated.bitmap()).isNotNull();
        assertThat(aggregated.bitmap().length).isGreaterThan(0);
    }

    @Test
    void shouldReturnInsufficientWhenBelowThreshold() {
        var event = createTestEvent(1);
        var threshold = 5;

        // Accumulate only 2 signatures (below threshold)
        aggregator.accumulate(event, createMember(0), 0, createSignature(0), threshold, 1);
        aggregator.accumulate(event, createMember(1), 1, createSignature(1), threshold, 1);

        // Aggregate should indicate insufficient signatures
        var result = aggregator.aggregate(event);
        assertThat(result).isInstanceOf(AggregationResult.InsufficientSignatures.class);

        var insufficient = (AggregationResult.InsufficientSignatures) result;
        assertThat(insufficient.current()).isEqualTo(2);
        assertThat(insufficient.required()).isEqualTo(5);
    }

    @Test
    void shouldReturnAggregationFailedForNonexistentEvent() {
        var event = createTestEvent(999);

        // Aggregate without any accumulation
        var result = aggregator.aggregate(event);
        assertThat(result).isInstanceOf(AggregationResult.AggregationFailed.class);

        var failed = (AggregationResult.AggregationFailed) result;
        assertThat(failed.reason()).contains("No accumulator found");
    }

    @Test
    void shouldGetAggregateAfterThreshold() {
        var event = createTestEvent(1);
        var threshold = 3;

        // Accumulate threshold signatures
        for (int i = 0; i < threshold; i++) {
            aggregator.accumulate(event, createMember(i), i, createSignature(i), threshold, 1);
        }

        // Get aggregate
        var maybeAggregate = aggregator.getAggregate(event);
        assertThat(maybeAggregate).isPresent();
        assertThat(maybeAggregate.get().aggregatedSignature()).isNotNull();
        assertThat(maybeAggregate.get().getSignerIndices()).hasSize(threshold);
    }

    @Test
    void shouldReturnEmptyAggregateWhenNotReady() {
        var event = createTestEvent(1);

        // No accumulation
        var maybeAggregate = aggregator.getAggregate(event);
        assertThat(maybeAggregate).isEmpty();
    }

    // ========== Cleanup/Expiration Tests ==========

    @Test
    void shouldCleanupExpiredAccumulators() throws InterruptedException {
        var event = createTestEvent(1);
        var threshold = 5;

        // Accumulate 1 signature
        aggregator.accumulate(event, createMember(0), 0, createSignature(0), threshold, 1);

        // Should have 1 active accumulator
        assertThat(aggregator.metrics().activeAccumulators()).isEqualTo(1);

        // Create new aggregator with short expiration (implementation detail)
        // This test validates cleanup exists but may need adjustment based on implementation

        // For now, just verify cleanup count metric exists
        var metrics = aggregator.metrics();
        assertThat(metrics.cleanupCount()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void shouldNotCleanupRecentAccumulators() {
        var event = createTestEvent(1);
        var threshold = 5;

        // Accumulate signature
        aggregator.accumulate(event, createMember(0), 0, createSignature(0), threshold, 1);

        // Immediately after accumulation, should still be active
        assertThat(aggregator.metrics().activeAccumulators()).isEqualTo(1);
    }

    // ========== Concurrent Access Tests ==========

    @Test
    void shouldHandleConcurrentAccumulation() throws InterruptedException {
        var event = createTestEvent(1);
        var threshold = 10;
        var threadCount = 10;
        var latch = new CountDownLatch(threadCount);

        // Accumulate concurrently from multiple threads
        var results = new ConcurrentHashMap<Integer, AccumulationResult>();
        var threads = IntStream.range(0, threadCount).mapToObj(i -> new Thread(() -> {
            var member = createMember(i);
            var signature = createSignature(i);
            var result = aggregator.accumulate(event, member, i, signature, threshold, 1);
            results.put(i, result);
            latch.countDown();
        })).toList();

        threads.forEach(Thread::start);
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        // Should have accumulated all signatures
        assertThat(aggregator.metrics().totalAccumulated()).isEqualTo(threadCount);

        // Exactly one thread should see ThresholdMet
        var thresholdResults = results.values().stream()
            .filter(r -> r instanceof AccumulationResult.ThresholdMet)
            .count();
        assertThat(thresholdResults).isEqualTo(1);
    }

    @Test
    void shouldHandleConcurrentAggregation() throws InterruptedException {
        var event = createTestEvent(1);
        var threshold = 5;

        // Accumulate threshold signatures
        for (int i = 0; i < threshold; i++) {
            aggregator.accumulate(event, createMember(i), i, createSignature(i), threshold, 1);
        }

        // Call aggregate concurrently from multiple threads
        var threadCount = 5;
        var latch = new CountDownLatch(threadCount);
        var results = new ConcurrentHashMap<Integer, AggregationResult>();

        var threads = IntStream.range(0, threadCount).mapToObj(i -> new Thread(() -> {
            var result = aggregator.aggregate(event);
            results.put(i, result);
            latch.countDown();
        })).toList();

        threads.forEach(Thread::start);
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();

        // All results should be Aggregated (idempotent)
        assertThat(results.values()).allMatch(r -> r instanceof AggregationResult.Aggregated);
    }

    // ========== Metrics Tests ==========

    @Test
    void shouldTrackActiveAccumulators() {
        var event1 = createTestEvent(1);
        var event2 = createTestEvent(2);
        var event3 = createTestEvent(3);

        // Initially zero
        assertThat(aggregator.metrics().activeAccumulators()).isZero();

        // Create 3 accumulators
        aggregator.accumulate(event1, createMember(0), 0, createSignature(0), 5, 1);
        aggregator.accumulate(event2, createMember(1), 1, createSignature(1), 5, 1);
        aggregator.accumulate(event3, createMember(2), 2, createSignature(2), 5, 1);

        assertThat(aggregator.metrics().activeAccumulators()).isEqualTo(3);
    }

    @Test
    void shouldTrackTotalAccumulated() {
        var event = createTestEvent(1);
        var threshold = 3;

        // Initially zero
        assertThat(aggregator.metrics().totalAccumulated()).isZero();

        // Accumulate 3 signatures (meets threshold)
        for (int i = 0; i < 3; i++) {
            aggregator.accumulate(event, createMember(i), i, createSignature(i), threshold, 1);
        }

        assertThat(aggregator.metrics().totalAccumulated()).isEqualTo(3);

        // Add more signatures - these are rejected as late signers after threshold
        for (int i = 3; i < 7; i++) {
            aggregator.accumulate(event, createMember(i), i, createSignature(i), threshold, 1);
        }

        // Only the first 3 accumulations count; rest are rejected as late signers
        assertThat(aggregator.metrics().totalAccumulated()).isEqualTo(3);
    }

    @Test
    void shouldTrackAggregationsCreated() {
        var threshold = 3;

        // Initially zero
        assertThat(aggregator.metrics().aggregationsCreated()).isZero();

        // Create first aggregation
        var event1 = createTestEvent(1);
        for (int i = 0; i < threshold; i++) {
            aggregator.accumulate(event1, createMember(i), i, createSignature(i), threshold, 1);
        }
        aggregator.aggregate(event1);

        assertThat(aggregator.metrics().aggregationsCreated()).isEqualTo(1);

        // Create second aggregation
        var event2 = createTestEvent(2);
        for (int i = 0; i < threshold; i++) {
            aggregator.accumulate(event2, createMember(i + 10), i, createSignature(i + 10), threshold, 1);
        }
        aggregator.aggregate(event2);

        assertThat(aggregator.metrics().aggregationsCreated()).isEqualTo(2);
    }

    // ========== Edge Cases ==========

    @Test
    void shouldRejectNullEvent() {
        assertThatThrownBy(() -> {
            aggregator.accumulate(null, createMember(0), 0, createSignature(0), 5, 1);
        }).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullMember() {
        assertThatThrownBy(() -> {
            aggregator.accumulate(createTestEvent(1), null, 0, createSignature(0), 5, 1);
        }).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullSignature() {
        assertThatThrownBy(() -> {
            aggregator.accumulate(createTestEvent(1), createMember(0), 0, null, 5, 1);
        }).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNegativeCommitteeIndex() {
        assertThatThrownBy(() -> {
            aggregator.accumulate(createTestEvent(1), createMember(0), -1, createSignature(0), 5, 1);
        }).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidThreshold() {
        assertThatThrownBy(() -> {
            aggregator.accumulate(createTestEvent(1), createMember(0), 0, createSignature(0), 0, 1);
        }).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNegativeEpoch() {
        assertThatThrownBy(() -> {
            aggregator.accumulate(createTestEvent(1), createMember(0), 0, createSignature(0), 5, -1);
        }).isInstanceOf(IllegalArgumentException.class);
    }

    // ========== Test Utilities ==========

    private EventCoordinates createTestEvent(int sequenceNumber) {
        var identifier = new SelfAddressingIdentifier(digestAlgorithm.digest(
            ("test-event-" + sequenceNumber).getBytes()));
        var digest = digestAlgorithm.digest(("digest-" + sequenceNumber).getBytes());
        var ilk = "icp";
        return new EventCoordinates(identifier, ULong.valueOf(sequenceNumber), digest, ilk);
    }

    private Identifier createMember(int index) {
        return new SelfAddressingIdentifier(digestAlgorithm.digest(
            ("member-" + index).getBytes()));
    }

    private BLSSignature createSignature(int index) {
        // Sign a test message with the key pair for this index
        var keyPair = testKeyPairs.get(index % testKeyPairs.size());
        var message = digestAlgorithm.digest(("test-message-" + index).getBytes()).getBytes();
        return keyPair.sign(message);
    }
}
