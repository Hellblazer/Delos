/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.cryptography.batch;

import java.io.InputStream;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Context for collecting multiple Ed25519 signatures for batch verification.
 * Maintains deterministic ordering and maps verification results back to original indices.
 */
public class BatchVerificationContext {
    private final List<Integer>   indices        = new ArrayList<>();
    private final List<byte[]>    messages       = new ArrayList<>();
    private final List<byte[]>    signatures     = new ArrayList<>();
    private final List<byte[]>    publicKeyBytes = new ArrayList<>();
    private final List<PublicKey> publicKeys     = new ArrayList<>();

    /**
     * Add a signature to the batch for verification.
     *
     * @param originalIndex original index in the collection (for result mapping)
     * @param message       the message that was signed
     * @param signature     the signature bytes
     * @param publicKey     the public key for verification
     */
    public void add(int originalIndex, byte[] message, byte[] signature, PublicKey publicKey) {
        indices.add(originalIndex);
        messages.add(message);
        signatures.add(signature);
        publicKeys.add(publicKey);
        publicKeyBytes.add(publicKey.getEncoded());
    }

    /**
     * Verify all collected signatures using batch verification.
     * Results are returned in the original index order.
     *
     * @return boolean array where index i corresponds to original index i
     */
    public boolean[] verify() {
        if (isEmpty()) {
            return new boolean[0];
        }

        if (size() == 1) {
            // Single signature: use simple verification
            return verifySingle();
        }

        // Use batch verifier
        var verifier = BatchVerifierFactory.createVerifier();
        var results = verifier.batchVerify(
            messages.toArray(new byte[0][]),
            signatures.toArray(new byte[0][]),
            publicKeyBytes.toArray(new byte[0][])
        );

        // Map results back to original indices
        return mapResultsToOriginalOrder(results);
    }

    /**
     * Verify a single signature.
     */
    private boolean[] verifySingle() {
        byte[] message = messages.get(0);
        byte[] signature = signatures.get(0);
        PublicKey publicKey = publicKeys.get(0);

        // Create a simple verifier for single signature
        return new boolean[] { verifySignature(message, signature, publicKey) };
    }

    /**
     * Verify a single signature using JDK EdDSA.
     */
    private boolean verifySignature(byte[] message, byte[] signature, PublicKey publicKey) {
        try {
            var verifier = java.security.Signature.getInstance("EdDSA");
            verifier.initVerify(publicKey);
            verifier.update(message);
            return verifier.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Map batch verification results back to original index order.
     * Since batch verification returns results in batch order, we need to remap them
     * to the original indices.
     *
     * @param batchResults boolean array from batch verifier (in batch order)
     * @return boolean array in original index order
     */
    private boolean[] mapResultsToOriginalOrder(boolean[] batchResults) {
        // Find the maximum original index to size the result array correctly
        int maxIndex = -1;
        for (int idx : indices) {
            if (idx > maxIndex) {
                maxIndex = idx;
            }
        }

        // Create result array with space for all indices
        boolean[] orderedResults = new boolean[maxIndex + 1];
        Arrays.fill(orderedResults, false);

        // Map batch results to original indices
        for (int i = 0; i < batchResults.length; i++) {
            int originalIndex = indices.get(i);
            orderedResults[originalIndex] = batchResults[i];
        }

        return orderedResults;
    }

    /**
     * Get the number of signatures in this batch.
     */
    public int size() {
        return indices.size();
    }

    /**
     * Check if this context has any signatures.
     */
    public boolean isEmpty() {
        return indices.isEmpty();
    }

    /**
     * Clear all collected signatures.
     */
    public void clear() {
        indices.clear();
        messages.clear();
        signatures.clear();
        publicKeyBytes.clear();
        publicKeys.clear();
    }

    /**
     * Get the threshold below which batch verification has too much overhead.
     * For small batches, sequential verification is faster.
     */
    public static int minBatchSize() {
        return BatchVerifierFactory.minBatchSize();
    }
}
