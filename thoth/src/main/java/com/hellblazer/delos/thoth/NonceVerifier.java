/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth;

import com.hellblazer.delos.cryptography.Digest;

/**
 * Interface for cryptographic nonce verification to prevent replay attacks in DHT operations.
 *
 * <p>A nonce (number used once) is a unique random value generated per request.
 * When a response is received, the nonce is checked: if it has been seen before,
 * the response is rejected as a replay attack.</p>
 *
 * <p>Implementations must be thread-safe as nonce operations occur concurrently
 * across multiple DHT request handlers.</p>
 *
 * <p>The default implementation ({@link InMemoryNonceVerifier}) stores nonces
 * in memory with TTL expiry. Future implementations may persist nonces across
 * restarts (behind a feature flag) to close cross-restart replay windows.</p>
 *
 * @author hal.hildebrand
 */
public interface NonceVerifier {

    /**
     * Generate a unique nonce for a new DHT request.
     *
     * <p>Each call must return a distinct value. The generated nonce should
     * have sufficient entropy to make collision attacks infeasible (at least
     * 128 bits of randomness).</p>
     *
     * @return a unique nonce string; never null
     */
    String generateNonce();

    /**
     * Record a nonce as used and verify it has not been seen before.
     *
     * <p>This method is the core replay-detection mechanism. It atomically
     * checks whether the (nonce, memberId) pair has been previously recorded and,
     * if not, records it as used.</p>
     *
     * <p>In a quorum DHT context, multiple members legitimately respond to the same
     * request carrying the same nonce. Therefore replay detection is keyed on the
     * composite of (nonce, memberId): the same nonce is accepted from different members
     * (normal quorum flow), but the same (nonce, memberId) pair is rejected after
     * its first appearance (replay attack from the same member).</p>
     *
     * <p>A nonce that has expired (age &gt; TTL) is treated as if it was
     * never recorded — it can be accepted again (the original replay window
     * has closed).</p>
     *
     * @param nonce    the nonce from the response to verify; must not be null
     * @param memberId the identifier of the responding member; used as part of the composite key
     * @return {@code true} if the (nonce, memberId) pair is fresh (first use within TTL window);
     *         {@code false} if this (nonce, memberId) pair has already been seen (replay detected)
     */
    boolean recordAndVerify(String nonce, Digest memberId);

    /**
     * Shutdown this verifier and release any held resources.
     *
     * <p>After close, behavior of {@link #generateNonce()} and
     * {@link #recordAndVerify(String, Digest)} is undefined.
     * Implementations should cancel background tasks (e.g., eviction
     * schedulers) without shutting down any shared executors.</p>
     */
    void close();
}
