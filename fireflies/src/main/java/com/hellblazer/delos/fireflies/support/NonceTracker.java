/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies.support;

import com.google.protobuf.ByteString;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Thread-safe tracker for nonces used in join messages to prevent replay attacks. Uses a sliding window approach where
 * nonces are tracked with their timestamp and automatically expired after the configured TTL.
 *
 * @author hal.hildebrand
 */
public class NonceTracker {

    private final ConcurrentHashMap<ByteString, Long> seenNonces;
    private final long                                ttlMillis;
    private final ScheduledExecutorService            scheduler;

    /**
     * Create a new NonceTracker with the specified TTL for nonces
     *
     * @param ttl the time-to-live for nonces; after this duration, nonces are eligible for cleanup
     */
    public NonceTracker(Duration ttl) {
        this.seenNonces = new ConcurrentHashMap<>();
        this.ttlMillis = ttl.toMillis();
        this.scheduler = Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory());

        // Schedule periodic cleanup every TTL/2 to ensure timely removal of expired nonces
        scheduler.scheduleAtFixedRate(this::cleanupExpired, ttlMillis / 2, ttlMillis / 2, TimeUnit.MILLISECONDS);
    }

    /**
     * Check if a nonce has been seen before, and if not, record it with the current timestamp
     *
     * @param nonce     the nonce to check
     * @param timestamp the timestamp from the message (epoch millis)
     * @return true if this is a new nonce (not seen before), false if it's a replay
     */
    public boolean checkAndTrack(ByteString nonce, long timestamp) {
        // Check if nonce already exists - if so, it's a replay attack
        var existingTimestamp = seenNonces.putIfAbsent(nonce, timestamp);
        return existingTimestamp == null;
    }

    /**
     * Check if a timestamp is within the acceptable window (not too old)
     *
     * @param timestamp the timestamp to check (epoch millis)
     * @return true if the timestamp is within the TTL window, false if too old
     */
    public boolean isTimestampValid(long timestamp) {
        var now = System.currentTimeMillis();
        var age = now - timestamp;

        // Reject if timestamp is in the future (with small tolerance for clock skew)
        if (age < -5000) { // 5 second tolerance for clock skew
            return false;
        }

        // Reject if timestamp is older than TTL
        return age <= ttlMillis;
    }

    /**
     * Clean up expired nonces from the tracker
     */
    public void cleanupExpired() {
        var now = System.currentTimeMillis();
        seenNonces.entrySet().removeIf(entry -> now - entry.getValue() > ttlMillis);
    }

    /**
     * Shutdown the cleanup scheduler
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Get the number of tracked nonces (for testing/monitoring)
     */
    public int size() {
        return seenNonces.size();
    }

    /**
     * Clear all tracked nonces (for testing)
     */
    public void clear() {
        seenNonces.clear();
    }
}
