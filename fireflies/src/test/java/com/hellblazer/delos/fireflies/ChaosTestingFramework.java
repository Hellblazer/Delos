/*
 * Copyright (c) 2025, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Chaos testing framework for exposing race conditions in Fireflies.
 * Provides random delays, failures, and operation shuffling with reproducible randomness.
 *
 * @author hal.hildebrand
 */
public final class ChaosTestingFramework {

    private ChaosTestingFramework() {
        // utility class
    }

    /**
     * Configuration for chaos testing.
     */
    public record ChaosConfig(double failureProbability, int minDelayMs, int maxDelayMs, double delayProbability,
                              long seed) {

        public static ChaosConfig none() {
            return new ChaosConfig(0.0, 0, 0, 0.0, 42L);
        }

        public static ChaosConfig mild() {
            return new ChaosConfig(0.05, 1, 10, 0.1, 42L);
        }

        public static ChaosConfig moderate() {
            return new ChaosConfig(0.10, 5, 50, 0.3, 42L);
        }

        public static ChaosConfig aggressive() {
            return new ChaosConfig(0.20, 10, 100, 0.5, 42L);
        }

        public ChaosConfig withSeed(long newSeed) {
            return new ChaosConfig(failureProbability, minDelayMs, maxDelayMs, delayProbability, newSeed);
        }
    }

    /**
     * Injects random delays between operations.
     */
    public static class DelayInjector {
        private final Random random;
        private final int    minDelayMs;
        private final int    maxDelayMs;
        private final double probability;

        public DelayInjector(ChaosConfig config) {
            this.random = new Random(config.seed());
            this.minDelayMs = config.minDelayMs();
            this.maxDelayMs = config.maxDelayMs();
            this.probability = config.delayProbability();
        }

        public void maybeDelay() {
            if (probability > 0 && random.nextDouble() < probability) {
                var delayMs = minDelayMs + random.nextInt(Math.max(1, maxDelayMs - minDelayMs));
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        public <T> T withDelay(Supplier<T> operation) {
            maybeDelay();
            return operation.get();
        }

        public void withDelay(Runnable operation) {
            maybeDelay();
            operation.run();
        }
    }

    /**
     * Injects random failures during operations.
     */
    public static class FailureInjector {
        private final Random random;
        private final double probability;

        public FailureInjector(ChaosConfig config) {
            this.random = new Random(config.seed() + 1); // Different seed than delay
            this.probability = config.failureProbability();
        }

        public void maybeThrow() {
            if (probability > 0 && random.nextDouble() < probability) {
                throw new ChaosInducedException("Chaos-induced failure");
            }
        }

        public <T> T withFailure(Supplier<T> operation) {
            maybeThrow();
            return operation.get();
        }

        public void withFailure(Runnable operation) {
            maybeThrow();
            operation.run();
        }
    }

    /**
     * Exception thrown by chaos testing framework.
     */
    public static class ChaosInducedException extends RuntimeException {
        public ChaosInducedException(String message) {
            super(message);
        }
    }

    /**
     * Shuffles operations randomly for testing different orderings.
     */
    public static class OperationShuffler<T> {
        private final Random  random;
        private final List<T> operations;

        public OperationShuffler(long seed) {
            this.random = new Random(seed + 2);
            this.operations = new ArrayList<>();
        }

        public void add(T operation) {
            operations.add(operation);
        }

        public List<T> shuffle() {
            var shuffled = new ArrayList<>(operations);
            Collections.shuffle(shuffled, random);
            return shuffled;
        }

        public void clear() {
            operations.clear();
        }
    }

    /**
     * Coordinates chaos testing with invariant checking.
     */
    public static class ChaosController {
        private final ChaosConfig                              config;
        private final DelayInjector                            delayInjector;
        private final FailureInjector                          failureInjector;
        private final CopyOnWriteArrayList<ChaosEventListener> listeners;
        private final AtomicInteger                            operationCount;
        private final AtomicInteger                            failureCount;
        private final AtomicInteger                            delayCount;

        public ChaosController(ChaosConfig config) {
            this.config = config;
            this.delayInjector = new DelayInjector(config);
            this.failureInjector = new FailureInjector(config);
            this.listeners = new CopyOnWriteArrayList<>();
            this.operationCount = new AtomicInteger(0);
            this.failureCount = new AtomicInteger(0);
            this.delayCount = new AtomicInteger(0);
        }

        public void addListener(ChaosEventListener listener) {
            listeners.add(listener);
        }

        /**
         * Execute an operation with chaos (delays and potential failures).
         */
        public <T> T execute(String operationName, Supplier<T> operation) {
            operationCount.incrementAndGet();
            notifyListeners(l -> l.onOperationStart(operationName));

            try {
                delayInjector.maybeDelay();
                failureInjector.maybeThrow();
                var result = operation.get();
                notifyListeners(l -> l.onOperationComplete(operationName, true));
                return result;
            } catch (ChaosInducedException e) {
                failureCount.incrementAndGet();
                notifyListeners(l -> l.onOperationComplete(operationName, false));
                throw e;
            }
        }

        /**
         * Execute an operation with chaos (delays and potential failures).
         */
        public void execute(String operationName, Runnable operation) {
            execute(operationName, () -> {
                operation.run();
                return null;
            });
        }

        /**
         * Execute with invariant checking before and after.
         */
        public <T> T executeWithInvariantCheck(String operationName, View view, Supplier<T> operation) {
            FirefliesStateInvariants.assertAllInvariants(view);
            var result = execute(operationName, operation);
            FirefliesStateInvariants.assertAllInvariants(view);
            return result;
        }

        public ChaosStats getStats() {
            return new ChaosStats(operationCount.get(), failureCount.get(), delayCount.get());
        }

        public void reset() {
            operationCount.set(0);
            failureCount.set(0);
            delayCount.set(0);
        }

        private void notifyListeners(java.util.function.Consumer<ChaosEventListener> action) {
            listeners.forEach(action);
        }
    }

    /**
     * Listener for chaos events.
     */
    public interface ChaosEventListener {
        void onOperationStart(String operationName);

        void onOperationComplete(String operationName, boolean success);
    }

    /**
     * Statistics from chaos testing.
     */
    public record ChaosStats(int operationCount, int failureCount, int delayCount) {
        public double failureRate() {
            return operationCount == 0 ? 0.0 : (double) failureCount / operationCount;
        }

        @Override
        public String toString() {
            return String.format("ChaosStats[operations=%d, failures=%d (%.1f%%), delays=%d]", operationCount,
                                 failureCount, failureRate() * 100, delayCount);
        }
    }

    /**
     * Builder for creating chaos test scenarios.
     */
    public static class ChaosScenarioBuilder {
        private final List<Runnable> operations = new ArrayList<>();
        private       ChaosConfig    config     = ChaosConfig.moderate();

        public ChaosScenarioBuilder withConfig(ChaosConfig config) {
            this.config = config;
            return this;
        }

        public ChaosScenarioBuilder addOperation(Runnable operation) {
            operations.add(operation);
            return this;
        }

        public ChaosScenarioBuilder addOperations(List<Runnable> ops) {
            operations.addAll(ops);
            return this;
        }

        public ChaosScenario build() {
            return new ChaosScenario(config, new ArrayList<>(operations));
        }
    }

    /**
     * A chaos test scenario with operations and configuration.
     */
    public record ChaosScenario(ChaosConfig config, List<Runnable> operations) {

        /**
         * Run the scenario with chaos injection.
         */
        public ChaosStats run() {
            var controller = new ChaosController(config);
            var shuffler = new OperationShuffler<Runnable>(config.seed());
            operations.forEach(shuffler::add);

            var shuffled = shuffler.shuffle();
            for (int i = 0; i < shuffled.size(); i++) {
                try {
                    controller.execute("operation-" + i, shuffled.get(i));
                } catch (ChaosInducedException e) {
                    // Expected, continue with next operation
                }
            }

            return controller.getStats();
        }

        /**
         * Run the scenario multiple times with different seeds.
         */
        public List<ChaosStats> runMultiple(int iterations) {
            var results = new ArrayList<ChaosStats>();
            for (int i = 0; i < iterations; i++) {
                var scenarioWithSeed = new ChaosScenario(config.withSeed(config.seed() + i), operations);
                results.add(scenarioWithSeed.run());
            }
            return results;
        }
    }
}
