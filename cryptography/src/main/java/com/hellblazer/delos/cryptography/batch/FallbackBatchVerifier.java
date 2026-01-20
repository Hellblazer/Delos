/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.cryptography.batch;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;

/**
 * Fallback batch verifier that performs sequential verification of signatures.
 * Used when native or optimized batch verification is not available,
 * or as a sanity check when batch verification fails (to identify which signature failed).
 */
public class FallbackBatchVerifier implements BatchVerifier {
    private static final String ALGORITHM = "EdDSA";
    private static final int    MIN_BATCH_SIZE = 4;

    /**
     * Verify signatures sequentially using JDK's EdDSA implementation.
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

        boolean[] results = new boolean[messages.length];

        try {
            KeyFactory keyFactory = KeyFactory.getInstance(ALGORITHM);

            for (int i = 0; i < messages.length; i++) {
                results[i] = verifySignature(
                    keyFactory,
                    messages[i],
                    signatures[i],
                    publicKeys[i]
                );
            }
        } catch (Exception e) {
            // On any exception, return all false
            return results;
        }

        return results;
    }

    /**
     * Verify a single signature.
     */
    private boolean verifySignature(KeyFactory keyFactory, byte[] message, byte[] signature, byte[] publicKeyBytes) {
        try {
            // Reconstruct public key from bytes
            X509EncodedKeySpec spec = new X509EncodedKeySpec(publicKeyBytes);
            PublicKey publicKey = keyFactory.generatePublic(spec);

            // Verify signature
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(publicKey);
            verifier.update(message);
            return verifier.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean isAvailable() {
        // Fallback verifier is always available (uses JDK)
        return true;
    }

    @Override
    public int minBatchSize() {
        return MIN_BATCH_SIZE;
    }
}
