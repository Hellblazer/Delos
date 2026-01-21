/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.committee;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hellblazer.delos.cryptography.bls.BLSProvider;
import com.hellblazer.delos.cryptography.bls.ParsedBLSKey;
import com.hellblazer.delos.stereotomy.identifier.Identifier;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe cache for parsed BLS public keys used in committee signature verification.
 * <p>
 * This cache eliminates per-receipt parsing overhead by pre-computing and storing parsed
 * BLS public keys during view changes. Keys are cached using Caffeine with configurable
 * capacity and TTL limits.
 * <p>
 * The cache is designed to be cleared and repopulated atomically during view changes,
 * ensuring that verification always uses the current committee's keys.
 * <p>
 * Phase 1C-1-D-C: Committee Key Pre-computation Cache Implementation
 * <p>
 * Thread-safe: All operations are thread-safe via Caffeine's ConcurrentHashMap backing.
 * Immutable keys: ParsedBLSKey records are immutable, safe to share across threads.
 *
 * @author hal.hildebrand
 */
public class CommitteeKeyCache {

    /**
     * Maximum cache capacity (entries)
     */
    private static final int MAX_CAPACITY = 10_000;

    /**
     * Cache expiration after write
     */
    private static final Duration EXPIRE_AFTER_WRITE = Duration.ofMinutes(30);

    /**
     * Underlying Caffeine cache
     */
    private volatile Cache<Identifier, ParsedBLSKey> cache;

    /**
     * BLS provider for parsing keys
     */
    private final BLSProvider provider;

    /**
     * Create a new committee key cache.
     *
     * @param provider BLS provider for parsing public keys
     * @throws NullPointerException if provider is null
     */
    public CommitteeKeyCache(BLSProvider provider) {
        if (provider == null) {
            throw new NullPointerException("provider cannot be null");
        }
        this.provider = provider;
        this.cache = buildCache();
    }

    /**
     * Get a cached parsed key for a committee member.
     *
     * @param memberId Committee member identifier
     * @return Cached parsed key, or null if not in cache
     * @throws NullPointerException if memberId is null
     */
    public ParsedBLSKey get(Identifier memberId) {
        if (memberId == null) {
            throw new NullPointerException("memberId cannot be null");
        }
        return cache.getIfPresent(memberId);
    }

    /**
     * Get a cached parsed key, or parse and cache it on miss.
     * <p>
     * This method is thread-safe: if multiple threads concurrently request
     * the same key, only one will perform the parse operation.
     *
     * @param memberId  Committee member identifier
     * @param publicKey Raw BLS public key (48 bytes compressed)
     * @return Parsed BLS key (cached or newly parsed)
     * @throws NullPointerException     if any parameter is null
     * @throws IllegalArgumentException if publicKey is invalid format
     */
    public ParsedBLSKey getOrParse(Identifier memberId, byte[] publicKey) {
        if (memberId == null) {
            throw new NullPointerException("memberId cannot be null");
        }
        if (publicKey == null) {
            throw new NullPointerException("publicKey cannot be null");
        }

        return cache.get(memberId, key -> provider.parse(publicKey));
    }

    /**
     * Get cached parsed keys for multiple committee members.
     * <p>
     * Returns a list parallel to the input, with nulls for cache misses.
     * This method is optimized for batch retrieval during verification.
     *
     * @param memberIds Collection of committee member identifiers
     * @return List of parsed keys (nulls for misses), parallel to input
     * @throws NullPointerException if memberIds is null
     */
    public List<ParsedBLSKey> getAll(Collection<Identifier> memberIds) {
        if (memberIds == null) {
            throw new NullPointerException("memberIds cannot be null");
        }

        var results = new ArrayList<ParsedBLSKey>(memberIds.size());
        for (var memberId : memberIds) {
            results.add(cache.getIfPresent(memberId));
        }
        return results;
    }

    /**
     * Pre-compute and cache all committee member keys.
     * <p>
     * This method parses all provided keys and populates the cache.
     * Used during view changes to prepare for upcoming verification operations.
     * <p>
     * Existing cached keys are preserved unless overwritten.
     *
     * @param committeeMembers Map of member IDs to raw public keys (48 bytes each)
     * @throws NullPointerException     if committeeMembers is null
     * @throws IllegalArgumentException if any public key is invalid
     */
    public void precomputeCommittee(Map<Identifier, byte[]> committeeMembers) {
        if (committeeMembers == null) {
            throw new NullPointerException("committeeMembers cannot be null");
        }

        // Batch parse and cache all keys
        committeeMembers.forEach((memberId, publicKey) -> {
            var parsedKey = provider.parse(publicKey);
            cache.put(memberId, parsedKey);
        });
    }

    /**
     * Clear the cache and reset metrics.
     * <p>
     * This operation invalidates all cached keys and resets hit/miss/eviction counters.
     * Thread-safe: concurrent reads during clear will see either old or empty cache.
     */
    public void clear() {
        cache.invalidateAll();
        cache.cleanUp();
    }

    /**
     * Atomically clear the cache and precompute a new committee.
     * <p>
     * This method is designed for view changes: it ensures the cache contains
     * only the new committee's keys after completion.
     * <p>
     * Thread-safe: creates new cache instance for atomic replacement.
     *
     * @param newCommittee Map of new committee member IDs to public keys
     * @throws NullPointerException     if newCommittee is null
     * @throws IllegalArgumentException if any public key is invalid
     */
    public void clearAndPrecompute(Map<Identifier, byte[]> newCommittee) {
        if (newCommittee == null) {
            throw new NullPointerException("newCommittee cannot be null");
        }

        // Create fresh cache to ensure metrics reset
        var newCache = buildCache();

        // Populate new cache
        newCommittee.forEach((memberId, publicKey) -> {
            var parsedKey = provider.parse(publicKey);
            newCache.put(memberId, parsedKey);
        });

        // Atomic replacement
        this.cache = newCache;
    }

    /**
     * Get cache hit count since last clear.
     *
     * @return Number of cache hits
     */
    public long getHitCount() {
        return cache.stats().hitCount();
    }

    /**
     * Get cache miss count since last clear.
     *
     * @return Number of cache misses
     */
    public long getMissCount() {
        return cache.stats().missCount();
    }

    /**
     * Get cache eviction count since last clear.
     *
     * @return Number of entries evicted
     */
    public long getEvictionCount() {
        return cache.stats().evictionCount();
    }

    /**
     * Get current cache size.
     *
     * @return Number of entries currently cached
     */
    public long getSize() {
        return cache.estimatedSize();
    }

    /**
     * Build a new Caffeine cache instance with standard configuration.
     *
     * @return Configured cache
     */
    private Cache<Identifier, ParsedBLSKey> buildCache() {
        return Caffeine.newBuilder()
                       .maximumSize(MAX_CAPACITY)
                       .expireAfterWrite(EXPIRE_AFTER_WRITE)
                       .recordStats()
                       .build();
    }
}
