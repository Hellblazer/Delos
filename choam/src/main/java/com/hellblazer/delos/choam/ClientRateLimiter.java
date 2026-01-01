/*
 * Copyright (c) 2025, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam;

import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-client rate limiter using token bucket algorithm with sliding window.
 * Thread-safe implementation for controlling transaction submission rates.
 *
 * @author hal.hildebrand
 */
public class ClientRateLimiter {
    private final ReentrantLock lock = new ReentrantLock();
    private final int maxTokens;
    private final Duration window;
    private long windowStart;
    private int tokens;

    /**
     * Create a new rate limiter with specified capacity and time window.
     *
     * @param maxTokens Maximum number of tokens (requests) allowed per window
     * @param window Time duration of the rate limiting window
     */
    public ClientRateLimiter(int maxTokens, Duration window) {
        this.maxTokens = maxTokens;
        this.window = window;
        this.windowStart = System.currentTimeMillis();
        this.tokens = maxTokens;
    }

    /**
     * Attempt to acquire a token for a request.
     * Returns true if a token was successfully acquired, false if rate limited.
     *
     * @return true if request is allowed, false if rate limited
     */
    public boolean tryAcquire() {
        lock.lock();
        try {
            var now = System.currentTimeMillis();

            // Reset window if expired
            if (now - windowStart >= window.toMillis()) {
                windowStart = now;
                tokens = maxTokens;
            }

            // Check if tokens available
            if (tokens <= 0) {
                return false;
            }

            tokens--;
            return true;
        } finally {
            lock.unlock();
        }
    }
}
