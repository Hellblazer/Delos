/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.cryptography.bls;

import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.cryptography.bls.impl.TekuBLSProvider;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * High-level facade for BLS-12-381 cryptographic operations.
 * <p>
 * Provides type-safe, convenient static methods for BLS operations without exposing
 * raw byte arrays in the public API. All operations delegate to TekuBLSProvider.
 * <p>
 * Phase 5 implementation: Clean API for signing, aggregation, and verification.
 *
 * @author hal.hildebrand
 */
public final class BLSOperations {

    private static final BLSProvider provider = TekuBLSProvider.getInstance();
    private static final SecureRandom secureRandom = new SecureRandom();

    // Utility class - no instances
    private BLSOperations() {
    }

    // ========== Key Management ==========

    /**
     * Generate a new BLS key pair using SecureRandom.
     *
     * @return A new BLS key pair with Proof of Possession
     */
    public static BLSKeyPair generateKeyPair() {
        return BLSKeyPair.generate(provider);
    }

    /**
     * Generate a new BLS key pair using a provided random source.
     * <p>
     * For production use SecureRandom. For testing, use seeded Random for reproducibility.
     *
     * @param random Random source for key generation
     * @return A new BLS key pair with Proof of Possession
     * @throws NullPointerException if random is null
     */
    public static BLSKeyPair generateKeyPair(Random random) {
        Objects.requireNonNull(random, "random cannot be null");
        return BLSKeyPair.generate(random, provider);
    }

    /**
     * Derive the public key from a secret key.
     * <p>
     * Given secret key bytes, compute the corresponding public key using the BLS12-381 curve.
     * Also generates Proof of Possession for the derived public key.
     * <p>
     * Implementation note: Uses Teku BLS library to reconstruct key pair from secret key.
     *
     * @param secretKey BLS secret key (32 bytes)
     * @return BLS public key with Proof of Possession
     * @throws NullPointerException     if secretKey is null
     * @throws IllegalArgumentException if secretKey is not 32 bytes
     */
    public static BLSPublicKey derivePublicKey(byte[] secretKey) {
        // TODO: Phase 6 - Implement proper key derivation
        // Teku BLS API does not expose a public method to construct BLSKeyPair from secret key alone
        // Requires either: (1) reflection, (2) custom fork of Teku, or (3) alternative approach
        throw new UnsupportedOperationException("Key derivation not yet implemented - requires Teku BLS API enhancement");
    }

    // ========== Signing ==========

    /**
     * Sign a message with a BLS key pair.
     *
     * @param keyPair BLS key pair containing the secret key
     * @param message Message to sign (arbitrary bytes)
     * @return BLS signature
     * @throws NullPointerException  if any parameter is null
     * @throws IllegalStateException if the secret key has been closed
     */
    public static BLSSignature sign(BLSKeyPair keyPair, byte[] message) {
        Objects.requireNonNull(keyPair, "keyPair cannot be null");
        Objects.requireNonNull(message, "message cannot be null");
        return keyPair.sign(message);
    }

    /**
     * Sign a message with a secret key (byte array).
     * <p>
     * Convenience method for signing without wrapping in BLSKeyPair.
     *
     * @param secretKey BLS secret key (32 bytes)
     * @param message   Message to sign (arbitrary bytes)
     * @return BLS signature
     * @throws NullPointerException     if any parameter is null
     * @throws IllegalArgumentException if secretKey is not 32 bytes
     */
    public static BLSSignature sign(byte[] secretKey, byte[] message) {
        Objects.requireNonNull(secretKey, "secretKey cannot be null");
        Objects.requireNonNull(message, "message cannot be null");

        var signatureBytes = provider.sign(secretKey, message);
        return new BLSSignature(signatureBytes);
    }

    /**
     * Verify a BLS signature.
     *
     * @param publicKey BLS public key
     * @param message   Original message (arbitrary bytes)
     * @param signature BLS signature to verify
     * @return true if signature is valid, false otherwise
     * @throws NullPointerException if any parameter is null
     */
    public static boolean verify(BLSPublicKey publicKey, byte[] message, BLSSignature signature) {
        Objects.requireNonNull(publicKey, "publicKey cannot be null");
        Objects.requireNonNull(message, "message cannot be null");
        Objects.requireNonNull(signature, "signature cannot be null");

        return provider.verify(publicKey.toBytesCompressed(), message, signature.toBytes());
    }

    // ========== Aggregation ==========

    /**
     * Aggregate multiple BLS signatures with signer indices.
     * <p>
     * Creates a BLSAggregate containing the aggregated signature and a bitmap
     * indicating which committee members signed.
     *
     * @param signatures    List of BLS signatures to aggregate (must not be empty)
     * @param signerIndices List of signer positions (must match signatures length)
     * @return BLS aggregate with signature and signer bitmap
     * @throws NullPointerException     if any parameter is null
     * @throws IllegalArgumentException if lists are empty or have mismatched lengths
     */
    public static BLSAggregate aggregateSignatures(List<BLSSignature> signatures, List<Integer> signerIndices) {
        Objects.requireNonNull(signatures, "signatures cannot be null");
        Objects.requireNonNull(signerIndices, "signerIndices cannot be null");

        return BLSAggregate.aggregate(signatures, signerIndices);
    }

    /**
     * Aggregate multiple BLS signatures with auto-assigned indices (0, 1, 2, ...).
     * <p>
     * Convenience method for aggregating signatures when indices are sequential.
     *
     * @param signatures List of BLS signatures to aggregate (must not be empty)
     * @return BLS aggregate with signature and signer bitmap
     * @throws NullPointerException     if signatures is null
     * @throws IllegalArgumentException if signatures is empty
     */
    public static BLSAggregate aggregateSignatures(List<BLSSignature> signatures) {
        Objects.requireNonNull(signatures, "signatures cannot be null");

        // Auto-assign indices 0, 1, 2, ...
        var signerIndices = new ArrayList<Integer>(signatures.size());
        for (int i = 0; i < signatures.size(); i++) {
            signerIndices.add(i);
        }

        return aggregateSignatures(signatures, signerIndices);
    }

    /**
     * Verify an aggregate signature against committee public keys.
     * <p>
     * Uses the aggregate's signer bitmap to filter the public keys, then verifies
     * the aggregated signature against the filtered keys and message.
     *
     * @param publicKeys List of all committee public keys
     * @param message    Common message that was signed (arbitrary bytes)
     * @param aggregate  BLS aggregate containing signature and signer bitmap
     * @return true if aggregate signature verifies, false otherwise
     * @throws NullPointerException     if any parameter is null
     * @throws IllegalArgumentException if sizes are invalid
     */
    public static boolean verifyAggregate(List<BLSPublicKey> publicKeys, byte[] message, BLSAggregate aggregate) {
        Objects.requireNonNull(publicKeys, "publicKeys cannot be null");
        Objects.requireNonNull(message, "message cannot be null");
        Objects.requireNonNull(aggregate, "aggregate cannot be null");

        // Convert BLSPublicKey list to byte[] list
        var publicKeyBytes = publicKeys.stream()
                                       .map(BLSPublicKey::toBytesCompressed)
                                       .toList();

        return provider.verifyAggregateWithBitmap(publicKeyBytes, message, aggregate);
    }

    // ========== Batch Operations ==========

    /**
     * Batch verify multiple signatures at once (different messages allowed).
     * <p>
     * More efficient than verifying each signature individually.
     *
     * @param publicKeys List of public keys (each 96 bytes)
     * @param messages   List of messages (each arbitrary bytes)
     * @param signatures List of signatures (each 48 bytes)
     * @return true if all signatures verify, false if any fail
     * @throws NullPointerException     if any parameter is null or lists contain null
     * @throws IllegalArgumentException if list sizes don't match
     */
    public static boolean batchVerify(List<BLSPublicKey> publicKeys, List<byte[]> messages,
                                      List<BLSSignature> signatures) {
        Objects.requireNonNull(publicKeys, "publicKeys cannot be null");
        Objects.requireNonNull(messages, "messages cannot be null");
        Objects.requireNonNull(signatures, "signatures cannot be null");

        if (publicKeys.size() != messages.size() || publicKeys.size() != signatures.size()) {
            throw new IllegalArgumentException(
                "List sizes must match: publicKeys=" + publicKeys.size() +
                ", messages=" + messages.size() +
                ", signatures=" + signatures.size()
            );
        }

        // Convert to byte arrays
        var publicKeyBytes = publicKeys.stream()
                                       .map(BLSPublicKey::toBytesCompressed)
                                       .toList();
        var signatureBytes = signatures.stream()
                                       .map(BLSSignature::toBytes)
                                       .toList();

        return provider.batchVerify(publicKeyBytes, messages, signatureBytes);
    }

    // ========== Utilities ==========

    /**
     * Get the signature algorithm for BLS operations.
     *
     * @return SignatureAlgorithm.BLS_12_381
     */
    public static SignatureAlgorithm getAlgorithm() {
        return SignatureAlgorithm.BLS_12_381;
    }

    /**
     * Check if BLS operations are supported.
     * <p>
     * Always returns true as BLS is always available via Teku implementation.
     *
     * @return true
     */
    public static boolean isSupported() {
        return true;
    }
}
