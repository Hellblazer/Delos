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
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;

/**
 * Manages BLS key rotation lifecycle.
 * <p>
 * Responsibilities:
 * - Generate new keys
 * - Coordinate rotation across committee
 * - Validate proof-of-possession
 * - Transition keys through states (GENERATED → ACTIVE → DEPRECATED → ARCHIVED)
 * - Manage grace periods for zero-downtime rotation
 * <p>
 * Thread-safe: Uses KeyRotationState which is thread-safe with ReentrantReadWriteLock.
 * <p>
 * Phase 1C-3-A-1: Core BLS key rotation mechanism
 *
 * @author hal.hildebrand
 */
public class BLSKeyRotationManager {

    private final BLSProvider provider;
    private final String memberId;
    private final RotationPolicy policy;
    private final KeyRotationState state;
    private final int committeeMembersF;
    private final Random random;

    /**
     * Create a BLS key rotation manager.
     *
     * @param provider           BLS provider for cryptographic operations
     * @param memberId           This member's identifier
     * @param policy             Rotation policy defining when to rotate
     * @param committeeMembersF  Byzantine tolerance (f in 3f+1)
     * @throws NullPointerException if any parameter is null
     */
    public BLSKeyRotationManager(
        BLSProvider provider,
        String memberId,
        RotationPolicy policy,
        int committeeMembersF
    ) {
        this.provider = Objects.requireNonNull(provider, "provider cannot be null");
        this.memberId = Objects.requireNonNull(memberId, "memberId cannot be null");
        this.policy = Objects.requireNonNull(policy, "policy cannot be null");
        this.committeeMembersF = committeeMembersF;
        this.state = new KeyRotationState();
        this.random = new Random();
    }

    /**
     * Generate a new BLS key pair.
     *
     * @return New key pair (secret key + public key)
     */
    public BLSProvider.KeyPair generateNewKeyPair() {
        return provider.generateKeyPair(random);
    }

    /**
     * Initiate key rotation.
     * <p>
     * Creates a new key version in GENERATED state.
     * If an active key exists, it is transitioned to DEPRECATED.
     *
     * @param newKeyPair New key pair to activate
     * @return Rotation operation ID for tracking
     */
    public String initiateRotation(BLSProvider.KeyPair newKeyPair) {
        var rotationId = UUID.randomUUID().toString();
        var now = Instant.now();

        // Generate proof-of-possession
        var secretKey = new BLSSecretKey(newKeyPair.secretKey(), provider);
        var popProof = ProofOfPossession.generate(secretKey, newKeyPair.publicKey(), provider);

        // Transition existing active key to DEPRECATED
        var activeKey = state.getActiveKey();
        if (activeKey.isPresent()) {
            var deprecatedVersion = new KeyVersion(
                activeKey.get().versionNumber(),
                activeKey.get().createdAt(),
                now,  // Expiration = now (start of grace period)
                KeyStatus.DEPRECATED,
                activeKey.get().rotationId(),
                activeKey.get().popProof()
            );
            state.updateKeyVersion(deprecatedVersion);
        }

        // Create new key version (initially GENERATED)
        var newVersion = new KeyVersion(
            getNextVersionNumber(),
            now,
            null,  // No expiration initially
            KeyStatus.GENERATED,
            rotationId,
            popProof
        );

        state.addKeyVersion(newVersion);

        return rotationId;
    }

    /**
     * Activate a new key version after committee coordination.
     * <p>
     * Transitions key from GENERATED to ACTIVE.
     * Called after:
     * 1. All committee members confirmed receipt of new public key
     * 2. Majority (2f+1) have validated proof-of-possession
     * 3. Grace period has elapsed
     *
     * @param versionNumber Version number to activate
     * @throws IllegalArgumentException if version not found
     */
    public void activateKeyVersion(int versionNumber) {
        var version = state.keyVersions().get(versionNumber);
        if (version == null) {
            throw new IllegalArgumentException("Version not found: " + versionNumber);
        }

        // Create active version
        var activeVersion = new KeyVersion(
            version.versionNumber(),
            version.createdAt(),
            version.expiresAt(),
            KeyStatus.ACTIVE,
            version.rotationId(),
            version.popProof()
        );

        // Update the version to ACTIVE status
        state.updateKeyVersion(activeVersion);

        // Mark this version as the active one
        state.activateKeyVersion(versionNumber);
    }

    /**
     * Get all keys valid for verification (active + grace period).
     *
     * @param now Current timestamp
     * @return Map of version number to key version for valid keys
     */
    public Map<Integer, KeyVersion> getValidKeys(Instant now) {
        return state.getValidKeys(now, policy.getGracePeriod());
    }

    /**
     * Check if rotation should occur based on policy.
     *
     * @param now Current timestamp
     * @return true if rotation should be triggered
     */
    public boolean shouldRotate(Instant now) {
        return policy.shouldRotate(state, now);
    }

    /**
     * Check if event should trigger rotation based on policy.
     *
     * @param event Rotation event
     * @return true if event should trigger rotation
     */
    public boolean shouldRotateOnEvent(RotationEvent event) {
        return policy.shouldRotateOnEvent(event);
    }

    /**
     * Get current rotation state.
     *
     * @return Current key rotation state
     */
    public KeyRotationState getState() {
        return state;
    }

    /**
     * Get next version number (monotonically increasing).
     *
     * @return Next version number
     */
    private int getNextVersionNumber() {
        var versions = state.keyVersions();
        return versions.isEmpty() ? 1 : versions.lastKey() + 1;
    }
}
