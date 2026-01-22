/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.cryptography.bls.rotation;

import com.hellblazer.delos.cryptography.bls.BLSProvider;
import com.hellblazer.delos.cryptography.bls.BLSPublicKey;
import com.hellblazer.delos.cryptography.bls.BLSSecretKey;
import com.hellblazer.delos.cryptography.bls.ProofOfPossession;

import java.time.Instant;
import java.util.Random;

/**
 * Helper utilities for Byzantine key rotation test scenarios.
 * <p>
 * Provides methods to simulate adversarial behavior:
 * - Invalid key generation
 * - Malicious proof-of-possession
 * - Key version manipulation
 * - Equivocation attacks
 * <p>
 * Phase 1C-3-A-3: Key rotation testing infrastructure
 *
 * @author hal.hildebrand
 */
class ByzantineKeyRotationHelpers {

    /**
     * Generate an invalid BLS key pair with corrupted secret key.
     * Secret key is random bytes, not a valid BLS scalar.
     *
     * @param random Entropy source
     * @param provider BLS provider
     * @return Invalid key pair
     */
    static BLSProvider.KeyPair generateInvalidKeyPair(Random random, BLSProvider provider) {
        // Generate random bytes for secret key (not a valid BLS scalar)
        var invalidSecretBytes = new byte[32];
        random.nextBytes(invalidSecretBytes);

        // Generate a valid public key from a different secret (mismatch)
        var validPair = provider.generateKeyPair(random);

        // Return mismatched pair (secret doesn't correspond to public)
        return new BLSProvider.KeyPair(invalidSecretBytes, validPair.publicKey());
    }

    /**
     * Create a malicious proof-of-possession with wrong signature.
     *
     * @param publicKey Public key to create proof for
     * @param random    Entropy source
     * @param provider  BLS provider
     * @return Invalid proof-of-possession
     */
    static ProofOfPossession createInvalidProof(
        byte[] publicKey,
        Random random,
        BLSProvider provider
    ) {
        // Create a random signature that won't verify
        var randomSignature = new byte[96];
        random.nextBytes(randomSignature);

        return new ProofOfPossession(randomSignature);
    }

    /**
     * Create a key version with tampered metadata.
     *
     * @param versionNumber Version number
     * @param status        Status to assign
     * @param createdAt     Creation time
     * @param expiresAt     Expiration time (can be before createdAt for invalid)
     * @param rotationId    Rotation ID
     * @param popProof      Proof of possession
     * @return Key version (may be invalid)
     */
    static KeyVersion createTamperedKeyVersion(
        int versionNumber,
        KeyStatus status,
        Instant createdAt,
        Instant expiresAt,
        String rotationId,
        ProofOfPossession popProof
    ) {
        try {
            return new KeyVersion(
                versionNumber,
                createdAt,
                expiresAt,
                status,
                rotationId,
                popProof
            );
        } catch (IllegalArgumentException e) {
            // Return null if validation fails (e.g., expiresAt before createdAt)
            return null;
        }
    }

    /**
     * Simulate equivocation by creating two different key versions with same version number.
     *
     * @param versionNumber Version number
     * @param provider      BLS provider
     * @param random        Entropy source
     * @return Array of two conflicting key versions
     */
    static KeyVersion[] simulateEquivocation(
        int versionNumber,
        BLSProvider provider,
        Random random
    ) {
        var now = Instant.now();

        // Create first key version
        var keyPair1 = provider.generateKeyPair(random);
        var secretKey1 = new BLSSecretKey(keyPair1.secretKey(), provider);
        var proof1 = ProofOfPossession.generate(secretKey1, keyPair1.publicKey(), provider);

        var version1 = new KeyVersion(
            versionNumber,
            now,
            null,
            KeyStatus.GENERATED,
            "rotation-equivocate-1",
            proof1
        );

        // Create second conflicting key version with different key
        var keyPair2 = provider.generateKeyPair(random);
        var secretKey2 = new BLSSecretKey(keyPair2.secretKey(), provider);
        var proof2 = ProofOfPossession.generate(secretKey2, keyPair2.publicKey(), provider);

        var version2 = new KeyVersion(
            versionNumber,  // Same version number!
            now,
            null,
            KeyStatus.GENERATED,
            "rotation-equivocate-2",
            proof2
        );

        return new KeyVersion[]{version1, version2};
    }

    /**
     * Create a key version with a version number rollback attempt.
     *
     * @param oldVersionNumber Lower version number
     * @param provider         BLS provider
     * @param random           Entropy source
     * @return Key version with rolled-back version number
     */
    static KeyVersion simulateVersionRollback(
        int oldVersionNumber,
        BLSProvider provider,
        Random random
    ) {
        var now = Instant.now();
        var keyPair = provider.generateKeyPair(random);
        var secretKey = new BLSSecretKey(keyPair.secretKey(), provider);
        var proof = ProofOfPossession.generate(secretKey, keyPair.publicKey(), provider);

        return new KeyVersion(
            oldVersionNumber,
            now,
            null,
            KeyStatus.GENERATED,
            "rotation-rollback",
            proof
        );
    }

    /**
     * Corrupt a proof-of-possession by flipping random bits.
     *
     * @param proof     Valid proof to corrupt
     * @param bitCount  Number of bits to flip
     * @param random    Entropy source
     * @param provider  BLS provider
     * @return Corrupted proof
     */
    static ProofOfPossession corruptProof(
        ProofOfPossession proof,
        int bitCount,
        Random random,
        BLSProvider provider
    ) {
        var signatureBytes = proof.compressedSignature();
        var corrupted = new byte[signatureBytes.length];
        System.arraycopy(signatureBytes, 0, corrupted, 0, signatureBytes.length);

        // Flip random bits
        for (int i = 0; i < bitCount; i++) {
            int byteIndex = random.nextInt(corrupted.length);
            int bitIndex = random.nextInt(8);
            corrupted[byteIndex] ^= (1 << bitIndex);
        }

        return new ProofOfPossession(corrupted);
    }

    /**
     * Create a Byzantine member that delays proof-of-possession validation.
     * Used to simulate timeout attacks.
     *
     * @param manager Manager to wrap
     * @param delayMs Delay in milliseconds
     * @return Wrapper that delays operations
     */
    static DelayedRotationManager createDelayedManager(
        BLSKeyRotationManager manager,
        long delayMs
    ) {
        return new DelayedRotationManager(manager, delayMs);
    }

    /**
     * Wrapper that delays rotation operations to simulate slow/Byzantine members.
     */
    static class DelayedRotationManager {
        private final BLSKeyRotationManager delegate;
        private final long delayMs;

        DelayedRotationManager(BLSKeyRotationManager delegate, long delayMs) {
            this.delegate = delegate;
            this.delayMs = delayMs;
        }

        String initiateRotationWithDelay(BLSProvider.KeyPair keyPair) {
            sleep(delayMs);
            return delegate.initiateRotation(keyPair);
        }

        void activateKeyVersionWithDelay(int versionNumber) {
            sleep(delayMs);
            delegate.activateKeyVersion(versionNumber);
        }

        BLSKeyRotationManager getDelegate() {
            return delegate;
        }

        private void sleep(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
