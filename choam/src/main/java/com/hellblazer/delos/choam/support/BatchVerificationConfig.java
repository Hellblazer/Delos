/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Configuration for batch signature verification with feature flags and circuit breaker.
 * <p>
 * Provides safe rollout capabilities:
 * - Feature flag to enable/disable batch verification (default: disabled)
 * - A/B testing percentage for gradual rollout
 * - Circuit breaker that auto-disables on high failure rate
 * <p>
 * Thread-safe: All state is managed via atomic operations.
 *
 * @author hal.hildebrand
 */
public class BatchVerificationConfig {

    /**
     * Default configuration with batch verification disabled.
     */
    public static final BatchVerificationConfig DISABLED = new BatchVerificationConfig(false, 0, 0.01, 100, 50);

    /**
     * Default configuration with batch verification enabled (100% traffic).
     */
    public static final BatchVerificationConfig ENABLED = new BatchVerificationConfig(true, 100, 0.01, 100, 50);

    // Feature flag
    private final boolean enableBatchVerification;

    // A/B testing: percentage of traffic to use batch verification (0-100)
    private final int batchVerificationPercentage;

    // Circuit breaker settings
    private final double circuitBreakerThreshold;  // Failure rate threshold (e.g., 0.01 = 1%)
    private final int circuitBreakerWindowSize;    // Number of operations to track
    private final int circuitBreakerMinOperations; // Minimum operations before tripping

    // Circuit breaker state
    private final AtomicBoolean circuitOpen = new AtomicBoolean(false);
    private final AtomicLong totalOperations = new AtomicLong(0);
    private final AtomicLong failedOperations = new AtomicLong(0);
    private final AtomicLong windowStart = new AtomicLong(System.nanoTime());

    /**
     * Create batch verification configuration.
     *
     * @param enableBatchVerification    master feature flag (default: false)
     * @param batchVerificationPercentage percentage of traffic for batch verification (0-100)
     * @param circuitBreakerThreshold    failure rate to trip circuit (e.g., 0.01 = 1%)
     * @param circuitBreakerWindowSize   operations to track for failure rate
     * @param circuitBreakerMinOperations minimum ops before circuit can trip
     */
    public BatchVerificationConfig(
        boolean enableBatchVerification,
        int batchVerificationPercentage,
        double circuitBreakerThreshold,
        int circuitBreakerWindowSize,
        int circuitBreakerMinOperations
    ) {
        if (batchVerificationPercentage < 0 || batchVerificationPercentage > 100) {
            throw new IllegalArgumentException(
                "batchVerificationPercentage must be 0-100, got: " + batchVerificationPercentage);
        }
        if (circuitBreakerThreshold < 0 || circuitBreakerThreshold > 1.0) {
            throw new IllegalArgumentException(
                "circuitBreakerThreshold must be 0.0-1.0, got: " + circuitBreakerThreshold);
        }
        if (circuitBreakerWindowSize < 1) {
            throw new IllegalArgumentException(
                "circuitBreakerWindowSize must be >= 1, got: " + circuitBreakerWindowSize);
        }
        if (circuitBreakerMinOperations < 1) {
            throw new IllegalArgumentException(
                "circuitBreakerMinOperations must be >= 1, got: " + circuitBreakerMinOperations);
        }

        this.enableBatchVerification = enableBatchVerification;
        this.batchVerificationPercentage = batchVerificationPercentage;
        this.circuitBreakerThreshold = circuitBreakerThreshold;
        this.circuitBreakerWindowSize = circuitBreakerWindowSize;
        this.circuitBreakerMinOperations = circuitBreakerMinOperations;
    }

    /**
     * Check if batch verification should be used for this operation.
     * <p>
     * Returns true only if:
     * - Feature flag is enabled
     * - Circuit breaker is closed (not tripped)
     * - Random sampling selects this operation (based on percentage)
     *
     * @return true if batch verification should be attempted
     */
    public boolean shouldUseBatchVerification() {
        // Check master flag
        if (!enableBatchVerification) {
            return false;
        }

        // Check circuit breaker
        if (circuitOpen.get()) {
            return false;
        }

        // A/B testing: check percentage
        if (batchVerificationPercentage >= 100) {
            return true;
        }
        if (batchVerificationPercentage <= 0) {
            return false;
        }

        return ThreadLocalRandom.current().nextInt(100) < batchVerificationPercentage;
    }

    /**
     * Record a batch verification operation result.
     * <p>
     * Tracks success/failure for circuit breaker calculations.
     * May trip the circuit breaker if failure rate exceeds threshold.
     *
     * @param success true if the batch operation succeeded
     */
    public void recordOperation(boolean success) {
        long total = totalOperations.incrementAndGet();
        if (!success) {
            failedOperations.incrementAndGet();
        }

        // Check if we should evaluate circuit breaker
        if (total >= circuitBreakerMinOperations && total % 10 == 0) {
            evaluateCircuitBreaker();
        }

        // Reset window if we've exceeded the window size
        if (total >= circuitBreakerWindowSize) {
            resetWindow();
        }
    }

    /**
     * Evaluate whether to trip the circuit breaker.
     */
    private void evaluateCircuitBreaker() {
        long total = totalOperations.get();
        long failed = failedOperations.get();

        if (total < circuitBreakerMinOperations) {
            return;
        }

        double failureRate = (double) failed / total;
        if (failureRate > circuitBreakerThreshold) {
            if (circuitOpen.compareAndSet(false, true)) {
                // Circuit just tripped
                org.slf4j.LoggerFactory.getLogger(BatchVerificationConfig.class)
                    .warn("Circuit breaker tripped: failure rate {:.2%} > threshold {:.2%} ({}/{} operations)",
                          failureRate, circuitBreakerThreshold, failed, total);
            }
        }
    }

    /**
     * Reset the tracking window.
     */
    private void resetWindow() {
        // Only reset if we've exceeded the window
        if (totalOperations.get() >= circuitBreakerWindowSize) {
            totalOperations.set(0);
            failedOperations.set(0);
            windowStart.set(System.nanoTime());
        }
    }

    /**
     * Manually reset the circuit breaker to closed state.
     * <p>
     * Use with caution - typically for testing or manual recovery.
     */
    public void resetCircuitBreaker() {
        circuitOpen.set(false);
        totalOperations.set(0);
        failedOperations.set(0);
        windowStart.set(System.nanoTime());
    }

    /**
     * Check if the circuit breaker is currently open (tripped).
     */
    public boolean isCircuitOpen() {
        return circuitOpen.get();
    }

    /**
     * Check if batch verification is enabled (master flag).
     */
    public boolean isEnabled() {
        return enableBatchVerification;
    }

    /**
     * Get the batch verification traffic percentage.
     */
    public int getPercentage() {
        return batchVerificationPercentage;
    }

    /**
     * Get the circuit breaker failure rate threshold.
     */
    public double getCircuitBreakerThreshold() {
        return circuitBreakerThreshold;
    }

    /**
     * Get current failure rate in the tracking window.
     */
    public double getCurrentFailureRate() {
        long total = totalOperations.get();
        if (total == 0) {
            return 0.0;
        }
        return (double) failedOperations.get() / total;
    }

    /**
     * Builder for creating custom configurations.
     */
    public static Builder newBuilder() {
        return new Builder();
    }

    public static class Builder {
        private boolean enableBatchVerification = false;
        private int batchVerificationPercentage = 0;
        private double circuitBreakerThreshold = 0.01;  // 1%
        private int circuitBreakerWindowSize = 100;
        private int circuitBreakerMinOperations = 50;

        public Builder setEnableBatchVerification(boolean enable) {
            this.enableBatchVerification = enable;
            return this;
        }

        public Builder setBatchVerificationPercentage(int percentage) {
            this.batchVerificationPercentage = percentage;
            return this;
        }

        public Builder setCircuitBreakerThreshold(double threshold) {
            this.circuitBreakerThreshold = threshold;
            return this;
        }

        public Builder setCircuitBreakerWindowSize(int windowSize) {
            this.circuitBreakerWindowSize = windowSize;
            return this;
        }

        public Builder setCircuitBreakerMinOperations(int minOperations) {
            this.circuitBreakerMinOperations = minOperations;
            return this;
        }

        public BatchVerificationConfig build() {
            return new BatchVerificationConfig(
                enableBatchVerification,
                batchVerificationPercentage,
                circuitBreakerThreshold,
                circuitBreakerWindowSize,
                circuitBreakerMinOperations
            );
        }
    }
}
