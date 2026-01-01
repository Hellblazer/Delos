/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Circuit breaker implementation for synchronization operations. Protects against
 * cascade failures by tracking failure rates and temporarily disabling operations
 * when threshold is exceeded.
 * <p>
 * States:
 * <ul>
 *   <li>CLOSED - Normal operation, calls permitted</li>
 *   <li>OPEN - Failure threshold exceeded, calls blocked</li>
 *   <li>HALF_OPEN - Testing recovery, limited calls permitted</li>
 * </ul>
 *
 * @author hal.hildebrand
 */
public class CircuitBreaker {
    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    /**
     * Circuit breaker states
     */
    public enum State {
        /** Normal operation - calls are permitted */
        CLOSED,
        /** Failure threshold exceeded - calls are blocked */
        OPEN,
        /** Testing recovery - calls permitted to test if service recovered */
        HALF_OPEN
    }

    private final int              failureThreshold;
    private final Duration         timeout;
    private final ReentrantLock    lock = new ReentrantLock();
    private       State            state;
    private       int              failureCount;
    private       int              successCount;
    private       long             lastFailureTime;

    /**
     * Create a circuit breaker with specified threshold and timeout.
     *
     * @param failureThreshold number of consecutive failures before opening circuit
     * @param timeout          duration to wait before attempting recovery from OPEN state
     */
    public CircuitBreaker(int failureThreshold, Duration timeout) {
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException("Failure threshold must be positive");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
        this.failureThreshold = failureThreshold;
        this.timeout = timeout;
        this.state = State.CLOSED;
        this.failureCount = 0;
        this.successCount = 0;
        this.lastFailureTime = 0;
    }

    /**
     * Get the current state of the circuit breaker.
     *
     * @return current state
     */
    public State getState() {
        lock.lock();
        try {
            return state;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get the current failure count.
     *
     * @return number of consecutive failures
     */
    public int getFailureCount() {
        lock.lock();
        try {
            return failureCount;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get the current success count.
     *
     * @return total number of successful calls
     */
    public int getSuccessCount() {
        lock.lock();
        try {
            return successCount;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Check if a call is permitted in the current state.
     *
     * @return true if call is permitted, false otherwise
     */
    public boolean isCallPermitted() {
        lock.lock();
        try {
            switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                if (shouldAttemptReset()) {
                    transitionToHalfOpen();
                    return true;
                }
                return false;
            case HALF_OPEN:
                return true;
            default:
                return false;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Record a successful operation.
     */
    public void recordSuccess() {
        lock.lock();
        try {
            successCount++;
            failureCount = 0;  // Reset consecutive failures
            if (state == State.HALF_OPEN) {
                transitionToClosed();
                log.info("Circuit breaker recovered, transitioning to CLOSED");
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Record a failed operation.
     */
    public void recordFailure() {
        lock.lock();
        try {
            failureCount++;
            lastFailureTime = System.nanoTime();

            switch (state) {
            case CLOSED:
                if (failureCount >= failureThreshold) {
                    transitionToOpen();
                    log.warn("Circuit breaker opened after {} consecutive failures", failureCount);
                }
                break;
            case HALF_OPEN:
                transitionToOpen();
                log.warn("Circuit breaker reopened after failure during recovery attempt");
                break;
            case OPEN:
                // Already open, nothing to do
                break;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Execute a supplier function with circuit breaker protection.
     * If circuit is open, returns null without executing the supplier.
     *
     * @param supplier the function to execute
     * @param <T>      return type
     * @return result of supplier or null if circuit is open
     */
    public <T> T execute(Supplier<T> supplier) {
        if (!isCallPermitted()) {
            log.debug("Circuit breaker OPEN, call blocked");
            return null;
        }

        try {
            var result = supplier.get();
            recordSuccess();
            return result;
        } catch (Exception e) {
            recordFailure();
            throw e;
        }
    }

    /**
     * Execute a runnable with circuit breaker protection.
     * If circuit is open, the runnable is not executed.
     *
     * @param runnable the code to execute
     */
    public void execute(Runnable runnable) {
        if (!isCallPermitted()) {
            log.debug("Circuit breaker OPEN, call blocked");
            return;
        }

        try {
            runnable.run();
            recordSuccess();
        } catch (Exception e) {
            recordFailure();
            throw e;
        }
    }

    /**
     * Reset the circuit breaker to initial state.
     */
    public void reset() {
        lock.lock();
        try {
            state = State.CLOSED;
            failureCount = 0;
            successCount = 0;
            lastFailureTime = 0;
            log.info("Circuit breaker manually reset to CLOSED");
        } finally {
            lock.unlock();
        }
    }

    /**
     * Check if enough time has passed to attempt reset from OPEN state.
     *
     * @return true if timeout has elapsed
     */
    private boolean shouldAttemptReset() {
        var elapsed = Duration.ofNanos(System.nanoTime() - lastFailureTime);
        return elapsed.compareTo(timeout) >= 0;
    }

    /**
     * Transition to CLOSED state.
     */
    private void transitionToClosed() {
        state = State.CLOSED;
        failureCount = 0;
    }

    /**
     * Transition to OPEN state.
     */
    private void transitionToOpen() {
        state = State.OPEN;
    }

    /**
     * Transition to HALF_OPEN state.
     */
    private void transitionToHalfOpen() {
        state = State.HALF_OPEN;
        log.info("Circuit breaker attempting recovery, transitioning to HALF_OPEN");
    }
}
