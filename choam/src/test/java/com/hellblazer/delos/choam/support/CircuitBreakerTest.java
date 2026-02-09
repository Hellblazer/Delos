/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.support.CircuitBreaker.CircuitBreakerOpenException;
import com.hellblazer.delos.choam.support.CircuitBreaker.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link CircuitBreaker}.
 * <p>
 * Tests state transitions, failure thresholds, and reset timeout behavior.
 * </p>
 *
 * @author hal.hildebrand
 */
class CircuitBreakerTest {

    private CircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        breaker = new CircuitBreaker(3, Duration.ofSeconds(1));
    }

    /**
     * Tests that circuit starts in CLOSED state.
     */
    @Test
    void initialState_IsClosed() {
        assertThat(breaker.getState()).isEqualTo(State.CLOSED);
        assertThat(breaker.getConsecutiveFailures()).isEqualTo(0);
    }

    /**
     * Tests successful execution in CLOSED state.
     */
    @Test
    void execute_SuccessInClosedState() throws Exception {
        // Given: Circuit is CLOSED

        // When: Execute successful operation
        var result = breaker.execute(() -> "success");

        // Then: Operation completes, circuit stays CLOSED
        assertThat(result).isEqualTo("success");
        assertThat(breaker.getState()).isEqualTo(State.CLOSED);
        assertThat(breaker.getConsecutiveFailures()).isEqualTo(0);
    }

    /**
     * Tests single failure in CLOSED state.
     */
    @Test
    void execute_SingleFailureInClosedState() {
        // Given: Circuit is CLOSED

        // When: Execute failing operation
        assertThatThrownBy(() -> breaker.execute(() -> {
            throw new RuntimeException("test failure");
        })).isInstanceOf(RuntimeException.class).hasMessage("test failure");

        // Then: Circuit stays CLOSED (threshold is 3)
        assertThat(breaker.getState()).isEqualTo(State.CLOSED);
        assertThat(breaker.getConsecutiveFailures()).isEqualTo(1);
    }

    /**
     * Tests circuit opens after failure threshold.
     */
    @Test
    void execute_OpensAfterFailureThreshold() {
        // Given: Circuit is CLOSED, threshold is 3

        // When: Execute 3 failing operations
        for (int i = 0; i < 3; i++) {
            final int attemptNumber = i;
            try {
                breaker.execute(() -> {
                    throw new RuntimeException("failure " + attemptNumber);
                });
            } catch (Exception ignored) {
            }
        }

        // Then: Circuit is OPEN
        assertThat(breaker.getState()).isEqualTo(State.OPEN);
        assertThat(breaker.getConsecutiveFailures()).isEqualTo(3);
    }

    /**
     * Tests that circuit fails fast when OPEN.
     */
    @Test
    void execute_FailsFastWhenOpen() {
        // Given: Circuit is OPEN
        openCircuit();

        // When: Attempt to execute operation
        assertThatThrownBy(() -> breaker.execute(() -> "should not execute"))
            .isInstanceOf(CircuitBreakerOpenException.class)
            .hasMessageContaining("Circuit breaker is OPEN");

        // Then: Circuit stays OPEN
        assertThat(breaker.getState()).isEqualTo(State.OPEN);
    }

    /**
     * Tests transition from OPEN to HALF_OPEN after reset timeout.
     */
    @Test
    void execute_TransitionsToHalfOpenAfterTimeout() throws Exception {
        // Given: Circuit is OPEN
        openCircuit();
        assertThat(breaker.getState()).isEqualTo(State.OPEN);

        // When: Wait for reset timeout (1 second)
        Thread.sleep(1100);

        // And: Execute successful operation
        var result = breaker.execute(() -> "success");

        // Then: Circuit transitioned OPEN → HALF_OPEN → CLOSED
        assertThat(result).isEqualTo("success");
        assertThat(breaker.getState()).isEqualTo(State.CLOSED);
        assertThat(breaker.getConsecutiveFailures()).isEqualTo(0);
    }

    /**
     * Tests HALF_OPEN success transitions to CLOSED.
     */
    @Test
    void execute_HalfOpenSuccessTransitionsToClosed() throws Exception {
        // Given: Circuit is HALF_OPEN
        openCircuit();
        Thread.sleep(1100);  // Wait for reset timeout

        // When: Execute successful test operation
        var result = breaker.execute(() -> "test success");

        // Then: Circuit transitioned to CLOSED
        assertThat(result).isEqualTo("test success");
        assertThat(breaker.getState()).isEqualTo(State.CLOSED);
        assertThat(breaker.getConsecutiveFailures()).isEqualTo(0);
    }

    /**
     * Tests HALF_OPEN failure reopens circuit.
     */
    @Test
    void execute_HalfOpenFailureReopensCircuit() throws InterruptedException {
        // Given: Circuit is HALF_OPEN
        openCircuit();
        Thread.sleep(1100);  // Wait for reset timeout

        // When: Execute failing test operation
        assertThatThrownBy(() -> breaker.execute(() -> {
            throw new RuntimeException("test failure");
        })).isInstanceOf(RuntimeException.class);

        // Then: Circuit reopened to OPEN
        assertThat(breaker.getState()).isEqualTo(State.OPEN);
    }

    /**
     * Tests that success in CLOSED state resets failure count.
     */
    @Test
    void execute_SuccessResetsFailureCount() throws Exception {
        // Given: 2 failures (below threshold)
        for (int i = 0; i < 2; i++) {
            try {
                breaker.execute(() -> {
                    throw new RuntimeException("failure");
                });
            } catch (Exception ignored) {
            }
        }
        assertThat(breaker.getConsecutiveFailures()).isEqualTo(2);

        // When: Execute successful operation
        breaker.execute(() -> "success");

        // Then: Failure count reset to 0
        assertThat(breaker.getConsecutiveFailures()).isEqualTo(0);
        assertThat(breaker.getState()).isEqualTo(State.CLOSED);
    }

    /**
     * Tests manual reset from OPEN to CLOSED.
     */
    @Test
    void reset_ManuallyCloseCircuit() {
        // Given: Circuit is OPEN
        openCircuit();
        assertThat(breaker.getState()).isEqualTo(State.OPEN);

        // When: Manually reset circuit
        breaker.reset();

        // Then: Circuit is CLOSED with zero failures
        assertThat(breaker.getState()).isEqualTo(State.CLOSED);
        assertThat(breaker.getConsecutiveFailures()).isEqualTo(0);
    }

    /**
     * Tests custom failure threshold.
     */
    @Test
    void customThreshold_RespectedInStateTransitions() {
        // Given: Circuit with threshold of 5
        var customBreaker = new CircuitBreaker(5, Duration.ofSeconds(1));

        // When: 4 failures (below threshold)
        for (int i = 0; i < 4; i++) {
            try {
                customBreaker.execute(() -> {
                    throw new RuntimeException("failure");
                });
            } catch (Exception ignored) {
            }
        }

        // Then: Circuit still CLOSED
        assertThat(customBreaker.getState()).isEqualTo(State.CLOSED);
        assertThat(customBreaker.getConsecutiveFailures()).isEqualTo(4);

        // When: 5th failure (reaches threshold)
        try {
            customBreaker.execute(() -> {
                throw new RuntimeException("failure 5");
            });
        } catch (Exception ignored) {
        }

        // Then: Circuit OPEN
        assertThat(customBreaker.getState()).isEqualTo(State.OPEN);
    }

    /**
     * Tests that circuit breaker doesn't execute operation when OPEN.
     */
    @Test
    void execute_DoesNotInvokeOperationWhenOpen() {
        // Given: Circuit is OPEN
        openCircuit();

        // When: Attempt to execute operation with side effect
        var invocationCount = new AtomicInteger(0);
        try {
            breaker.execute(() -> {
                invocationCount.incrementAndGet();
                return "should not execute";
            });
        } catch (Exception ignored) {
            // Expected - circuit is OPEN
        }

        // Then: Operation was not invoked
        assertThat(invocationCount.get()).isEqualTo(0);
    }

    /**
     * Tests concurrent access to circuit breaker is thread-safe.
     */
    @Test
    void execute_ThreadSafeUnderConcurrentAccess() throws InterruptedException {
        // Given: Circuit with high threshold
        var concurrentBreaker = new CircuitBreaker(100, Duration.ofSeconds(5));
        var successCount = new AtomicInteger(0);
        var failureCount = new AtomicInteger(0);

        // When: 10 threads execute 100 operations each
        var threads = new Thread[10];
        for (int t = 0; t < 10; t++) {
            threads[t] = Thread.ofVirtual().start(() -> {
                for (int i = 0; i < 100; i++) {
                    try {
                        concurrentBreaker.execute(() -> {
                            if (Math.random() < 0.5) {
                                throw new RuntimeException("random failure");
                            }
                            return "success";
                        });
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                    }
                }
            });
        }

        // Wait for all threads
        for (var thread : threads) {
            thread.join();
        }

        // Then: All operations counted (1000 total = success + failure)
        assertThat(successCount.get() + failureCount.get()).isEqualTo(1000);

        // And: Circuit breaker state is consistent
        var state = concurrentBreaker.getState();
        var failures = concurrentBreaker.getConsecutiveFailures();
        assertThat(state).isIn(State.CLOSED, State.OPEN);
        assertThat(failures).isLessThanOrEqualTo(failureCount.get());
    }

    /**
     * Helper method to open the circuit by causing threshold failures.
     */
    private void openCircuit() {
        for (int i = 0; i < 3; i++) {
            try {
                breaker.execute(() -> {
                    throw new RuntimeException("failure");
                });
            } catch (Exception ignored) {
            }
        }
    }
}
