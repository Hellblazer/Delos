/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.cryptography.Digest;

import java.io.File;

/**
 * Interface for tracking transaction nonces to prevent replay attacks.
 * <p>
 * Implementations:
 * - PersistentNonceStore: Persists nonces to disk (MVStore)
 * - InMemoryNonceStore: In-memory only (legacy, no replay protection across restarts)
 *
 * @author hal.hildebrand
 */
public interface NonceTracker extends AutoCloseable {

    /**
     * Factory method to create NonceTracker based on feature flag.
     *
     * @param storeFile MVStore file for persistent storage (ignored if persistence disabled)
     * @return PersistentNonceStore if FeatureFlags.NONCE_PERSISTENCE enabled, otherwise InMemoryNonceStore
     */
    static NonceTracker create(File storeFile) {
        if (FeatureFlags.NONCE_PERSISTENCE.isEnabled()) {
            return new PersistentNonceStore(storeFile);
        } else {
            return new InMemoryNonceStore();
        }
    }

    /**
     * Get the next nonce for the given source and increment the counter.
     *
     * @param source Transaction source identifier
     * @return Next nonce value
     */
    int getAndIncrement(Digest source);

    /**
     * Validate that a nonce is acceptable for the given source.
     * <p>
     * Strict ordering: A nonce is valid only if it equals the next expected nonce.
     * Once consumed via getAndIncrement(), the nonce becomes permanently invalid.
     * <p>
     * For new sources, only nonce 0 is valid. For persistent stores, entries expire
     * after HEIGHT_WINDOW blocks based on creation height.
     *
     * @param source Transaction source identifier
     * @param nonce  Nonce to validate
     * @return true if nonce is valid (equals next expected), false if replay or expired
     */
    boolean validateNonce(Digest source, int nonce);

    /**
     * Update the current block height for expiration calculations.
     * <p>
     * Nonces are valid for blocks H to H+10,000. When checkpoint is called,
     * nonces older than currentHeight - 10,000 are expired.
     *
     * @param blockHeight Current block height
     */
    void checkpoint(long blockHeight);

    /**
     * Clear all nonces (for testing).
     */
    void clear();

    /**
     * Close resources (for persistent stores).
     */
    @Override
    default void close() {
        // Default: no-op for non-persistent implementations
    }
}
