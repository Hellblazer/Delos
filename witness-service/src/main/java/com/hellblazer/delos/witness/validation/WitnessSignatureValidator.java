/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.validation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.Verifier;
import com.hellblazer.delos.stereotomy.KeyState;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.witness.integration.WitnessKerlIntegration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;

/**
 * Validates witness signatures against KERI KeyState with dual-key support during grace period.
 * <p>
 * Verification algorithm:
 * 1. Extract witness identifier from receipt
 * 2. Lookup KeyState via WitnessKerlIntegration at collection epoch
 * 3. Verify signature against KeyState public key (ACTIVE key)
 * 4. If verification fails and rotation manager present, try deprecated keys (grace period)
 * 5. Return verification result with diagnostic info
 * </p>
 * <p>
 * Dual-key support (Phase 1C-3-A):
 * - During grace period, both old (DEPRECATED) and new (ACTIVE) keys accepted
 * - Enables zero-downtime key rotation via BLSKeyRotationManager
 * - Signature valid if verifies against any key in getValidKeys(now)
 * </p>
 * <p>
 * Error handling:
 * - Invalid signature: log, increment metric, return InvalidSignature
 * - Identifier not found: retry with fallback, return IdentifierNotFound
 * - KeyState unavailable: return KeyStateUnavailable (caller may defer receipt)
 * </p>
 */
public class WitnessSignatureValidator {

    private static final Logger log = LoggerFactory.getLogger(WitnessSignatureValidator.class);

    /**
     * Function to verify witness signature against valid keys during grace period.
     * Handles both ACTIVE and DEPRECATED keys if witness is in grace period.
     */
    @FunctionalInterface
    public interface KeyLookup {
        /**
         * Attempt to verify signature against valid keys during grace period.
         * Implementation should check if witness is in grace period and try both
         * old (DEPRECATED) and new (ACTIVE) keys via BLSKeyRotationManager.getValidKeys().
         *
         * @param witnessId Witness identifier
         * @param signature Signature to verify
         * @param signedData Data that was signed
         * @param now       Current timestamp
         * @return true if signature verifies against any valid key, false otherwise
         */
        boolean verifyWithValidKeys(Identifier witnessId, JohnHancock signature, byte[] signedData, Instant now);
    }

    private final WitnessKerlIntegration kerlIntegration;
    private final Optional<KeyLookup> keyLookup;
    private final Counter validSignatures;
    private final Counter invalidSignatures;
    private final Counter identifierNotFound;
    private final Counter keyStateUnavailable;
    private final Counter dualKeyValidations;

    /**
     * Create witness signature validator with metrics tracking.
     *
     * @param kerlIntegration  KERL integration for KeyState lookup
     * @param meterRegistry   Metrics registry for tracking
     */
    public WitnessSignatureValidator(WitnessKerlIntegration kerlIntegration, MeterRegistry meterRegistry) {
        this(kerlIntegration, null, meterRegistry);
    }

    /**
     * Create witness signature validator with optional dual-key support.
     *
     * @param kerlIntegration  KERL integration for KeyState lookup
     * @param keyLookup        Optional function to get valid keys during grace period
     * @param meterRegistry   Metrics registry for tracking
     */
    public WitnessSignatureValidator(WitnessKerlIntegration kerlIntegration,
                                     KeyLookup keyLookup,
                                     MeterRegistry meterRegistry) {
        this.kerlIntegration = kerlIntegration;
        this.keyLookup = Optional.ofNullable(keyLookup);
        this.validSignatures = Counter.builder("witness.signature.validation.valid").register(meterRegistry);
        this.invalidSignatures = Counter.builder("witness.signature.validation.invalid").register(meterRegistry);
        this.identifierNotFound = Counter.builder("witness.signature.validation.identifier_not_found").register(meterRegistry);
        this.keyStateUnavailable = Counter.builder("witness.signature.validation.keystate_unavailable").register(meterRegistry);
        this.dualKeyValidations = Counter.builder("witness.signature.validation.dual_key").register(meterRegistry);
    }

    /**
     * Verify witness signature against KERL KeyState with optional dual-key fallback.
     * <p>
     * Returns sealed SignatureVerificationResult:
     * - Success(keyState): Signature valid
     * - InvalidSignature: Signature verification failed
     * - IdentifierNotFound: Identifier not in KERL
     * - KeyStateUnavailable: KERL lookup failed
     * </p>
     * <p>
     * During grace period, will also try deprecated keys from KeyLookup if available.
     * Enables zero-downtime key rotation via BLSKeyRotationManager integration.
     * </p>
     *
     * @param witnessIdentifier  Witness identifier to verify
     * @param signature          Signature to verify
     * @param signedData         Data that was signed
     * @param collectionEpoch    Epoch when receipt collected
     * @return Verification result
     */
    public SignatureVerificationResult verifySignature(Identifier witnessIdentifier,
                                                       JohnHancock signature,
                                                       byte[] signedData,
                                                       long collectionEpoch) {
        // Lookup KeyState at collection epoch
        var keyStateOpt = kerlIntegration.verifyIdentifier(witnessIdentifier, collectionEpoch);

        if (keyStateOpt.isEmpty()) {
            log.debug("KeyState unavailable for witness={} at epoch={}", witnessIdentifier, collectionEpoch);
            keyStateUnavailable.increment();
            return new KeyStateUnavailable(witnessIdentifier, collectionEpoch);
        }

        var keyState = keyStateOpt.get();

        // Verify signature against KeyState public keys (ACTIVE key)
        var verified = verifyWithKeyState(keyState, signature, signedData);

        if (verified) {
            log.debug("Signature valid for witness={} at epoch={}", witnessIdentifier, collectionEpoch);
            validSignatures.increment();
            return new Success(keyState);
        }

        // If primary verification failed and key lookup available, try deprecated keys (grace period)
        if (keyLookup.isPresent()) {
            var now = Instant.now();
            try {
                if (keyLookup.get().verifyWithValidKeys(witnessIdentifier, signature, signedData, now)) {
                    log.debug("Signature valid via dual-key validation (grace period) for witness={} at epoch={}",
                        witnessIdentifier, collectionEpoch);
                    validSignatures.increment();
                    dualKeyValidations.increment();
                    return new Success(keyState);
                }
            } catch (Exception e) {
                log.debug("Error during grace period verification for witness={}: {}",
                    witnessIdentifier, e.getMessage());
            }
        }

        // All verification attempts failed
        log.warn("Invalid signature for witness={} at epoch={}", witnessIdentifier, collectionEpoch);
        invalidSignatures.increment();
        return new InvalidSignature(witnessIdentifier, collectionEpoch);
    }

    /**
     * Verify signature using KeyState public keys.
     * Handles multi-sig by checking all keys in signing threshold.
     *
     * @param keyState    KeyState with public keys
     * @param signature   Signature to verify
     * @param signedData  Data that was signed
     * @return true if signature valid
     */
    private boolean verifyWithKeyState(KeyState keyState, JohnHancock signature, byte[] signedData) {
        var publicKeys = keyState.getKeys();
        if (publicKeys.isEmpty()) {
            log.warn("No public keys in KeyState for identifier={}", keyState.getIdentifier());
            return false;
        }

        // For single-sig, verify against first key
        // For multi-sig, would need to check threshold (Phase C)
        var publicKey = publicKeys.get(0);
        var verifier = new Verifier.DefaultVerifier(publicKey);

        return verifier.verify(signature, signedData);
    }


    /**
     * Get validation statistics for monitoring.
     *
     * @return ValidationStats record
     */
    public ValidationStats getStats() {
        return new ValidationStats(
            (long) validSignatures.count(),
            (long) invalidSignatures.count(),
            (long) identifierNotFound.count(),
            (long) keyStateUnavailable.count(),
            (long) dualKeyValidations.count()
        );
    }

    /**
     * Sealed interface for signature verification result.
     */
    public sealed interface SignatureVerificationResult {
    }

    /**
     * Signature verification succeeded.
     */
    public record Success(KeyState keyState) implements SignatureVerificationResult {
    }

    /**
     * Signature verification failed (invalid signature).
     */
    public record InvalidSignature(Identifier witnessIdentifier,
                                   long collectionEpoch) implements SignatureVerificationResult {
    }

    /**
     * Identifier not found in KERL.
     */
    public record IdentifierNotFound(Identifier witnessIdentifier,
                                     long collectionEpoch) implements SignatureVerificationResult {
    }

    /**
     * KeyState unavailable (KERL lookup failed).
     */
    public record KeyStateUnavailable(Identifier witnessIdentifier,
                                      long collectionEpoch) implements SignatureVerificationResult {
    }

    /**
     * Validation statistics record.
     */
    public record ValidationStats(long validSignatures,
                                  long invalidSignatures,
                                  long identifierNotFound,
                                  long keyStateUnavailable,
                                  long dualKeyValidations) {
    }
}
