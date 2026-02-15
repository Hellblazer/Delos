/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Simple circuit breaker for validation operations.
 * <p>
 * Prevents repeated validation attempts when infrastructure is failing.
 * After consecutive failures exceed threshold, circuit opens and validation
 * is skipped until reset timeout elapses.
 * </p>
 * <p>
 * Thread-safe using atomic operations.
 * </p>
 *
 * @author hal.hildebrand
 */
public class ValidationCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(ValidationCircuitBreaker.class);

    private final int                      failureThreshold;
    private final Duration                 resetTimeout;
    private final AtomicInteger            consecutiveFailures;
    private final AtomicReference<Instant> lastFailureTime;

    /**
     * Creates circuit breaker with default configuration.
     * <p>
     * Defaults: 10 failure threshold, 1 minute reset timeout.
     * </p>
     */
    public ValidationCircuitBreaker() {
        this(10, Duration.ofMinutes(1));
    }

    /**
     * Creates circuit breaker with custom configuration.
     *
     * @param failureThreshold Number of consecutive failures before opening
     * @param resetTimeout     Time before circuit can close again
     */
    public ValidationCircuitBreaker(int failureThreshold, Duration resetTimeout) {
        this.failureThreshold = failureThreshold;
        this.resetTimeout = resetTimeout;
        this.consecutiveFailures = new AtomicInteger(0);
        this.lastFailureTime = new AtomicReference<>(null);
    }

    /**
     * Check if circuit breaker is open (should skip operations).
     *
     * @return true if circuit is open, false if closed
     */
    public boolean isOpen() {
        int failures = consecutiveFailures.get();
        if (failures < failureThreshold) {
            return false; // Circuit closed
        }

        // Circuit might be open - check if reset timeout elapsed
        var lastFailure = lastFailureTime.get();
        if (lastFailure != null && Duration.between(lastFailure, Instant.now()).compareTo(resetTimeout) >= 0) {
            // Reset timeout elapsed - try to close circuit
            if (consecutiveFailures.compareAndSet(failures, 0)) {
                log.info("Validation circuit breaker reset after timeout");
                return false; // Circuit closed
            }
        }

        return true; // Circuit open
    }

    /**
     * Record a successful operation.
     * <p>
     * Resets consecutive failure count.
     * </p>
     */
    public void recordSuccess() {
        int failures = consecutiveFailures.getAndSet(0);
        if (failures > 0) {
            log.debug("Validation circuit breaker recorded success after {} failures", failures);
        }
    }

    /**
     * Record a failed operation.
     * <p>
     * Increments consecutive failure count. When threshold is reached,
     * circuit opens and operations will be skipped until reset timeout.
     * </p>
     */
    public void recordFailure() {
        lastFailureTime.set(Instant.now());
        int failures = consecutiveFailures.incrementAndGet();

        if (failures == failureThreshold) {
            log.warn("Validation circuit breaker OPENED after {} consecutive failures (will reset after {})",
                     failures, resetTimeout);
        } else if (failures > failureThreshold) {
            log.debug("Validation circuit breaker still open ({} failures)", failures);
        } else {
            log.debug("Validation circuit breaker recorded failure {} of {}", failures, failureThreshold);
        }
    }

    /**
     * Get number of consecutive failures.
     *
     * @return Consecutive failure count
     */
    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    /**
     * Get failure threshold.
     *
     * @return Failure threshold
     */
    public int getFailureThreshold() {
        return failureThreshold;
    }

    /**
     * Get reset timeout duration.
     *
     * @return Reset timeout
     */
    public Duration getResetTimeout() {
        return resetTimeout;
    }

    /**
     * Manually reset circuit breaker to closed state.
     * <p>
     * Use for manual recovery or testing.
     * </p>
     */
    public void reset() {
        consecutiveFailures.set(0);
        lastFailureTime.set(null);
        log.info("Validation circuit breaker manually reset");
    }
}
