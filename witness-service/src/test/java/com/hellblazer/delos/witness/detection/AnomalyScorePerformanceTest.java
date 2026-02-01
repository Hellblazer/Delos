/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import com.hellblazer.delos.stereotomy.identifier.Identifier;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Performance test for AnomalyScore O(1) fix.
 * <p>
 * Phase 0 requirement: 100K recordEvent() calls in < 100ms.
 * </p>
 *
 * @author hal.hildebrand
 */
class AnomalyScorePerformanceTest {

    private static final Logger log = LoggerFactory.getLogger(AnomalyScorePerformanceTest.class);

    @Test
    void recordEventPerformance() {
        var score = new AnomalyScore(Identifier.NONE, 1000, 0.1);
        var random = new Random(42L);
        var eventCount = 100_000;

        // Warm up JIT
        for (int i = 0; i < 10_000; i++) {
            score.recordEvent(random.nextDouble(), "Warmup");
        }

        // Reset for actual test
        score = new AnomalyScore(Identifier.NONE, 1000, 0.1);

        // Measure
        var startNanos = System.nanoTime();
        for (int i = 0; i < eventCount; i++) {
            score.recordEvent(random.nextDouble() * 0.5, "Event " + i);
        }
        var elapsedNanos = System.nanoTime() - startNanos;
        var elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);

        log.info("Performance: {} recordEvent() calls in {} ms ({} ops/sec)",
            eventCount,
            elapsedMs,
            eventCount * 1000L / Math.max(1, elapsedMs));

        // Phase 0 requirement: 100K in < 100ms
        assertThat(elapsedMs)
            .as("100K recordEvent() calls must complete in < 100ms")
            .isLessThan(100);
    }

    @Test
    void recordEventIsO1NotON() {
        // Measure time for small history vs large history
        // If O(N), large history should take proportionally longer
        // If O(1), times should be similar

        var smallHistoryScore = new AnomalyScore(Identifier.NONE, 100, 0.1);
        var largeHistoryScore = new AnomalyScore(Identifier.NONE, 10_000, 0.1);
        var random = new Random(42L);
        var warmupEvents = 50_000;
        var measureEvents = 10_000;

        // Fill both histories to capacity
        for (int i = 0; i < warmupEvents; i++) {
            smallHistoryScore.recordEvent(random.nextDouble(), "Fill");
            largeHistoryScore.recordEvent(random.nextDouble(), "Fill");
        }

        // Measure small history
        var startSmall = System.nanoTime();
        for (int i = 0; i < measureEvents; i++) {
            smallHistoryScore.recordEvent(random.nextDouble(), "Measure");
        }
        var smallTimeNanos = System.nanoTime() - startSmall;

        // Measure large history
        var startLarge = System.nanoTime();
        for (int i = 0; i < measureEvents; i++) {
            largeHistoryScore.recordEvent(random.nextDouble(), "Measure");
        }
        var largeTimeNanos = System.nanoTime() - startLarge;

        var ratio = (double) largeTimeNanos / smallTimeNanos;

        log.info("O(1) verification:");
        log.info("  Small history (100): {} ns for {} events", smallTimeNanos, measureEvents);
        log.info("  Large history (10K): {} ns for {} events", largeTimeNanos, measureEvents);
        log.info("  Ratio (large/small): {}", String.format("%.2f", ratio));

        // If O(1), ratio should be close to 1 (within 3x due to cache effects)
        // If O(N), ratio would be ~100 (10000/100)
        assertThat(ratio)
            .as("Large history should not take significantly longer than small (O(1) vs O(N))")
            .isLessThan(3.0);
    }

    @Test
    void concurrentRecordEventPerformance() throws InterruptedException {
        var score = new AnomalyScore(Identifier.NONE, 1000, 0.1);
        var threadCount = 4;
        var eventsPerThread = 25_000;
        var threads = new Thread[threadCount];
        var errors = new java.util.concurrent.atomic.AtomicInteger(0);

        var startLatch = new java.util.concurrent.CountDownLatch(1);
        var doneLatch = new java.util.concurrent.CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            var seed = t;
            threads[t] = new Thread(() -> {
                try {
                    startLatch.await();
                    var random = new Random(seed);
                    for (int i = 0; i < eventsPerThread; i++) {
                        score.recordEvent(random.nextDouble(), "Event");
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
            threads[t].start();
        }

        var startNanos = System.nanoTime();
        startLatch.countDown();
        doneLatch.await();
        var elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        var totalEvents = threadCount * eventsPerThread;
        log.info("Concurrent performance: {} events across {} threads in {} ms ({} ops/sec)",
            totalEvents,
            threadCount,
            elapsedMs,
            totalEvents * 1000L / Math.max(1, elapsedMs));

        assertThat(errors.get()).isZero();
        assertThat(elapsedMs)
            .as("Concurrent 100K events should complete in < 200ms")
            .isLessThan(200);
    }
}
