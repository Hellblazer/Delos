/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.cryptography.bls;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Provider interface for BLS12-381 cryptographic operations.
 * <p>
 * This interface defines the contract for BLS signature operations including:
 * - Key pair generation
 * - Signing and verification
 * - Signature aggregation
 * - Batch verification
 * <p>
 * Implementations must be thread-safe and handle all error cases defensively.
 */
public interface BLSProvider {

    /**
     * Key pair record containing BLS secret and public keys.
     * <p>
     * Keys are stored as byte arrays with defensive copies:
     * - Secret key: 32 bytes
     * - Public key: 48 bytes (compressed G1 point)
     *
     * @param secretKey BLS12-381 secret key (32 bytes)
     * @param publicKey BLS12-381 public key compressed (48 bytes)
     */
    record KeyPair(byte[] secretKey, byte[] publicKey) {
        public KeyPair {
            // Defensive copies
            secretKey = secretKey.clone();
            publicKey = publicKey.clone();
        }

        @Override
        public byte[] secretKey() {
            return secretKey.clone();
        }

        @Override
        public byte[] publicKey() {
            return publicKey.clone();
        }
    }

    /**
     * Get the default BLS provider implementation.
     *
     * @return Default provider (TekuBLSProvider)
     */
    static BLSProvider getDefault() {
        return new com.hellblazer.delos.cryptography.bls.impl.TekuBLSProvider();
    }

    /**
     * Generate a new BLS key pair.
     *
     * @param random Random source for key generation
     * @return New key pair
     * @throws NullPointerException if random is null
     */
    KeyPair generateKeyPair(Random random);

    /**
     * Sign a message with a BLS secret key.
     *
     * @param secretKey BLS secret key (32 bytes)
     * @param message   Message to sign (arbitrary bytes)
     * @return BLS signature (96 bytes compressed)
     * @throws NullPointerException     if any parameter is null
     * @throws IllegalArgumentException if secretKey is not 32 bytes
     */
    byte[] sign(byte[] secretKey, byte[] message);

    /**
     * Verify a BLS signature.
     *
     * @param publicKey BLS public key (48 bytes compressed)
     * @param message   Original message (arbitrary bytes)
     * @param signature BLS signature (96 bytes compressed)
     * @return true if signature is valid, false otherwise
     * @throws NullPointerException     if any parameter is null
     * @throws IllegalArgumentException if key/signature sizes are invalid
     */
    boolean verify(byte[] publicKey, byte[] message, byte[] signature);

    /**
     * Aggregate multiple BLS signatures into one.
     * <p>
     * All signatures must be on the same message for fast aggregate verification.
     *
     * @param signatures List of BLS signatures (each 96 bytes)
     * @return Aggregate signature (96 bytes)
     * @throws NullPointerException     if signatures is null or contains null
     * @throws IllegalArgumentException if any signature is not 96 bytes
     */
    byte[] aggregateSignatures(List<byte[]> signatures);

    /**
     * Verify an aggregate signature against multiple public keys (same message).
     *
     * @param publicKeys         List of public keys (each 48 bytes compressed)
     * @param message            Common message (arbitrary bytes)
     * @param aggregateSignature Aggregate signature (96 bytes)
     * @return true if aggregate signature is valid, false otherwise
     * @throws NullPointerException     if any parameter is null or lists contain null
     * @throws IllegalArgumentException if sizes are invalid or list sizes don't match
     */
    boolean verifyAggregate(List<byte[]> publicKeys, byte[] message, byte[] aggregateSignature);

    /**
     * Batch verify multiple signatures at once (different messages allowed).
     *
     * @param publicKeys List of public keys (each 48 bytes compressed)
     * @param messages   List of messages (each arbitrary bytes)
     * @param signatures List of signatures (each 96 bytes)
     * @return true if all signatures verify, false if any fail
     * @throws NullPointerException     if any parameter is null or lists contain null
     * @throws IllegalArgumentException if list sizes don't match or sizes are invalid
     */
    boolean batchVerify(List<byte[]> publicKeys, List<byte[]> messages, List<byte[]> signatures);

    /**
     * Verify a BLSAggregate signature against committee public keys using the signer bitmap.
     * <p>
     * This method filters the public keys based on the aggregate's signer bitmap,
     * then verifies the aggregated signature against the filtered keys and message.
     * <p>
     * Phase 3 addition for witness service aggregation support.
     *
     * @param publicKeys List of all committee public keys (each 48 bytes compressed)
     * @param message    Common message that was signed (arbitrary bytes)
     * @param aggregate  BLS aggregate containing signature and signer bitmap
     * @return true if aggregate signature verifies, false otherwise
     * @throws NullPointerException     if any parameter is null
     * @throws IllegalArgumentException if sizes are invalid
     */
    default boolean verifyAggregateWithBitmap(List<byte[]> publicKeys, byte[] message, BLSAggregate aggregate) {
        // Phase 4 implementation: filter keys by bitmap, then verify
        var filteredKeys = filterByBitmap(publicKeys, aggregate.signerBitmap());
        return verifyAggregate(filteredKeys, message, aggregate.aggregatedSignature().compressedBytes());
    }

    /**
     * Filter a list of public keys based on a signer bitmap.
     * <p>
     * Returns only the public keys at positions where the bitmap has set bits.
     * Used for aggregate signature verification with partial committee signatures.
     * <p>
     * Phase 3 addition for bitmap-based key filtering.
     *
     * @param publicKeys   List of all committee public keys
     * @param signerBitmap Bitmap indicating which keys to include
     * @return List of public keys at positions indicated by bitmap
     * @throws NullPointerException     if any parameter is null
     * @throws IllegalArgumentException if bitmap indicates indices beyond publicKeys size
     */
    default List<byte[]> filterByBitmap(List<byte[]> publicKeys, byte[] signerBitmap) {
        if (publicKeys == null) {
            throw new NullPointerException("publicKeys cannot be null");
        }
        if (signerBitmap == null) {
            throw new NullPointerException("signerBitmap cannot be null");
        }

        var filtered = new ArrayList<byte[]>();

        for (int byteIndex = 0; byteIndex < signerBitmap.length; byteIndex++) {
            var b = signerBitmap[byteIndex];
            for (int bitIndex = 0; bitIndex < 8; bitIndex++) {
                if ((b & (1 << bitIndex)) != 0) {
                    var keyIndex = byteIndex * 8 + bitIndex;
                    if (keyIndex >= publicKeys.size()) {
                        throw new IllegalArgumentException(
                            "Bitmap indicates signer index " + keyIndex +
                            " but publicKeys has only " + publicKeys.size() + " keys"
                        );
                    }
                    filtered.add(publicKeys.get(keyIndex));
                }
            }
        }

        return filtered;
    }
}
