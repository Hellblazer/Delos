/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.integration;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import com.hellblazer.delos.stereotomy.KERL;
import com.hellblazer.delos.stereotomy.KeyState;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import org.joou.ULong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Bridge to Stereotomy KERL verifier with caching and failure handling.
 * <p>
 * Provides KERI identifier verification for witness committee membership:
 * - 60s TTL cache (aligned with Fireflies epoch)
 * - Invalidation on view changes
 * - Retry fallback (100ms delay, single retry)
 * - Metrics tracking (hit rate, latency, fallback frequency)
 * </p>
 * <p>
 * Thread-safe: Uses ConcurrentHashMap for cache.
 * </p>
 */
public class WitnessKerlIntegration {

    private static final Logger log = LoggerFactory.getLogger(WitnessKerlIntegration.class);
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final Duration RETRY_DELAY = Duration.ofMillis(100);

    private final KERL kerl;
    private final ConcurrentHashMap<CacheKey, CachedKeyState> cache;
    private final Timer lookupTimer;
    private final Counter cacheHits;
    private final Counter cacheMisses;
    private final Counter fallbackRetries;

    /**
     * Create KERL integration with metrics tracking.
     *
     * @param kerl           KERL instance for KeyState lookup
     * @param meterRegistry Metrics registry for tracking
     */
    public WitnessKerlIntegration(KERL kerl, MeterRegistry meterRegistry) {
        this.kerl = kerl;
        this.cache = new ConcurrentHashMap<>();
        this.lookupTimer = Timer.builder("witness.kerl.lookup.time").register(meterRegistry);
        this.cacheHits = Counter.builder("witness.kerl.cache.hits").register(meterRegistry);
        this.cacheMisses = Counter.builder("witness.kerl.cache.misses").register(meterRegistry);
        this.fallbackRetries = Counter.builder("witness.kerl.fallback.retries").register(meterRegistry);
    }

    /**
     * Verify identifier with KERL lookup and caching.
     * <p>
     * Algorithm:
     * 1. Check cache (60s TTL)
     * 2. On miss: lookup via KERL
     * 3. On failure: retry after 100ms
     * 4. Cache result and return
     * </p>
     *
     * @param identifier     Identifier to verify
     * @param sequenceNumber Key event sequence number
     * @return KeyState if found, empty on failure
     */
    public Optional<KeyState> verifyIdentifier(Identifier identifier, long sequenceNumber) {
        var cacheKey = new CacheKey(identifier, sequenceNumber);

        // Check cache first
        var cached = getCachedKeyState(cacheKey);
        if (cached.isPresent()) {
            cacheHits.increment();
            return cached;
        }

        cacheMisses.increment();

        // Lookup via KERL with timing
        var keyState = lookupKeyState(identifier, sequenceNumber);

        // Fallback retry on failure
        if (keyState.isEmpty()) {
            log.debug("KERL lookup failed for identifier={}, sequenceNumber={}, retrying after {}ms",
                      identifier, sequenceNumber, RETRY_DELAY.toMillis());
            fallbackRetries.increment();

            try {
                Thread.sleep(RETRY_DELAY.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted during KERL retry delay", e);
                return Optional.empty();
            }

            keyState = lookupKeyState(identifier, sequenceNumber);
        }

        // Cache result if successful
        keyState.ifPresent(ks -> cacheKeyState(cacheKey, ks));

        return keyState;
    }

    /**
     * Lookup KeyState via KERL with timing.
     *
     * @param identifier     Identifier to lookup
     * @param sequenceNumber Sequence number
     * @return KeyState if found, empty on error
     */
    private Optional<KeyState> lookupKeyState(Identifier identifier, long sequenceNumber) {
        return lookupTimer.record(() -> {
            try {
                var keyState = kerl.getKeyState(identifier, ULong.valueOf(sequenceNumber));
                return Optional.ofNullable(keyState);
            } catch (Exception e) {
                log.warn("KERL lookup error for identifier={}, sequenceNumber={}", identifier, sequenceNumber, e);
                return Optional.empty();
            }
        });
    }

    /**
     * Cache KeyState with 60s TTL.
     *
     * @param key      Cache key
     * @param keyState KeyState to cache
     */
    private void cacheKeyState(CacheKey key, KeyState keyState) {
        var expiryTime = System.currentTimeMillis() + CACHE_TTL.toMillis();
        cache.put(key, new CachedKeyState(keyState, expiryTime));
    }

    /**
     * Get cached KeyState if not expired.
     *
     * @param key Cache key
     * @return KeyState if cached and valid, empty if expired/missing
     */
    private Optional<KeyState> getCachedKeyState(CacheKey key) {
        var cached = cache.get(key);
        if (cached == null) {
            return Optional.empty();
        }

        var now = System.currentTimeMillis();
        if (now > cached.expiryTime) {
            cache.remove(key);
            return Optional.empty();
        }

        return Optional.of(cached.keyState);
    }

    /**
     * Invalidate entire cache on view change.
     * Called from view change propagation (Phase A.5).
     */
    public void invalidateCacheOnViewChange() {
        var size = cache.size();
        cache.clear();
        log.debug("Invalidated KERL cache on view change, removed {} entries", size);
    }

    /**
     * Invalidate specific identifier from cache.
     *
     * @param identifier Identifier to invalidate
     */
    public void invalidateIdentifier(Identifier identifier) {
        cache.keySet().removeIf(key -> key.identifier.equals(identifier));
        log.debug("Invalidated KERL cache entries for identifier={}", identifier);
    }

    /**
     * Get cache size for monitoring.
     *
     * @return Current cache size
     */
    public int getCacheSize() {
        return cache.size();
    }

    /**
     * Get cache hit rate for monitoring.
     *
     * @return Hit rate (0.0-1.0)
     */
    public double getCacheHitRate() {
        var hits = cacheHits.count();
        var misses = cacheMisses.count();
        var total = hits + misses;
        return total == 0 ? 0.0 : (double) hits / total;
    }

    /**
     * Cache key combining identifier and sequence number.
     */
    private record CacheKey(Identifier identifier, long sequenceNumber) {
    }

    /**
     * Cached KeyState with expiry time.
     */
    private record CachedKeyState(KeyState keyState, long expiryTime) {
    }
}
