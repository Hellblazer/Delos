/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.archipelago;

import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Thread-safe channel pool keyed by route target string.
 * Channels are created lazily on first request and evicted when idle or unusable.
 * Uses ConcurrentHashMap for lock-free operation and scheduled eviction sweeps.
 * <p>
 * Thread safety:
 * - borrowChannel(): ConcurrentHashMap.compute() provides per-key locking
 * - PoolEntry operations: AtomicInteger refcount for lock-free borrow/release
 * - evictIdleChannels(): Weakly consistent iteration, per-key lock on remove
 * <p>
 * Performance characteristics:
 * - borrowChannel(): O(1) average, microsecond-scale contention on same key
 * - eviction: O(n) where n = pool size, runs asynchronously on schedule
 * - memory: O(maxCachedChannels) bounded by configuration
 *
 * @author hal.hildebrand
 */
class CachedChannelPool implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(CachedChannelPool.class);

    /**
     * Pool entry for a single route target.
     * Tracks the channel, reference count, and last access time.
     */
    static class PoolEntry {
        final ManagedChannel channel;
        final String routeTarget;
        final AtomicInteger refCount = new AtomicInteger(0);
        volatile long lastAccessedNanos = System.nanoTime();

        PoolEntry(String routeTarget, ManagedChannel channel) {
            this.routeTarget = routeTarget;
            this.channel = channel;
        }

        /**
         * Borrow the channel, incrementing refcount and updating access time.
         * Returns a PooledManagedChannel wrapper whose shutdown() decrements refcount.
         */
        ManagedChannel borrow() {
            refCount.incrementAndGet();
            lastAccessedNanos = System.nanoTime();
            return new PooledManagedChannel(channel, this);
        }

        /**
         * Release the channel by decrementing refcount.
         * Called by PooledManagedChannel.shutdown().
         */
        void release() {
            refCount.decrementAndGet();
        }

        /**
         * Check if the entry is idle (refcount <= 0 and last access beyond timeout).
         */
        boolean isIdle(Duration timeout) {
            return refCount.get() <= 0
                && System.nanoTime() - lastAccessedNanos > timeout.toNanos();
        }

        /**
         * Check if the underlying channel is unusable (shutdown or transient failure).
         */
        boolean isUnusable() {
            var state = channel.getState(false);
            return state == ConnectivityState.SHUTDOWN
                || state == ConnectivityState.TRANSIENT_FAILURE;
        }
    }

    private final ConcurrentHashMap<String, PoolEntry> pool = new ConcurrentHashMap<>();
    private final Function<String, ManagedChannel> channelFactory;
    private final ChannelCacheConfig config;
    private final ScheduledExecutorService evictor;
    private volatile boolean closed = false;

    /**
     * Create a new channel pool with the given factory and configuration.
     *
     * @param channelFactory Factory function to create channels for route targets
     * @param config         Cache configuration
     */
    CachedChannelPool(Function<String, ManagedChannel> channelFactory, ChannelCacheConfig config) {
        this.channelFactory = channelFactory;
        this.config = config;
        this.evictor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("channel-pool-evictor-", 0).factory()
        );
        this.evictor.scheduleAtFixedRate(
            this::evictIdleChannels,
            config.evictionInterval().toMillis(),
            config.evictionInterval().toMillis(),
            TimeUnit.MILLISECONDS
        );
    }

    /**
     * Get or create a pooled channel for the given route target.
     * Returns a PooledManagedChannel whose shutdown() decrements refcount.
     * <p>
     * Thread-safe: Uses ConcurrentHashMap.compute() for atomic get-or-create.
     *
     * @param routeTarget The route target string (e.g., context digest or member ID)
     * @return A pooled channel wrapper
     */
    ManagedChannel borrowChannel(String routeTarget) {
        if (closed) {
            throw new IllegalStateException("Channel pool is closed");
        }

        var entry = pool.compute(routeTarget, (key, existing) -> {
            // Reuse existing entry if healthy
            if (existing != null && !existing.isUnusable()) {
                return existing;
            }

            // Replace broken channel
            if (existing != null) {
                log.debug("Replacing broken channel for route: {}", key);
                existing.channel.shutdown();
            }

            // Enforce max size by evicting oldest idle before creating new
            if (pool.size() >= config.maxCachedChannels()) {
                evictOne();
            }

            // Create new channel
            log.debug("Creating new cached channel for route: {}", key);
            return new PoolEntry(key, channelFactory.apply(key));
        });

        return entry.borrow();
    }

    /**
     * Scheduled eviction task: remove channels that are idle beyond timeout or unusable.
     * Runs asynchronously on eviction thread.
     */
    private void evictIdleChannels() {
        if (closed) {
            return;
        }

        pool.entrySet().removeIf(e -> {
            var entry = e.getValue();
            if (entry.isIdle(config.idleTimeout()) || entry.isUnusable()) {
                log.debug("Evicting cached channel for route: {}", entry.routeTarget);
                entry.channel.shutdown();
                return true;
            }
            return false;
        });
    }

    /**
     * Evict one idle channel (oldest last access) to make room for a new entry.
     * Called when pool reaches max size.
     */
    private void evictOne() {
        // Find the entry with oldest lastAccessed among idle entries
        pool.entrySet().stream()
            .filter(e -> e.getValue().refCount.get() <= 0)
            .min((a, b) -> Long.compare(a.getValue().lastAccessedNanos, b.getValue().lastAccessedNanos))
            .ifPresent(oldest -> {
                log.debug("Evicting oldest idle channel for route: {}", oldest.getKey());
                pool.remove(oldest.getKey());
                oldest.getValue().channel.shutdown();
            });
    }

    /**
     * Close the pool, shutting down all cached channels and stopping eviction.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        evictor.shutdown();
        pool.values().forEach(entry -> {
            try {
                entry.channel.shutdown();
            } catch (Exception e) {
                log.debug("Error closing cached channel for: {}", entry.routeTarget, e);
            }
        });
        pool.clear();
    }

    /**
     * Get the current pool size (for metrics and testing).
     */
    int size() {
        return pool.size();
    }
}
