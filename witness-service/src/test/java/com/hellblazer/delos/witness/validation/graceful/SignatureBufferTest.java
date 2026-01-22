/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.validation.graceful;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive tests for SignatureBuffer.
 * <p>
 * Tests thread safety, TTL expiration, capacity enforcement, epoch filtering,
 * concurrent operations, and performance characteristics.
 * <p>
 * Phase 1C-3-C: Graceful Degradation (Delos-3956)
 */
class SignatureBufferTest {

    private GracefulDegradationConfig config;
    private SignatureBuffer buffer;
    private SecureRandom random;

    @BeforeEach
    void setUp() {
        config = GracefulDegradationConfig.defaultConfig();
        buffer = new SignatureBuffer(config);
        random = new SecureRandom();
    }

    /**
     * Basic buffer() functionality - single signature
     */
    @Test
    void shouldBufferSignature() {
        // Given
        var memberId = createMemberId();
        var signature = createSignature();
        var message = createMessage();
        var event = createEventCoordinates(1);
        var epoch = 1L;

        // When
        var position = buffer.buffer(memberId, signature, message, event, epoch);

        // Then
        assertThat(position).isEqualTo(0);
        assertThat(buffer.size()).isEqualTo(1);
        assertThat(buffer.isEmpty()).isFalse();
    }

    /**
     * Test buffering multiple signatures incrementally
     */
    @Test
    void shouldBufferMultipleSignatures() {
        // Given
        var count = 10;

        // When
        for (var i = 0; i < count; i++) {
            var position = buffer.buffer(
                createMemberId(),
                createSignature(),
                createMessage(),
                createEventCoordinates(i),
                1L
            );
            assertThat(position).isEqualTo(i);
        }

        // Then
        assertThat(buffer.size()).isEqualTo(count);
        assertThat(buffer.getAll()).hasSize(count);
    }

    /**
     * Capacity enforcement - eviction when full
     */
    @Test
    void shouldEnforceCapacityLimit() {
        // Given
        var maxSize = config.maxSignaturesToBuffer();
        var overage = 10;

        // When - buffer beyond capacity
        for (var i = 0; i < maxSize + overage; i++) {
            buffer.buffer(
                createMemberId(),
                createSignature(),
                createMessage(),
                createEventCoordinates(i),
                1L
            );
        }

        // Then - size should not exceed max
        assertThat(buffer.size()).isLessThanOrEqualTo(maxSize);

        // Stats should reflect total buffered
        var stats = buffer.getStats();
        assertThat(stats.currentSize()).isLessThanOrEqualTo(maxSize);
        assertThat(stats.maxSize()).isEqualTo(maxSize);
        assertThat(stats.totalBuffered()).isEqualTo(maxSize + overage);
    }

    /**
     * getForEpoch() filtering - only returns specified epoch
     */
    @Test
    void shouldFilterByEpoch() {
        // Given - buffer signatures in 3 epochs
        for (var epoch = 1; epoch <= 3; epoch++) {
            for (var i = 0; i < 5; i++) {
                buffer.buffer(
                    createMemberId(),
                    createSignature(),
                    createMessage(),
                    createEventCoordinates(i),
                    epoch
                );
            }
        }

        // When
        var epoch2Sigs = buffer.getForEpoch(2L);

        // Then
        assertThat(epoch2Sigs).hasSize(5);
        assertThat(epoch2Sigs).allMatch(sig -> sig.epochNumber() == 2L);
    }

    /**
     * clearEpoch() cleanup - removes only specified epoch
     */
    @Test
    void shouldClearSpecificEpoch() {
        // Given - buffer in 3 epochs
        for (var epoch = 1; epoch <= 3; epoch++) {
            for (var i = 0; i < 5; i++) {
                buffer.buffer(
                    createMemberId(),
                    createSignature(),
                    createMessage(),
                    createEventCoordinates(i),
                    epoch
                );
            }
        }
        assertThat(buffer.size()).isEqualTo(15);

        // When - clear epoch 2
        buffer.clearEpoch(2L);

        // Then - epoch 2 gone, others remain
        assertThat(buffer.size()).isEqualTo(10);
        assertThat(buffer.getForEpoch(1L)).hasSize(5);
        assertThat(buffer.getForEpoch(2L)).isEmpty();
        assertThat(buffer.getForEpoch(3L)).hasSize(5);
    }

    /**
     * TTL expiration - old signatures automatically removed
     */
    @Test
    @Timeout(10)
    void shouldExpireOldSignatures() throws InterruptedException {
        // Given - short TTL config
        var shortTTLConfig = new GracefulDegradationConfig(
            0.33, 1000, 500, 10000, true, 100, true, 0.667, true, 10000
        );
        var ttlBuffer = new SignatureBuffer(shortTTLConfig);

        // When - buffer signatures
        for (var i = 0; i < 5; i++) {
            ttlBuffer.buffer(
                createMemberId(),
                createSignature(),
                createMessage(),
                createEventCoordinates(i),
                1L
            );
        }
        assertThat(ttlBuffer.size()).isEqualTo(5);

        // Wait for TTL expiration
        Thread.sleep(600);

        // Then - force cleanup by buffering new signature
        ttlBuffer.buffer(
            createMemberId(),
            createSignature(),
            createMessage(),
            createEventCoordinates(100),
            1L
        );

        // Old signatures should be expired
        assertThat(ttlBuffer.size()).isLessThan(6);
    }

    /**
     * Concurrent thread safety - 32 threads buffering simultaneously
     */
    @Test
    @Timeout(10)
    void shouldHandleConcurrentBuffering() throws InterruptedException {
        // Given
        var threadCount = 32;
        var signaturesPerThread = 10;
        var executor = Executors.newFixedThreadPool(threadCount);
        var latch = new CountDownLatch(threadCount);
        var positions = new ArrayList<Integer>();

        // When - buffer from multiple threads
        for (var t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                for (var i = 0; i < signaturesPerThread; i++) {
                    var position = buffer.buffer(
                        createMemberId(),
                        createSignature(),
                        createMessage(),
                        createEventCoordinates(i),
                        1L
                    );
                    synchronized (positions) {
                        positions.add(position);
                    }
                }
                latch.countDown();
            });
        }

        latch.await();
        executor.shutdown();

        // Then - all buffered without corruption
        var expectedTotal = threadCount * signaturesPerThread;
        assertThat(buffer.size()).isEqualTo(expectedTotal);
        assertThat(buffer.getAll()).hasSize(expectedTotal);

        // All positions should be unique
        assertThat(new HashSet<>(positions)).hasSize(expectedTotal);
    }

    /**
     * Statistics tracking - accurate counts
     */
    @Test
    void shouldTrackStatistics() {
        // Given - buffer some signatures
        for (var i = 0; i < 5; i++) {
            buffer.buffer(
                createMemberId(),
                createSignature(),
                createMessage(),
                createEventCoordinates(i),
                1L
            );
        }

        // When
        var stats = buffer.getStats();

        // Then
        assertThat(stats.currentSize()).isEqualTo(5);
        assertThat(stats.maxSize()).isEqualTo(config.maxSignaturesToBuffer());
        assertThat(stats.totalBuffered()).isEqualTo(5);
        assertThat(stats.totalExpired()).isEqualTo(0);
        assertThat(stats.oldestAgeMs()).isGreaterThanOrEqualTo(0);
    }

    /**
     * Empty buffer handling
     */
    @Test
    void shouldHandleEmptyBuffer() {
        // When/Then
        assertThat(buffer.isEmpty()).isTrue();
        assertThat(buffer.size()).isEqualTo(0);
        assertThat(buffer.getAll()).isEmpty();
        assertThat(buffer.getForEpoch(1L)).isEmpty();

        var stats = buffer.getStats();
        assertThat(stats.currentSize()).isEqualTo(0);
        assertThat(stats.totalBuffered()).isEqualTo(0);
        assertThat(stats.oldestAgeMs()).isEqualTo(0);
    }

    /**
     * Clear all buffer contents
     */
    @Test
    void shouldClearAllSignatures() {
        // Given - buffer multiple epochs
        for (var epoch = 1; epoch <= 3; epoch++) {
            for (var i = 0; i < 5; i++) {
                buffer.buffer(
                    createMemberId(),
                    createSignature(),
                    createMessage(),
                    createEventCoordinates(i),
                    epoch
                );
            }
        }
        assertThat(buffer.size()).isEqualTo(15);

        // When
        buffer.clear();

        // Then
        assertThat(buffer.isEmpty()).isTrue();
        assertThat(buffer.size()).isEqualTo(0);
        assertThat(buffer.getAll()).isEmpty();
    }

    /**
     * Multiple epochs with different counts
     */
    @Test
    void shouldHandleMultipleEpochsIndependently() {
        // Given - different counts per epoch
        buffer.buffer(createMemberId(), createSignature(), createMessage(), createEventCoordinates(1), 1L);
        buffer.buffer(createMemberId(), createSignature(), createMessage(), createEventCoordinates(2), 1L);

        buffer.buffer(createMemberId(), createSignature(), createMessage(), createEventCoordinates(3), 2L);
        buffer.buffer(createMemberId(), createSignature(), createMessage(), createEventCoordinates(4), 2L);
        buffer.buffer(createMemberId(), createSignature(), createMessage(), createEventCoordinates(5), 2L);

        buffer.buffer(createMemberId(), createSignature(), createMessage(), createEventCoordinates(6), 3L);

        // When/Then
        assertThat(buffer.getForEpoch(1L)).hasSize(2);
        assertThat(buffer.getForEpoch(2L)).hasSize(3);
        assertThat(buffer.getForEpoch(3L)).hasSize(1);
        assertThat(buffer.getForEpoch(999L)).isEmpty();
    }

    /**
     * Performance - buffer() latency < 1ms P99
     */
    @Test
    void shouldMeetPerformanceTargets() {
        // Given - warmup
        for (var i = 0; i < 100; i++) {
            buffer.buffer(
                createMemberId(),
                createSignature(),
                createMessage(),
                createEventCoordinates(i),
                1L
            );
        }
        buffer.clear();

        // When - measure buffer operations
        var iterations = 1000;
        var timings = new ArrayList<Long>();

        for (var i = 0; i < iterations; i++) {
            var start = System.nanoTime();
            buffer.buffer(
                createMemberId(),
                createSignature(),
                createMessage(),
                createEventCoordinates(i),
                1L
            );
            var duration = System.nanoTime() - start;
            timings.add(duration);
        }

        // Then - P99 < 1ms (1_000_000 ns)
        timings.sort(Long::compareTo);
        var p99Index = (int) (iterations * 0.99);
        var p99 = timings.get(p99Index);

        assertThat(p99).isLessThan(1_000_000); // 1ms in nanoseconds
    }

    /**
     * Concurrent reads while writing
     */
    @Test
    @Timeout(10)
    void shouldHandleConcurrentReadsAndWrites() throws InterruptedException {
        // Given - pre-populate with some data
        for (var i = 0; i < 50; i++) {
            buffer.buffer(
                createMemberId(),
                createSignature(),
                createMessage(),
                createEventCoordinates(i),
                1L
            );
        }

        // When - concurrent reads and writes
        var executor = Executors.newFixedThreadPool(16);
        var latch = new CountDownLatch(16);
        var errors = new AtomicInteger(0);

        for (var t = 0; t < 8; t++) {
            // Writers
            executor.submit(() -> {
                try {
                    for (var i = 0; i < 20; i++) {
                        buffer.buffer(
                            createMemberId(),
                            createSignature(),
                            createMessage(),
                            createEventCoordinates(i),
                            1L
                        );
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });

            // Readers
            executor.submit(() -> {
                try {
                    for (var i = 0; i < 20; i++) {
                        var all = buffer.getAll();
                        var forEpoch = buffer.getForEpoch(1L);
                        var stats = buffer.getStats();
                        assertThat(all).isNotNull();
                        assertThat(forEpoch).isNotNull();
                        assertThat(stats).isNotNull();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // Then - no errors
        assertThat(errors.get()).isEqualTo(0);
    }

    // Helper methods for test data creation

    private Identifier createMemberId() {
        var digest = DigestAlgorithm.DEFAULT.digest(randomBytes(32));
        return Identifier.NONE; // Simplified for testing
    }

    private byte[] createSignature() {
        return randomBytes(64);
    }

    private byte[] createMessage() {
        return randomBytes(128);
    }

    private EventCoordinates createEventCoordinates(long sequenceNum) {
        return new EventCoordinates(
            Identifier.NONE,
            ULong.valueOf(sequenceNum),
            DigestAlgorithm.DEFAULT.digest(randomBytes(32)),
            "icp"
        );
    }

    private byte[] randomBytes(int length) {
        var bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }
}
