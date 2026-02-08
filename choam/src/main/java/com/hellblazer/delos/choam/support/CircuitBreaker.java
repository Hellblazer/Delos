/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Circuit breaker pattern implementation for fault-tolerant recovery operations.
 * <p>
 * Prevents cascading failures by "opening" the circuit after consecutive failures
 * exceed a threshold. After a reset timeout, the circuit transitions to half-open
 * state for testing recovery before fully closing.
 * </p>
 *
 * <h2>States</h2>
 * <ul>
 *   <li><b>CLOSED</b>: Normal operation, requests pass through</li>
 *   <li><b>OPEN</b>: Circuit tripped, requests fail fast (no execution)</li>
 *   <li><b>HALF_OPEN</b>: Testing state, single request allowed to test recovery</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 * <ul>
 *   <li><b>Failure Threshold</b>: Consecutive failures before opening (default: 3)</li>
 *   <li><b>Reset Timeout</b>: Time before OPEN → HALF_OPEN transition (default: 5min)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>
 * This class is thread-safe using atomic operations for state transitions.
 * </p>
 *
 * @author hal.hildebrand
 */
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    /**
     * Circuit breaker states.
     */
    public enum State {
        /** Normal operation - requests pass through */
        CLOSED,
        /** Circuit tripped - requests fail fast */
        OPEN,
        /** Testing recovery - single request allowed */
        HALF_OPEN
    }

    private final int                      failureThreshold;
    private final Duration                 resetTimeout;
    private final AtomicReference<State>   state;
    private final AtomicInteger            consecutiveFailures;
    private final AtomicReference<Instant> lastFailureTime;

    /**
     * Creates a circuit breaker with default configuration.
     * <p>
     * Defaults: 3 failure threshold, 5 minute reset timeout.
     */
    public CircuitBreaker() {
        this(3, Duration.ofMinutes(5));
    }

    /**
     * Creates a circuit breaker with custom configuration.
     *
     * @param failureThreshold Number of consecutive failures before opening
     * @param resetTimeout     Time before OPEN → HALF_OPEN transition
     */
    public CircuitBreaker(int failureThreshold, Duration resetTimeout) {
        this.failureThreshold = failureThreshold;
        this.resetTimeout = resetTimeout;
        this.state = new AtomicReference<>(State.CLOSED);
        this.consecutiveFailures = new AtomicInteger(0);
        this.lastFailureTime = new AtomicReference<>(null);
    }

    /**
     * Executes an operation through the circuit breaker.
     * <p>
     * Behavior by state:
     * <ul>
     *   <li><b>CLOSED</b>: Execute operation, record success/failure</li>
     *   <li><b>OPEN</b>: Fail fast if reset timeout not elapsed, else transition to HALF_OPEN and execute</li>
     *   <li><b>HALF_OPEN</b>: Execute single test operation, close on success or reopen on failure</li>
     * </ul>
     *
     * @param operation The operation to execute
     * @param <T>       Return type
     * @return Operation result
     * @throws CircuitBreakerOpenException if circuit is OPEN and reset timeout not elapsed
     * @throws Exception                   if operation throws
     */
    public <T> T execute(Supplier<T> operation) throws Exception {
        var currentState = state.get();

        // Check if circuit should transition OPEN → HALF_OPEN
        if (currentState == State.OPEN) {
            var lastFailure = lastFailureTime.get();
            if (lastFailure != null && Duration.between(lastFailure, Instant.now()).compareTo(resetTimeout) >= 0) {
                log.info("Circuit breaker transitioning OPEN → HALF_OPEN (reset timeout elapsed)");
                state.set(State.HALF_OPEN);
                currentState = State.HALF_OPEN;
            } else {
                throw new CircuitBreakerOpenException("Circuit breaker is OPEN, failing fast");
            }
        }

        try {
            // Execute operation
            T result = operation.get();

            // Success - handle state transition
            if (currentState == State.HALF_OPEN) {
                log.info("Circuit breaker test request succeeded, transitioning HALF_OPEN → CLOSED");
                state.set(State.CLOSED);
                consecutiveFailures.set(0);
            } else if (currentState == State.CLOSED) {
                // Reset failure count on success in CLOSED state
                consecutiveFailures.set(0);
            }

            return result;

        } catch (Exception e) {
            // Failure - handle state transition
            recordFailure(currentState);
            throw e;
        }
    }

    /**
     * Records a failure and potentially opens the circuit.
     *
     * @param currentState The state when failure occurred
     */
    private void recordFailure(State currentState) {
        lastFailureTime.set(Instant.now());

        if (currentState == State.HALF_OPEN) {
            // Test request failed, reopen circuit
            log.warn("Circuit breaker test request failed, transitioning HALF_OPEN → OPEN");
            state.set(State.OPEN);
            consecutiveFailures.set(failureThreshold); // Keep circuit open
        } else if (currentState == State.CLOSED) {
            int failures = consecutiveFailures.incrementAndGet();
            log.debug("Circuit breaker recorded failure {} of {}", failures, failureThreshold);

            if (failures >= failureThreshold) {
                log.warn("Circuit breaker failure threshold reached ({}/{}), transitioning CLOSED → OPEN",
                         failures, failureThreshold);
                state.set(State.OPEN);
            }
        }
    }

    /**
     * Gets the current circuit state.
     *
     * @return Current state
     */
    public State getState() {
        return state.get();
    }

    /**
     * Gets the number of consecutive failures.
     *
     * @return Consecutive failure count
     */
    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    /**
     * Resets the circuit breaker to CLOSED state.
     * <p>
     * Use this for manual recovery or testing.
     * </p>
     */
    public void reset() {
        state.set(State.CLOSED);
        consecutiveFailures.set(0);
        lastFailureTime.set(null);
        log.info("Circuit breaker manually reset to CLOSED");
    }

    /**
     * Exception thrown when circuit breaker is OPEN.
     */
    public static class CircuitBreakerOpenException extends RuntimeException {
        public CircuitBreakerOpenException(String message) {
            super(message);
        }
    }
}
