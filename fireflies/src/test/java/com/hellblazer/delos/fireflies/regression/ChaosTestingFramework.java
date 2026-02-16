/*
 * Copyright (c) 2026, Delos Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies.regression;

import com.hellblazer.delos.fireflies.View;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Chaos testing framework for Fireflies regression testing.
 * Provides random delays, failures, and operation shuffling to expose race conditions.
 *
 * @author hal.hildebrand
 */
public class ChaosTestingFramework {

    /**
     * Configuration for chaos testing behavior.
     */
    public record ChaosConfig(
        double failureProbability,    // 0.0 - 1.0, probability of injected failure
        Duration minDelay,            // Minimum delay to inject
        Duration maxDelay,            // Maximum delay to inject
        double delayProbability,      // 0.0 - 1.0, probability of delay injection
        long seed                     // Random seed for reproducibility
    ) {
        public static ChaosConfig none() {
            return new ChaosConfig(0.0, Duration.ZERO, Duration.ZERO, 0.0, 0);
        }

        public static ChaosConfig mild(long seed) {
            return new ChaosConfig(0.05, Duration.ofMillis(1), Duration.ofMillis(10), 0.1, seed);
        }

        public static ChaosConfig moderate(long seed) {
            return new ChaosConfig(0.1, Duration.ofMillis(5), Duration.ofMillis(50), 0.3, seed);
        }

        public static ChaosConfig aggressive(long seed) {
            return new ChaosConfig(0.2, Duration.ofMillis(10), Duration.ofMillis(100), 0.5, seed);
        }
    }

    /**
     * Delay injector for random timing variations.
     */
    public static class DelayInjector {
        private final ChaosConfig config;
        private final Random random;
        private final AtomicLong delayCount = new AtomicLong();
        private final AtomicLong totalDelayMs = new AtomicLong();

        public DelayInjector(ChaosConfig config) {
            this.config = config;
            this.random = new Random(config.seed());
        }

        /**
         * Maybe inject a random delay based on configuration.
         */
        public void maybeDelay() {
            if (random.nextDouble() < config.delayProbability()) {
                var range = config.maxDelay().toMillis() - config.minDelay().toMillis();
                var delayMs = config.minDelay().toMillis() + random.nextLong(Math.max(1, range));
                try {
                    Thread.sleep(delayMs);
                    delayCount.incrementAndGet();
                    totalDelayMs.addAndGet(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        /**
         * Force a specific delay.
         */
        public void forceDelay(Duration duration) {
            try {
                Thread.sleep(duration.toMillis());
                delayCount.incrementAndGet();
                totalDelayMs.addAndGet(duration.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        public long getDelayCount() { return delayCount.get(); }
        public long getTotalDelayMs() { return totalDelayMs.get(); }
    }

    /**
     * Failure injector for random exception throwing.
     */
    public static class FailureInjector {
        private final ChaosConfig config;
        private final Random random;
        private final AtomicLong failureCount = new AtomicLong();

        public FailureInjector(ChaosConfig config) {
            this.config = config;
            this.random = new Random(config.seed() + 1); // Different stream
        }

        /**
         * Maybe throw an exception based on configuration.
         */
        public void maybeThrow(Supplier<RuntimeException> exceptionSupplier) {
            if (random.nextDouble() < config.failureProbability()) {
                failureCount.incrementAndGet();
                throw exceptionSupplier.get();
            }
        }

        /**
         * Maybe simulate a timeout.
         */
        public void maybeTimeout() {
            maybeThrow(() -> new RuntimeException("Chaos-injected timeout"));
        }

        public long getFailureCount() { return failureCount.get(); }
    }

    /**
     * Operation shuffler for random ordering.
     */
    public static class OperationShuffler {
        private final Random random;

        public OperationShuffler(long seed) {
            this.random = new Random(seed + 2); // Different stream
        }

        /**
         * Shuffle a list of operations in place.
         */
        public void shuffle(List<Runnable> operations) {
            Collections.shuffle(operations, random);
        }

        /**
         * Execute operations in shuffled order.
         */
        public void executeShuffled(List<Runnable> operations) {
            var shuffled = new ArrayList<>(operations);
            shuffle(shuffled);
            shuffled.forEach(Runnable::run);
        }

        /**
         * Execute operations in shuffled order using virtual threads.
         */
        public List<CompletableFuture<Void>> executeShuffledAsync(List<Runnable> operations) {
            var shuffled = new ArrayList<>(operations);
            shuffle(shuffled);
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            return shuffled.stream()
                .map(op -> CompletableFuture.runAsync(op, executor))
                .toList();
        }
    }

    /**
     * Result of chaos execution.
     */
    public record ChaosExecutionResult(
        long operationCount,
        List<FirefliesStateInvariants.InvariantViolation> violations,
        List<Throwable> exceptions,
        Duration duration,
        long delaysInjected,
        long failuresInjected
    ) {
        public boolean hasViolations() { return !violations.isEmpty(); }
        public boolean hasExceptions() { return !exceptions.isEmpty(); }
        public boolean isClean() { return !hasViolations() && !hasExceptions(); }
    }

    /**
     * Main chaos controller coordinating all chaos components.
     */
    public static class ChaosController {
        private final ChaosConfig config;
        private final DelayInjector delayInjector;
        private final FailureInjector failureInjector;
        private final OperationShuffler shuffler;
        private final List<FirefliesStateInvariants.InvariantViolation> violations;
        private final AtomicLong operationCount = new AtomicLong();

        public ChaosController(ChaosConfig config) {
            this.config = config;
            this.delayInjector = new DelayInjector(config);
            this.failureInjector = new FailureInjector(config);
            this.shuffler = new OperationShuffler(config.seed());
            this.violations = new CopyOnWriteArrayList<>();
        }

        /**
         * Execute operations with chaos and invariant checking.
         */
        public ChaosExecutionResult executeWithChaos(
                View view,
                List<Runnable> operations,
                boolean checkInvariantsAfterEach) {

            var shuffledOps = new ArrayList<>(operations);
            shuffler.shuffle(shuffledOps);

            var exceptions = new CopyOnWriteArrayList<Throwable>();
            var startTime = System.nanoTime();

            for (var op : shuffledOps) {
                try {
                    delayInjector.maybeDelay();
                    op.run();
                    operationCount.incrementAndGet();

                    if (checkInvariantsAfterEach) {
                        violations.addAll(FirefliesStateInvariants.getViolations(view));
                    }
                } catch (Throwable t) {
                    exceptions.add(t);
                }
            }

            var duration = Duration.ofNanos(System.nanoTime() - startTime);

            // Final invariant check
            violations.addAll(FirefliesStateInvariants.getViolations(view));

            return new ChaosExecutionResult(
                operationCount.get(),
                new ArrayList<>(violations),
                new ArrayList<>(exceptions),
                duration,
                delayInjector.getDelayCount(),
                failureInjector.getFailureCount()
            );
        }

        /**
         * Execute operations concurrently with chaos.
         */
        public ChaosExecutionResult executeWithChaosConcurrent(
                View view,
                List<Runnable> operations,
                int concurrency) {

            var executor = Executors.newVirtualThreadPerTaskExecutor();
            var shuffledOps = new ArrayList<>(operations);
            shuffler.shuffle(shuffledOps);

            var exceptions = new CopyOnWriteArrayList<Throwable>();
            var latch = new CountDownLatch(shuffledOps.size());
            var startTime = System.nanoTime();

            for (var op : shuffledOps) {
                executor.submit(() -> {
                    try {
                        delayInjector.maybeDelay();
                        op.run();
                        operationCount.incrementAndGet();
                    } catch (Throwable t) {
                        exceptions.add(t);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            try {
                latch.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            var duration = Duration.ofNanos(System.nanoTime() - startTime);
            executor.shutdown();

            // Final invariant check
            violations.addAll(FirefliesStateInvariants.getViolations(view));

            return new ChaosExecutionResult(
                operationCount.get(),
                new ArrayList<>(violations),
                new ArrayList<>(exceptions),
                duration,
                delayInjector.getDelayCount(),
                failureInjector.getFailureCount()
            );
        }

        public DelayInjector getDelayInjector() { return delayInjector; }
        public FailureInjector getFailureInjector() { return failureInjector; }
        public OperationShuffler getShuffler() { return shuffler; }
    }
}
