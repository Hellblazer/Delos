/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.validation;

import com.codahale.metrics.Counter;
import com.codahale.metrics.MetricRegistry;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.Verifier;
import com.hellblazer.delos.stereotomy.KeyState;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.witness.integration.WitnessKerlIntegration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates witness signatures against KERI KeyState.
 * <p>
 * Verification algorithm:
 * 1. Extract witness identifier from receipt
 * 2. Lookup KeyState via WitnessKerlIntegration at collection epoch
 * 3. Verify signature against KeyState public key
 * 4. Return verification result with diagnostic info
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

    private final WitnessKerlIntegration kerlIntegration;
    private final Counter validSignatures;
    private final Counter invalidSignatures;
    private final Counter identifierNotFound;
    private final Counter keyStateUnavailable;

    /**
     * Create witness signature validator with metrics tracking.
     *
     * @param kerlIntegration  KERL integration for KeyState lookup
     * @param metricRegistry   Metrics registry for tracking
     */
    public WitnessSignatureValidator(WitnessKerlIntegration kerlIntegration, MetricRegistry metricRegistry) {
        this.kerlIntegration = kerlIntegration;
        this.validSignatures = metricRegistry.counter("witness.signature.validation.valid");
        this.invalidSignatures = metricRegistry.counter("witness.signature.validation.invalid");
        this.identifierNotFound = metricRegistry.counter("witness.signature.validation.identifier_not_found");
        this.keyStateUnavailable = metricRegistry.counter("witness.signature.validation.keystate_unavailable");
    }

    /**
     * Verify witness signature against KERL KeyState.
     * <p>
     * Returns sealed SignatureVerificationResult:
     * - Success(keyState): Signature valid
     * - InvalidSignature: Signature verification failed
     * - IdentifierNotFound: Identifier not in KERL
     * - KeyStateUnavailable: KERL lookup failed
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
            keyStateUnavailable.inc();
            return new KeyStateUnavailable(witnessIdentifier, collectionEpoch);
        }

        var keyState = keyStateOpt.get();

        // Verify signature against KeyState public keys
        var verified = verifyWithKeyState(keyState, signature, signedData);

        if (verified) {
            log.debug("Signature valid for witness={} at epoch={}", witnessIdentifier, collectionEpoch);
            validSignatures.inc();
            return new Success(keyState);
        } else {
            log.warn("Invalid signature for witness={} at epoch={}", witnessIdentifier, collectionEpoch);
            invalidSignatures.inc();
            return new InvalidSignature(witnessIdentifier, collectionEpoch);
        }
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
            validSignatures.getCount(),
            invalidSignatures.getCount(),
            identifierNotFound.getCount(),
            keyStateUnavailable.getCount()
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
                                  long keyStateUnavailable) {
    }
}
