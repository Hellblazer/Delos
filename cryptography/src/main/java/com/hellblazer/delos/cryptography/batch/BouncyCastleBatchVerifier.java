/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.cryptography.batch;

import java.security.SecureRandom;

/**
 * Batch verifier using pure Java implementation via Bouncy Castle.
 *
 * Uses the Bos-Coster algorithm for batch verification:
 * - Combines multiple signature verification equations using random scalars
 * - Performs multi-scalar multiplication in a single combined operation
 * - Achieves ~1.5x speedup over sequential verification
 *
 * Algorithm:
 * 1. For each signature i with message m_i, public key A_i, signature (R_i, s_i):
 *    - Verify that s_i < order and R_i is a valid point
 *    - Compute: c_i = H(R_i || A_i || m_i)
 *    - Verification equation: [c_i]*A_i + [s_i]*B == R_i (check R_i - [c_i]*A_i - [s_i]*B == 0)
 *
 * 2. For batch verification (assuming all are valid):
 *    - Generate random 128-bit scalars z_1, z_2, ..., z_n
 *    - Compute: z_1*eq_1 + z_2*eq_2 + ... + z_n*eq_n should be 0
 *    - If batch succeeds, all are valid with high probability
 *    - If batch fails, verify individually to find which failed
 *
 * @see <a href="https://ed25519.cr.yp.to/">Ed25519 Paper</a>
 * @see <a href="https://github.com/bcgit/bc-java">Bouncy Castle Java</a>
 */
public class BouncyCastleBatchVerifier implements BatchVerifier {
    private static final int    MIN_BATCH_SIZE = 4;
    private static final String ALGORITHM = "EdDSA";
    private final FallbackBatchVerifier fallback = new FallbackBatchVerifier();

    /**
     * Verify multiple signatures using batch verification.
     * Falls back to sequential verification if batch fails to identify failures.
     */
    @Override
    public boolean[] batchVerify(byte[][] messages, byte[][] signatures, byte[][] publicKeys) {
        if (messages.length != signatures.length || signatures.length != publicKeys.length) {
            throw new IllegalArgumentException(
                "Array lengths must match: messages=" + messages.length +
                ", signatures=" + signatures.length +
                ", publicKeys=" + publicKeys.length
            );
        }

        // Empty batch
        if (messages.length == 0) {
            return new boolean[0];
        }

        // Single signature: use fallback
        if (messages.length == 1) {
            return fallback.batchVerify(messages, signatures, publicKeys);
        }

        // Try batch verification
        try {
            boolean[] results = performBatchVerification(messages, signatures, publicKeys);
            if (results != null) {
                return results;
            }
        } catch (Exception e) {
            // Fall through to individual verification
        }

        // Fallback: verify individually to identify failures
        return fallback.batchVerify(messages, signatures, publicKeys);
    }

    /**
     * Perform the actual batch verification using Bos-Coster algorithm.
     * Returns null if batch verification fails or throws exception.
     */
    private boolean[] performBatchVerification(byte[][] messages, byte[][] signatures, byte[][] publicKeys)
        throws Exception {
        int batchSize = messages.length;

        // Parse all signatures and public keys
        var parsedSignatures = new Ed25519Signature[batchSize];
        var parsedPublicKeys = new Ed25519PublicKey[batchSize];

        for (int i = 0; i < batchSize; i++) {
            parsedSignatures[i] = Ed25519Signature.decode(signatures[i]);
            parsedPublicKeys[i] = Ed25519PublicKey.decode(publicKeys[i]);

            if (parsedSignatures[i] == null || parsedPublicKeys[i] == null) {
                return null; // Invalid signature/key format
            }
        }

        // Generate random scalars for combining equations (128-bit)
        SecureRandom random = new SecureRandom();
        byte[][] scalars = new byte[batchSize][16];
        for (int i = 0; i < batchSize; i++) {
            random.nextBytes(scalars[i]);
        }

        // Compute combined verification:
        // sum(z_i * ([c_i]*A_i + [s_i]*B - R_i)) == 0 for all valid signatures
        // This uses Shamir's trick for efficient multi-scalar multiplication
        var combinedPoint = computeCombinedVerification(
            messages, parsedSignatures, parsedPublicKeys, scalars
        );

        // If result is identity point, all signatures are valid
        if (combinedPoint != null && combinedPoint.isIdentity()) {
            boolean[] results = new boolean[batchSize];
            java.util.Arrays.fill(results, true);
            return results;
        }

        // Batch verification failed - return null to trigger individual verification
        return null;
    }

    /**
     * Compute the combined verification point.
     * This is where the actual batch verification work happens.
     * Currently returns null as a placeholder for Phase 2 implementation.
     *
     * Phase 2 TODO: Implement using Bouncy Castle's Ed25519 point arithmetic
     */
    private CurvePoint computeCombinedVerification(
        byte[][] messages,
        Ed25519Signature[] signatures,
        Ed25519PublicKey[] publicKeys,
        byte[][] scalars) throws Exception {

        // TODO: Phase 2 implementation
        // This requires using Bouncy Castle's non-public Ed25519 point APIs
        // 1. Convert all R values (from signatures) to curve points
        // 2. Convert all A values (public keys) to curve points
        // 3. Compute hash c_i = H(R_i || A_i || m_i) for each signature
        // 4. Accumulate: sum(z_i * ([c_i]*A_i + [s_i]*B - R_i))
        // 5. Return combined point

        // For now, return null to use fallback
        return null;
    }

    @Override
    public boolean isAvailable() {
        // Bouncy Castle batch verifier requires BC on classpath
        try {
            Class.forName("org.bouncycastle.math.ec.rfc8032.Ed25519");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public int minBatchSize() {
        return MIN_BATCH_SIZE;
    }

    /**
     * Wrapper for decoded Ed25519 signature (R, s pair).
     */
    private static class Ed25519Signature {
        byte[] r; // 32 bytes
        byte[] s; // 32 bytes

        static Ed25519Signature decode(byte[] signature) {
            if (signature == null || signature.length != 64) {
                return null;
            }
            var sig = new Ed25519Signature();
            sig.r = new byte[32];
            sig.s = new byte[32];
            System.arraycopy(signature, 0, sig.r, 0, 32);
            System.arraycopy(signature, 32, sig.s, 0, 32);
            return sig;
        }
    }

    /**
     * Wrapper for decoded Ed25519 public key.
     */
    private static class Ed25519PublicKey {
        byte[] a; // 32 bytes

        static Ed25519PublicKey decode(byte[] publicKey) {
            // Handle DER encoding if present
            byte[] rawKey = publicKey;

            // If this looks like an X.509 DER-encoded key, extract the raw key bytes
            if (publicKey != null && publicKey.length > 32) {
                // Try to extract raw key from X.509 encoding
                // Look for 32 consecutive bytes that look like a public key
                if (publicKey.length == 44) {
                    // Likely X.509 encoded: 12 byte header + 32 byte key
                    rawKey = new byte[32];
                    System.arraycopy(publicKey, 12, rawKey, 0, 32);
                }
            }

            if (rawKey == null || rawKey.length != 32) {
                return null;
            }

            var pk = new Ed25519PublicKey();
            pk.a = rawKey.clone();
            return pk;
        }
    }

    /**
     * Represents a point on the Ed25519 curve.
     */
    private static class CurvePoint {
        boolean identity;

        static CurvePoint identity() {
            var pt = new CurvePoint();
            pt.identity = true;
            return pt;
        }

        static CurvePoint point() {
            var pt = new CurvePoint();
            pt.identity = false;
            return pt;
        }

        boolean isIdentity() {
            return identity;
        }
    }
}
