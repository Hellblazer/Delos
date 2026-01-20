/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.cryptography.batch;

/**
 * Batch verifier for Ed25519 signatures.
 * Provides optimized verification of multiple signatures using multi-scalar multiplication.
 *
 * The batch verification algorithm combines multiple signature verification equations
 * using random scalars and multi-scalar multiplication, achieving 2-5x speedup
 * compared to sequential verification.
 *
 * @see <a href="https://ed25519.cr.yp.to/">Ed25519 Paper</a>
 */
public interface BatchVerifier {
    /**
     * Verify multiple Ed25519 signatures in a single batch operation.
     * Signatures can be over different messages with different public keys.
     *
     * @param messages    array of message byte arrays (one per signature)
     * @param signatures  array of signature byte arrays (one per message)
     * @param publicKeys  array of public key bytes (one per signature)
     * @return array of booleans, where result[i] = true if signature[i] is valid
     */
    boolean[] batchVerify(byte[][] messages, byte[][] signatures, byte[][] publicKeys);

    /**
     * Check if batch verification is available on this platform.
     * (e.g., native library loaded, or Bouncy Castle available)
     *
     * @return true if batch verification can be used
     */
    boolean isAvailable();

    /**
     * Get the minimum batch size where batch verification is faster than sequential.
     * Batch verification has overhead, so small batches should use sequential verification.
     *
     * @return minimum batch size to benefit from batch verification
     */
    int minBatchSize();
}
