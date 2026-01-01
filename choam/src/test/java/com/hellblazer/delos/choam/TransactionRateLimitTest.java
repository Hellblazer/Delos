/*
 * Copyright (c) 2025, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.choam.comm.Submitter;
import com.hellblazer.delos.choam.proto.SubmitResult;
import com.hellblazer.delos.choam.proto.Transaction;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for per-client transaction submission rate limiting
 *
 * @author hal.hildebrand
 */
public class TransactionRateLimitTest {

    @Test
    public void testRateLimitEnforcement() {
        // Create test setup
        var digestAlgorithm = DigestAlgorithm.DEFAULT;
        var client1 = digestAlgorithm.getOrigin();
        var client2 = digestAlgorithm.random();

        var rateLimiter = new PerClientRateLimiter(2); // 2 txns per client
        var submitter = new TestSubmitter();

        var txn1 = Transaction.newBuilder().setNonce(1).build();
        var txn2 = Transaction.newBuilder().setNonce(2).build();
        var txn3 = Transaction.newBuilder().setNonce(3).build();

        // Client 1 should be able to submit 2 transactions
        var result1 = rateLimiter.submit(txn1, client1, submitter);
        assertEquals(SubmitResult.Result.PUBLISHED, result1.getResult(),
                     "First transaction should be accepted");

        var result2 = rateLimiter.submit(txn2, client1, submitter);
        assertEquals(SubmitResult.Result.PUBLISHED, result2.getResult(),
                     "Second transaction should be accepted");

        // Third transaction from client1 should be rate limited
        var result3 = rateLimiter.submit(txn3, client1, submitter);
        assertEquals(SubmitResult.Result.RATE_LIMITED, result3.getResult(),
                     "Third transaction should be rate limited");

        // Client 2 should still be able to submit (independent rate limit)
        var result4 = rateLimiter.submit(txn1, client2, submitter);
        assertEquals(SubmitResult.Result.PUBLISHED, result4.getResult(),
                     "Client 2 should have independent rate limit");
    }

    @Test
    public void testRateLimitReset() throws InterruptedException {
        var digestAlgorithm = DigestAlgorithm.DEFAULT;
        var client = digestAlgorithm.getOrigin();

        var rateLimiter = new PerClientRateLimiter(1); // 1 txn per client, 100ms window
        rateLimiter.setWindowMs(100);
        var submitter = new TestSubmitter();

        var txn1 = Transaction.newBuilder().setNonce(1).build();
        var txn2 = Transaction.newBuilder().setNonce(2).build();

        // First transaction should succeed
        var result1 = rateLimiter.submit(txn1, client, submitter);
        assertEquals(SubmitResult.Result.PUBLISHED, result1.getResult());

        // Second transaction should be rate limited
        var result2 = rateLimiter.submit(txn2, client, submitter);
        assertEquals(SubmitResult.Result.RATE_LIMITED, result2.getResult());

        // Wait for window to expire
        Thread.sleep(150);

        // Third transaction should succeed after window reset
        var result3 = rateLimiter.submit(txn1, client, submitter);
        assertEquals(SubmitResult.Result.PUBLISHED, result3.getResult(),
                     "Transaction should succeed after rate limit window expires");
    }

    @Test
    public void testConcurrentClientSubmissions() throws InterruptedException {
        var digestAlgorithm = DigestAlgorithm.DEFAULT;
        var rateLimiter = new PerClientRateLimiter(5);
        var submitter = new TestSubmitter();

        // Simulate multiple clients submitting concurrently
        var threads = new Thread[10];
        var successCount = new ConcurrentHashMap<Digest, Integer>();
        var rateLimitedCount = new ConcurrentHashMap<Digest, Integer>();

        for (int i = 0; i < threads.length; i++) {
            final var clientId = digestAlgorithm.random();
            successCount.put(clientId, 0);
            rateLimitedCount.put(clientId, 0);

            threads[i] = new Thread(() -> {
                for (int j = 0; j < 10; j++) {
                    var txn = Transaction.newBuilder().setNonce(j).build();
                    var result = rateLimiter.submit(txn, clientId, submitter);

                    if (result.getResult() == SubmitResult.Result.PUBLISHED) {
                        successCount.compute(clientId, (k, v) -> v + 1);
                    } else if (result.getResult() == SubmitResult.Result.RATE_LIMITED) {
                        rateLimitedCount.compute(clientId, (k, v) -> v + 1);
                    }
                }
            });
            threads[i].start();
        }

        for (var thread : threads) {
            thread.join();
        }

        // Each client should have exactly 5 successful submissions and 5 rate limited
        for (var entry : successCount.entrySet()) {
            assertEquals(5, entry.getValue(),
                         "Each client should have 5 successful submissions");
            assertEquals(5, rateLimitedCount.get(entry.getKey()),
                         "Each client should have 5 rate limited submissions");
        }
    }

    /**
     * Simple per-client rate limiter implementation for testing
     */
    static class PerClientRateLimiter {
        private final int maxPerClient;
        private final ConcurrentHashMap<Digest, ClientRateLimit> limits = new ConcurrentHashMap<>();
        private long windowMs = 1000; // Default 1 second window

        PerClientRateLimiter(int maxPerClient) {
            this.maxPerClient = maxPerClient;
        }

        void setWindowMs(long windowMs) {
            this.windowMs = windowMs;
        }

        SubmitResult submit(Transaction txn, Digest from, Submitter submitter) {
            var limit = limits.computeIfAbsent(from, k -> new ClientRateLimit());

            if (!limit.tryAcquire(windowMs, maxPerClient)) {
                return SubmitResult.newBuilder().setResult(SubmitResult.Result.RATE_LIMITED).build();
            }

            return submitter.submit(txn, from);
        }

        static class ClientRateLimit {
            private final ReentrantLock lock = new ReentrantLock();
            private long windowStart = System.currentTimeMillis();
            private int count = 0;

            boolean tryAcquire(long windowMs, int maxPerWindow) {
                lock.lock();
                try {
                    var now = System.currentTimeMillis();

                    // Reset window if expired
                    if (now - windowStart >= windowMs) {
                        windowStart = now;
                        count = 0;
                    }

                    if (count >= maxPerWindow) {
                        return false;
                    }

                    count++;
                    return true;
                } finally {
                    lock.unlock();
                }
            }
        }
    }

    /**
     * Test submitter that always succeeds
     */
    static class TestSubmitter implements Submitter {
        @Override
        public SubmitResult submit(Transaction request, Digest from) {
            return SubmitResult.newBuilder().setResult(SubmitResult.Result.PUBLISHED).build();
        }
    }
}
