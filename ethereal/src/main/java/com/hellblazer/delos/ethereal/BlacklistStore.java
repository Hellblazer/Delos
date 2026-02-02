/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.ethereal;

import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Interface for persistent storage of equivocation blacklist.
 * <p>
 * Byzantine nodes that are detected equivocating (producing multiple units
 * with the same creator and height) are blacklisted to prevent repeated attacks.
 * <p>
 * Implementations must be thread-safe as blacklist operations may be called
 * concurrently from multiple gossip handlers.
 *
 * @author hal.hildebrand
 */
public interface BlacklistStore {

    /**
     * Add a creator to the blacklist due to detected equivocation.
     * <p>
     * This operation should be durable (persist across restarts) in production
     * implementations. Write-through semantics ensure the blacklist entry is
     * persisted before returning.
     *
     * @param creator the process ID of the equivocating creator
     */
    void blacklist(short creator);

    /**
     * Check if a creator has been blacklisted for equivocation.
     *
     * @param creator the process ID to check
     * @return true if the creator is blacklisted, false otherwise
     */
    boolean isBlacklisted(short creator);

    /**
     * Get all blacklisted creators.
     *
     * @return unmodifiable set of blacklisted creator process IDs
     */
    Set<Short> getBlacklisted();

    /**
     * Clear the blacklist. Used primarily for testing.
     * Production implementations may choose to make this a no-op
     * or require administrative authorization.
     */
    void clear();

    /**
     * In-memory implementation of BlacklistStore.
     * <p>
     * Suitable for testing and scenarios where blacklist persistence
     * across restarts is not required. The blacklist survives across
     * epochs within the same process lifetime.
     */
    class InMemoryBlacklistStore implements BlacklistStore {
        private final Set<Short> blacklisted = new ConcurrentSkipListSet<>();

        @Override
        public void blacklist(short creator) {
            blacklisted.add(creator);
        }

        @Override
        public boolean isBlacklisted(short creator) {
            return blacklisted.contains(creator);
        }

        @Override
        public Set<Short> getBlacklisted() {
            return Set.copyOf(blacklisted);
        }

        @Override
        public void clear() {
            blacklisted.clear();
        }
    }
}
