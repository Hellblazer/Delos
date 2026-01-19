/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.delos.witness.committee;

import com.hellblazer.delos.cryptography.bls.BLSOperations;
import com.hellblazer.delos.cryptography.bls.BLSProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Validates Proof of Possession and registration signature for BLS key registration.
 * Implements two-step validation: key ownership proof + member authorization.
 * <p>
 * AUDIT FIX (Issue #2): Separated concerns between:
 * - Step 1: Standard BLS PoP (signs public key itself) - proves key ownership
 * - Step 2: Registration signature (signs member ID) - proves member authorization
 * <p>
 * Thread-safe: This validator is stateless and can be safely used across virtual threads.
 *
 * @author hal.hildebrand
 */
public final class ProofOfPossessionValidator {

    private static final Logger log = LoggerFactory.getLogger(ProofOfPossessionValidator.class);

    private final BLSProvider blsProvider;

    /**
     * Create a ProofOfPossessionValidator with a BLS provider.
     *
     * @param blsProvider BLS provider for cryptographic operations
     * @throws NullPointerException if blsProvider is null
     */
    public ProofOfPossessionValidator(BLSProvider blsProvider) {
        this.blsProvider = Objects.requireNonNull(blsProvider, "blsProvider cannot be null");
    }

    /**
     * Validate both Proof of Possession and registration signature for key registration.
     * <p>
     * Two-step validation:
     * 1. PoP proves the registrant possesses the private key (key ownership).
     * 2. Registration signature proves the member ID authorizes this key (member authorization).
     * <p>
     * This design prevents unauthorized key registration by requiring both:
     * - Possession of the BLS secret key (via PoP)
     * - Authorization from the committee member (via registration signature)
     *
     * @param registration Key registration to validate
     * @return ValidationResult indicating success or failure reason
     * @throws NullPointerException if registration is null
     */
    public ValidationResult validate(BLSKeyRegistration registration) {
        Objects.requireNonNull(registration, "registration cannot be null");

        try {
            // Step 1: Verify Proof of Possession (key ownership)
            // Standard BLS PoP signs the public key itself
            var popValid = registration.proofOfPossession().verify(
                registration.publicKey().toBytesCompressed(),
                blsProvider
            );

            if (!popValid) {
                log.warn("PoP validation failed for member: {}", registration.memberId());
                return new ValidationResult.Invalid("Proof of Possession signature verification failed");
            }

            // Step 2: Verify registration signature (member authorization)
            // Registration signature signs the member identifier, proving authorization
            var memberMessage = registration.memberId().getDigest(null).getBytes();
            var authValid = BLSOperations.verify(
                registration.publicKey(),
                memberMessage,
                registration.registrationSignature()
            );

            if (!authValid) {
                log.warn("Registration signature validation failed for member: {}", registration.memberId());
                return new ValidationResult.Invalid("Registration signature verification failed");
            }

            log.debug("Two-step validation successful for member: {}", registration.memberId());
            return new ValidationResult.Valid(registration);

        } catch (Exception e) {
            log.error("Validation error for member: {}", registration.memberId(), e);
            return new ValidationResult.Error("Validation exception: " + e.getMessage());
        }
    }

    /**
     * Validation result sealed interface.
     * <p>
     * Provides type-safe results for validation operations:
     * - Valid: Registration passed both PoP and authorization checks
     * - Invalid: Registration failed validation (reason provided)
     * - Error: Exception occurred during validation
     */
    public sealed interface ValidationResult {
        /**
         * Validation succeeded - registration is valid.
         *
         * @param registration The validated registration
         */
        record Valid(BLSKeyRegistration registration) implements ValidationResult {}

        /**
         * Validation failed - registration is invalid.
         *
         * @param reason Human-readable reason for failure
         */
        record Invalid(String reason) implements ValidationResult {}

        /**
         * Validation error - exception occurred.
         *
         * @param message Error message from exception
         */
        record Error(String message) implements ValidationResult {}
    }
}
