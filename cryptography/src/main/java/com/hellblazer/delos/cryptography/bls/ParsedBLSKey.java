/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.cryptography.bls;

/**
 * Opaque wrapper for a parsed BLS public key.
 * <p>
 * This record encapsulates the provider-specific representation of a parsed BLS12-381 public key,
 * hiding implementation details (Teku BLSPublicKey, etc) behind a type-safe interface.
 * <p>
 * Used for key pre-parsing during view changes to eliminate per-receipt parsing overhead.
 * The actual parsed key object is stored internally and accessed only by the provider.
 * <p>
 * Phase 1C-1-D: Committee Key Pre-computation support.
 * <p>
 * Thread-safe: Immutable record with no state mutation.
 *
 * @param parsedKey The provider-specific parsed key object (encapsulated)
 * @author hal.hildebrand
 */
public record ParsedBLSKey(Object parsedKey) {

    public ParsedBLSKey {
        if (parsedKey == null) {
            throw new NullPointerException("parsedKey cannot be null");
        }
    }

    /**
     * Extract the raw bytes of the public key.
     * <p>
     * This method accesses the underlying parsed key to extract the compressed point bytes.
     * The method is package-private to restrict access to trusted providers.
     *
     * @return The 48-byte compressed G1 point representation
     */
    public byte[] getCompressedBytes() {
        // Provider implementations will override this behavior
        // through package-private extensions
        throw new UnsupportedOperationException(
            "ParsedBLSKey.getCompressedBytes() must be called on provider-extended instances"
        );
    }
}
