/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.benchmark;

import com.hellblazer.delos.cryptography.SignatureAlgorithm;

import java.security.*;

/**
 * Helper class for Ed25519 vs BLS signature comparison benchmarks.
 * <p>
 * Provides Ed25519 signing and verification operations for baseline performance comparison
 * against BLS aggregate signatures.
 * <p>
 * Used by BLSPerformanceBenchmarkTest for Category 1 benchmarks (Ed25519 vs BLS comparison).
 *
 * @author hal.hildebrand
 */
public class Ed25519BLSComparisonTest {

    private KeyPair ed25519KeyPair;
    private Signature ed25519Signer;
    private Signature ed25519Verifier;

    /**
     * Initialize Ed25519 key pair and signature instances.
     */
    public void setUp() {
        try {
            // Generate Ed25519 key pair
            ed25519KeyPair = SignatureAlgorithm.ED_25519.generateKeyPair(new SecureRandom());

            // Initialize signer and verifier
            ed25519Signer = Signature.getInstance(SignatureAlgorithm.ED_25519.signatureInstanceName());
            ed25519Verifier = Signature.getInstance(SignatureAlgorithm.ED_25519.signatureInstanceName());

            ed25519Signer.initSign(ed25519KeyPair.getPrivate());
            ed25519Verifier.initVerify(ed25519KeyPair.getPublic());
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Ed25519 test fixtures", e);
        }
    }

    /**
     * Sign a message with Ed25519.
     *
     * @param message Message to sign
     * @return Ed25519 signature bytes
     */
    public byte[] signWithEd25519(byte[] message) {
        try {
            ed25519Signer.update(message);
            return ed25519Signer.sign();
        } catch (SignatureException e) {
            throw new RuntimeException("Ed25519 signing failed", e);
        }
    }

    /**
     * Verify an Ed25519 signature.
     *
     * @param message Message that was signed
     * @param signature Ed25519 signature to verify
     * @return true if signature is valid
     */
    public boolean verifyEd25519(byte[] message, byte[] signature) {
        try {
            ed25519Verifier.update(message);
            return ed25519Verifier.verify(signature);
        } catch (SignatureException e) {
            throw new RuntimeException("Ed25519 verification failed", e);
        }
    }

    /**
     * Get the Ed25519 public key.
     *
     * @return Ed25519 public key
     */
    public PublicKey getPublicKey() {
        return ed25519KeyPair.getPublic();
    }

    /**
     * Get the Ed25519 private key.
     *
     * @return Ed25519 private key
     */
    public PrivateKey getPrivateKey() {
        return ed25519KeyPair.getPrivate();
    }
}
