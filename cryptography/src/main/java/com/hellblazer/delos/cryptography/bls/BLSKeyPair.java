/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.cryptography.bls;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.Random;

/**
 * BLS key pair containing public and secret keys.
 * <p>
 * Implements AutoCloseable to ensure the secret key is properly cleared from memory.
 * Always use try-with-resources or explicitly call close() when done.
 *
 * @param publicKey BLS public key with Proof of Possession
 * @param secretKey BLS secret key
 * @author hal.hildebrand
 */
public record BLSKeyPair(BLSPublicKey publicKey, BLSSecretKey secretKey) implements AutoCloseable {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException if publicKey or secretKey is null
     */
    public BLSKeyPair {
        Objects.requireNonNull(publicKey, "publicKey cannot be null");
        Objects.requireNonNull(secretKey, "secretKey cannot be null");
    }

    /**
     * Generate a new BLS key pair using a secure random source.
     *
     * @param provider BLS provider for key generation
     * @return A new key pair
     */
    public static BLSKeyPair generate(BLSProvider provider) {
        return generate(new SecureRandom(), provider);
    }

    /**
     * Generate a new BLS key pair using a provided random source.
     * <p>
     * For testing, use BLSTestFixtures.deterministicRandom() for reproducible keys.
     * For production, always use SecureRandom.
     *
     * @param random   Random source for key generation
     * @param provider BLS provider for key generation
     * @return A new key pair
     */
    public static BLSKeyPair generate(Random random, BLSProvider provider) {
        Objects.requireNonNull(random, "random cannot be null");
        Objects.requireNonNull(provider, "provider cannot be null");

        // Generate raw key pair from provider
        var rawKeyPair = provider.generateKeyPair(random);

        // Wrap in typed classes
        var secretKey = new BLSSecretKey(rawKeyPair.secretKey(), provider);
        var publicKeyBytes = rawKeyPair.publicKey();

        // Generate Proof of Possession
        var pop = ProofOfPossession.generate(secretKey, publicKeyBytes, provider);

        // Create public key with PoP
        var publicKey = new BLSPublicKey(publicKeyBytes, pop);

        return new BLSKeyPair(publicKey, secretKey);
    }

    /**
     * Sign a message with the secret key.
     *
     * @param message The message to sign
     * @return BLS signature
     * @throws IllegalStateException if the secret key has been closed
     */
    public BLSSignature sign(byte[] message) {
        return secretKey.sign(message);
    }

    /**
     * Close this key pair and zero the secret key from memory.
     * After calling this method, signing will throw IllegalStateException.
     * <p>
     * This method is idempotent - calling it multiple times has no additional effect.
     */
    @Override
    public void close() throws Exception {
        secretKey.close();
    }

    @Override
    public String toString() {
        return "BLSKeyPair[publicKey=" + publicKey + ", secretKey=" + secretKey + "]";
    }
}

