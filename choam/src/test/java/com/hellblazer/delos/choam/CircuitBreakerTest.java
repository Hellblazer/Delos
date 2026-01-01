/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test circuit breaker behavior for synchronization operations
 *
 * @author hal.hildebrand
 */
class CircuitBreakerTest {

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        circuitBreaker = new CircuitBreaker(3, Duration.ofMillis(100));
    }

    @Test
    void testInitialStateClosed() {
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
        assertTrue(circuitBreaker.isCallPermitted());
    }

    @Test
    void testSuccessfulCallsStayInClosed() {
        for (var i = 0; i < 10; i++) {
            circuitBreaker.recordSuccess();
        }
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
        assertTrue(circuitBreaker.isCallPermitted());
    }

    @Test
    void testFailuresOpenCircuit() {
        // Record failures up to threshold
        circuitBreaker.recordFailure();
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());

        circuitBreaker.recordFailure();
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());

        circuitBreaker.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
        assertFalse(circuitBreaker.isCallPermitted());
    }

    @Test
    void testOpenCircuitRejectsCall() {
        // Open the circuit
        for (var i = 0; i < 3; i++) {
            circuitBreaker.recordFailure();
        }

        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
        assertFalse(circuitBreaker.isCallPermitted());
    }

    @Test
    void testOpenToHalfOpenTransition() throws InterruptedException {
        // Open the circuit
        for (var i = 0; i < 3; i++) {
            circuitBreaker.recordFailure();
        }

        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());

        // Wait for timeout
        Thread.sleep(150);

        // Next call attempt should transition to HALF_OPEN
        assertTrue(circuitBreaker.isCallPermitted());
        assertEquals(CircuitBreaker.State.HALF_OPEN, circuitBreaker.getState());
    }

    @Test
    void testHalfOpenSuccessClosesCircuit() throws InterruptedException {
        // Open the circuit
        for (var i = 0; i < 3; i++) {
            circuitBreaker.recordFailure();
        }

        // Wait for timeout and transition to HALF_OPEN
        Thread.sleep(150);
        assertTrue(circuitBreaker.isCallPermitted());

        // Success in HALF_OPEN should close circuit
        circuitBreaker.recordSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
        assertTrue(circuitBreaker.isCallPermitted());
    }

    @Test
    void testHalfOpenFailureReopensCircuit() throws InterruptedException {
        // Open the circuit
        for (var i = 0; i < 3; i++) {
            circuitBreaker.recordFailure();
        }

        // Wait for timeout and transition to HALF_OPEN
        Thread.sleep(150);
        assertTrue(circuitBreaker.isCallPermitted());

        // Failure in HALF_OPEN should reopen circuit
        circuitBreaker.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
        assertFalse(circuitBreaker.isCallPermitted());
    }

    @Test
    void testSuccessResetsFailureCount() {
        // Record some failures
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();

        // Success should reset
        circuitBreaker.recordSuccess();

        // Need 3 more failures to open
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());

        circuitBreaker.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
    }

    @Test
    void testConcurrentAccess() throws InterruptedException {
        var threadCount = 10;
        var latch = new CountDownLatch(threadCount);
        var successCount = new AtomicInteger(0);
        var failureCount = new AtomicInteger(0);

        for (var i = 0; i < threadCount; i++) {
            var threadNum = i;
            Thread.ofVirtual().start(() -> {
                try {
                    if (circuitBreaker.isCallPermitted()) {
                        if (threadNum % 2 == 0) {
                            circuitBreaker.recordSuccess();
                            successCount.incrementAndGet();
                        } else {
                            circuitBreaker.recordFailure();
                            failureCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        // Verify state is consistent
        var state = circuitBreaker.getState();
        assertTrue(state == CircuitBreaker.State.CLOSED || state == CircuitBreaker.State.OPEN);
    }

    @Test
    void testExecuteSupplierSuccess() {
        var result = circuitBreaker.execute(() -> "success");
        assertEquals("success", result);
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
    }

    @Test
    void testExecuteSupplierFailure() {
        assertThrows(RuntimeException.class, () -> circuitBreaker.execute(() -> {
            throw new RuntimeException("test failure");
        }));

        assertEquals(1, circuitBreaker.getFailureCount());
    }

    @Test
    void testExecuteRunnableSuccess() {
        var executed = new AtomicInteger(0);
        circuitBreaker.execute(() -> executed.incrementAndGet());

        assertEquals(1, executed.get());
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
    }

    @Test
    void testExecuteBlockedInOpenState() {
        // Open the circuit
        for (var i = 0; i < 3; i++) {
            circuitBreaker.recordFailure();
        }

        var executed = new AtomicInteger(0);
        circuitBreaker.execute(() -> executed.incrementAndGet());

        // Should not execute
        assertEquals(0, executed.get());
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
    }

    @Test
    void testReset() {
        // Open the circuit
        for (var i = 0; i < 3; i++) {
            circuitBreaker.recordFailure();
        }

        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());

        circuitBreaker.reset();

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
        assertEquals(0, circuitBreaker.getFailureCount());
        assertTrue(circuitBreaker.isCallPermitted());
    }

    @Test
    void testMetrics() {
        circuitBreaker.recordSuccess();
        circuitBreaker.recordSuccess();
        circuitBreaker.recordFailure();

        assertEquals(2, circuitBreaker.getSuccessCount());
        assertEquals(1, circuitBreaker.getFailureCount());
    }

    @Test
    void testCustomThreshold() {
        var cb = new CircuitBreaker(5, Duration.ofSeconds(1));

        // 4 failures should not open
        for (var i = 0; i < 4; i++) {
            cb.recordFailure();
        }
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());

        // 5th failure should open
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
    }
}
