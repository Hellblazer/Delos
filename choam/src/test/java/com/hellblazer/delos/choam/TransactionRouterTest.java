/*
 * Copyright (c) 2025, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.choam.proto.SubmitResult;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ClientRateLimiter functionality.
 *
 * @author hal.hildebrand
 */
public class TransactionRouterTest {

    private final DigestAlgorithm digestAlgorithm = DigestAlgorithm.DEFAULT;

    @Test
    public void testClientRateLimiter_Basic() {
        var limiter = new ClientRateLimiter(3, Duration.ofSeconds(1));

        // Should allow 3 requests
        assertTrue(limiter.tryAcquire(), "First request should succeed");
        assertTrue(limiter.tryAcquire(), "Second request should succeed");
        assertTrue(limiter.tryAcquire(), "Third request should succeed");

        // Fourth should be rate limited
        assertFalse(limiter.tryAcquire(), "Fourth request should be rate limited");
    }

    @Test
    public void testClientRateLimiter_WindowReset() throws InterruptedException {
        var limiter = new ClientRateLimiter(2, Duration.ofMillis(100));

        // Use up tokens
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire(), "Should be rate limited");

        // Wait for window to reset
        Thread.sleep(150);

        // Should be allowed again
        assertTrue(limiter.tryAcquire(), "Should be allowed after window reset");
    }

    @Test
    public void testClientRateLimiter_Concurrent() throws InterruptedException {
        var limiter = new ClientRateLimiter(100, Duration.ofSeconds(1));
        var successCount = new java.util.concurrent.atomic.AtomicInteger(0);
        var failedCount = new java.util.concurrent.atomic.AtomicInteger(0);

        var threads = new Thread[10];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 20; j++) {
                    if (limiter.tryAcquire()) {
                        successCount.incrementAndGet();
                    } else {
                        failedCount.incrementAndGet();
                    }
                }
            });
            threads[i].start();
        }

        for (var thread : threads) {
            thread.join();
        }

        assertEquals(100, successCount.get(), "Should allow exactly 100 requests");
        assertEquals(100, failedCount.get(), "Should reject exactly 100 requests");
    }
}
