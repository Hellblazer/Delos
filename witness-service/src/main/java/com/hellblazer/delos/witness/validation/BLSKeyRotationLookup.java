/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.validation;

import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.bls.rotation.BLSKeyRotationManager;
import com.hellblazer.delos.cryptography.bls.rotation.KeyStatus;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KeyLookup implementation using BLSKeyRotationManager for grace period verification.
 * <p>
 * Enables dual-key validation during key rotation grace period:
 * - Checks if member is in grace period via rotation manager
 * - Returns valid keys (ACTIVE + DEPRECATED) for verification
 * - Falls back to ACTIVE key if no rotation in progress
 * </p>
 *
 * @author hal.hildebrand
 * @since 1.0 (Phase 1C-3-A)
 */
public class BLSKeyRotationLookup implements WitnessSignatureValidator.KeyLookup {

    private static final Logger log = LoggerFactory.getLogger(BLSKeyRotationLookup.class);

    private final Map<Identifier, BLSKeyRotationManager> memberRotationManagers;

    /**
     * Create BLS key rotation lookup for grace period verification.
     *
     * @param memberRotationManagers Map from member identifier to their rotation manager
     */
    public BLSKeyRotationLookup(Map<Identifier, BLSKeyRotationManager> memberRotationManagers) {
        this.memberRotationManagers = Objects.requireNonNull(memberRotationManagers,
            "memberRotationManagers cannot be null");
    }

    /**
     * Attempt to verify signature against valid keys during grace period.
     * <p>
     * If member has a rotation manager and is in grace period, will accept both
     * ACTIVE and DEPRECATED (old) keys. Otherwise, only ACTIVE keys are accepted.
     * </p>
     *
     * @param witnessId Witness identifier
     * @param signature Signature to verify
     * @param signedData Data that was signed
     * @param now Current timestamp
     * @return true if signature verifies against any valid key
     */
    @Override
    public boolean verifyWithValidKeys(Identifier witnessId, JohnHancock signature, byte[] signedData, Instant now) {
        // Get rotation manager for this witness
        var rotationManager = memberRotationManagers.get(witnessId);
        if (rotationManager == null) {
            log.debug("No rotation manager for witness {}, grace period verification skipped", witnessId);
            return false;
        }

        try {
            // Get valid keys (both ACTIVE and DEPRECATED if in grace period)
            var validKeys = rotationManager.getValidKeys(now);
            if (validKeys.isEmpty()) {
                log.debug("No valid keys for witness {} at {}", witnessId, now);
                return false;
            }

            // Get signature bytes for verification
            // Note: For BLS, we use the first signature from the array
            var signatureArray = signature.getBytes();
            if (signatureArray == null || signatureArray.length == 0) {
                log.debug("Empty signature for witness {}", witnessId);
                return false;
            }
            var signatureBytes = signatureArray[0];

            // Try verification with each valid key
            for (var keyVersion : validKeys.values()) {
                try {
                    // Log which key type was used for monitoring
                    var keyStatus = keyVersion.status();

                    // Attempt BLS verification with this key
                    // In a full implementation, public key bytes would be retrieved from KERI
                    // For now, this serves as the grace period verification hook
                    if (verifySignatureWithKey(signedData, signatureBytes, keyVersion)) {
                        if (keyStatus == KeyStatus.DEPRECATED) {
                            log.debug("Signature verified via DEPRECATED key (grace period) for witness {}", witnessId);
                        } else {
                            log.debug("Signature verified via {} key for witness {}", keyStatus, witnessId);
                        }
                        return true;
                    }
                } catch (Exception e) {
                    log.debug("Verification failed with key version {} for witness {}: {}",
                        keyVersion.versionNumber(), witnessId, e.getMessage());
                }
            }

            return false;
        } catch (Exception e) {
            log.debug("Error verifying with valid keys for witness {}: {}", witnessId, e.getMessage());
            return false;
        }
    }

    /**
     * Verify signature with a specific key version.
     * <p>
     * This method delegates to KERI/stereotomy integration to retrieve
     * the actual public key bytes for the given key version, then verifies
     * the signature using BLS verification.
     * </p>
     * <p>
     * BLS signature verification expects:
     * - 48-byte compressed public key (G1 point)
     * - 96-byte compressed signature (G2 point)
     * </p>
     *
     * @param signedData Data that was signed
     * @param signatureBytes 96-byte compressed BLS signature
     * @param keyVersion Key version to verify against
     * @return true if signature is valid
     */
    private boolean verifySignatureWithKey(byte[] signedData, byte[] signatureBytes,
                                          com.hellblazer.delos.cryptography.bls.rotation.KeyVersion keyVersion) {
        // Validate signature size
        if (signatureBytes == null || signatureBytes.length != 96) {
            log.debug("Invalid signature size: {} (expected 96)",
                     signatureBytes != null ? signatureBytes.length : "null");
            return false;
        }

        try {
            // Note: In production, the public key bytes for this key version
            // would be retrieved from KERI/stereotomy based on the member's
            // identifier and key version number. This is a placeholder
            // for the actual integration.
            //
            // For now, return false - full KERI integration is Phase 1C-3-B
            // This serves as the extension hook for grace period verification.
            log.debug("Grace period verification hook for key version {}: KERI integration pending",
                     keyVersion.versionNumber());
            return false;
        } catch (Exception e) {
            log.debug("BLS verification failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Register a rotation manager for a member.
     * Called when member joins committee or rotation begins.
     *
     * @param memberId Member identifier
     * @param rotationManager Rotation manager for this member
     */
    public void registerRotationManager(Identifier memberId, BLSKeyRotationManager rotationManager) {
        Objects.requireNonNull(memberId, "memberId cannot be null");
        Objects.requireNonNull(rotationManager, "rotationManager cannot be null");
        memberRotationManagers.put(memberId, rotationManager);
    }

    /**
     * Unregister rotation manager when member is no longer active.
     *
     * @param memberId Member identifier
     */
    public void unregisterRotationManager(Identifier memberId) {
        memberRotationManagers.remove(memberId);
    }

    /**
     * Get rotation manager for a member (useful for testing/monitoring).
     *
     * @param memberId Member identifier
     * @return Rotation manager or null if not registered
     */
    public BLSKeyRotationManager getRotationManager(Identifier memberId) {
        return memberRotationManagers.get(memberId);
    }

    /**
     * Clear all registered rotation managers.
     */
    public void clear() {
        memberRotationManagers.clear();
    }
}
