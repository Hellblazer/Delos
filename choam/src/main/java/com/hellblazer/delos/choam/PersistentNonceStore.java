/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.cryptography.Digest;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Persistent nonce tracker using H2 MVStore for disk storage.
 * <p>
 * Features:
 * - Persists (source, nonce) pairs to disk for replay protection across restarts
 * - Strict nonce ordering: only accepts the next expected nonce (prevents replay attacks)
 * - Block height-based expiration: entries expire after 10,000 blocks
 * - Thread-safe for concurrent access
 * - Automatic cleanup of expired entries
 * <p>
 * Storage format:
 * - Key: source.toString()
 * - Value: NonceEntry(currentNonce, creationHeight)
 * <p>
 * Nonce validation:
 * - validateNonce(source, N) returns true only if N equals the next expected nonce
 * - Once a nonce is consumed via getAndIncrement(), it becomes invalid
 * - Entries expire after HEIGHT_WINDOW blocks, resetting the nonce counter to 0
 *
 * @author hal.hildebrand
 */
public class PersistentNonceStore implements NonceTracker {
    private static final Logger log           = LoggerFactory.getLogger(PersistentNonceStore.class);
    private static final long   HEIGHT_WINDOW = 10_000L; // Block height window for entry expiration

    private final MVStore                   store;
    private final MVMap<String, String>     nonceMap;  // Store as String: "nonce:height"
    private final ReadWriteLock             lock = new ReentrantReadWriteLock();
    private volatile long                   currentHeight;

    /**
     * Nonce entry with height tracking for expiration
     */
    private static class NonceEntry {
        final int currentNonce;
        final long creationHeight;

        NonceEntry(int currentNonce, long creationHeight) {
            this.currentNonce = currentNonce;
            this.creationHeight = creationHeight;
        }
    }

    public PersistentNonceStore(File storeFile) {
        this.store = MVStore.open(storeFile.getAbsolutePath());
        this.nonceMap = store.openMap("nonces");
        this.currentHeight = 0L;
        log.debug("Opened persistent nonce store at: {}", storeFile);
    }

    @Override
    public int getAndIncrement(Digest source) {
        lock.writeLock().lock();
        try {
            var key = source.toString();
            var entry = getNonceEntry(key);

            int nextNonce = entry.currentNonce;
            // Preserve original creationHeight - don't update on every increment
            var newEntry = new NonceEntry(nextNonce + 1, entry.creationHeight);
            nonceMap.put(key, serializeEntry(newEntry));
            store.commit();

            log.trace("Incremented nonce for source {}: {} -> {}", source, nextNonce, nextNonce + 1);
            return nextNonce;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean validateNonce(Digest source, int nonce) {
        lock.readLock().lock();
        try {
            var key = source.toString();
            var entry = getNonceEntry(key);

            // Check if entry is expired based on height
            if (isExpired(entry)) {
                log.debug("Nonce validation failed - entry expired: source={}, nonce={}, creationHeight={}, currentHeight={}",
                         source, nonce, entry.creationHeight, currentHeight);
                return false;
            }

            // Strict nonce ordering: only accept the next expected nonce
            // This prevents replay attacks - once a nonce is used (via getAndIncrement),
            // it cannot be reused
            if (nonce != entry.currentNonce) {
                log.debug("Nonce validation failed - strict ordering: source={}, nonce={}, expected={}",
                         source, nonce, entry.currentNonce);
                return false;
            }

            return true;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void checkpoint(long blockHeight) {
        lock.writeLock().lock();
        try {
            if (blockHeight > currentHeight) {
                long oldHeight = currentHeight;
                currentHeight = blockHeight;
                log.debug("Updated checkpoint: {} -> {}", oldHeight, blockHeight);

                // Cleanup expired entries
                long minValidHeight = currentHeight - HEIGHT_WINDOW;
                var keysToRemove = nonceMap.entrySet().stream()
                                           .filter(e -> deserializeEntry(e.getValue()).creationHeight < minValidHeight)
                                           .map(java.util.Map.Entry::getKey)
                                           .toList();

                keysToRemove.forEach(nonceMap::remove);
                if (!keysToRemove.isEmpty()) {
                    store.commit();
                    log.debug("Evicted {} expired nonce entries at height {}", keysToRemove.size(), currentHeight);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void clear() {
        lock.writeLock().lock();
        try {
            nonceMap.clear();
            store.commit();
            log.debug("Cleared all nonces");
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void close() {
        lock.writeLock().lock();
        try {
            store.close();
            log.debug("Closed persistent nonce store");
        } finally {
            lock.writeLock().unlock();
        }
    }

    // Helper methods

    private NonceEntry getNonceEntry(String key) {
        var serialized = nonceMap.get(key);
        if (serialized == null) {
            return new NonceEntry(0, currentHeight);
        }
        return deserializeEntry(serialized);
    }

    private String serializeEntry(NonceEntry entry) {
        return entry.currentNonce + ":" + entry.creationHeight;
    }

    private NonceEntry deserializeEntry(String serialized) {
        var parts = serialized.split(":");
        return new NonceEntry(Integer.parseInt(parts[0]), Long.parseLong(parts[1]));
    }

    private boolean isExpired(NonceEntry entry) {
        if (currentHeight == 0) {
            return false; // No expiration if height not set
        }
        long minValidHeight = currentHeight - HEIGHT_WINDOW;
        return entry.creationHeight < minValidHeight;
    }
}
