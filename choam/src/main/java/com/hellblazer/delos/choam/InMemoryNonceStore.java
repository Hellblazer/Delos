/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.cryptography.Digest;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory nonce tracker with no persistence.
 * <p>
 * Legacy implementation for backward compatibility when FeatureFlags.NONCE_PERSISTENCE
 * is disabled. Nonces reset on restart, providing no replay protection across sessions.
 * <p>
 * Thread-safe for concurrent access.
 *
 * @author hal.hildebrand
 */
public class InMemoryNonceStore implements NonceTracker {

    private final ConcurrentHashMap<Digest, AtomicInteger> nonces = new ConcurrentHashMap<>();

    @Override
    public int getAndIncrement(Digest source) {
        return nonces.computeIfAbsent(source, _ -> new AtomicInteger(0)).getAndIncrement();
    }

    @Override
    public boolean validateNonce(Digest source, int nonce) {
        var current = nonces.get(source);
        if (current == null) {
            // No nonces for this source yet - any non-negative nonce is valid
            return nonce >= 0;
        }
        // Nonce must be >= current value (not a replay)
        return nonce >= current.get();
    }

    @Override
    public void checkpoint(long blockHeight) {
        // No-op for in-memory store (no height-based expiration)
    }

    @Override
    public void clear() {
        nonces.clear();
    }
}
