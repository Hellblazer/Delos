/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.delos.witness.committee;

import com.hellblazer.delos.cryptography.bls.BLSOperations;
import com.hellblazer.delos.cryptography.bls.BLSProvider;
import com.hellblazer.delos.cryptography.bls.BLSPublicKey;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.witness.committee.ProofOfPossessionValidator.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Service orchestrating BLS key registration workflow for committee members.
 * <p>
 * Coordinates validation and storage of BLS public keys with Proof of Possession.
 * This service is the primary entry point for key registration operations, delegating
 * validation to ProofOfPossessionValidator and storage to CommitteeBLSKeyStore.
 * <p>
 * <b>Registration Workflow</b>:
 * <ol>
 *   <li>Validate Proof of Possession (via ProofOfPossessionValidator)</li>
 *   <li>Validate registration signature (via ProofOfPossessionValidator)</li>
 *   <li>Store validated key (via CommitteeBLSKeyStore)</li>
 *   <li>Return ValidationResult (Valid/Invalid/Error)</li>
 * </ol>
 * <p>
 * <b>Thread Safety</b>:
 * Stateless design with immutable dependencies. All operations delegated to
 * thread-safe components (CommitteeBLSKeyStore, ProofOfPossessionValidator).
 * Safe for concurrent access from virtual threads.
 *
 * @author hal.hildebrand
 */
public final class KeyRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(KeyRegistrationService.class);

    private final CommitteeBLSKeyStore keyStore;
    private final ProofOfPossessionValidator popValidator;

    /**
     * Create KeyRegistrationService with required dependencies.
     *
     * @param keyStore      Storage for committee member BLS keys
     * @param popValidator  Validator for Proof of Possession
     * @throws NullPointerException if any parameter is null
     */
    public KeyRegistrationService(CommitteeBLSKeyStore keyStore,
                                   ProofOfPossessionValidator popValidator) {
        this.keyStore = Objects.requireNonNull(keyStore, "keyStore cannot be null");
        this.popValidator = Objects.requireNonNull(popValidator, "popValidator cannot be null");
    }

    /**
     * Register a BLS public key for a committee member.
     * <p>
     * Validates the registration using two-step validation:
     * 1. Proof of Possession (proves key ownership)
     * 2. Registration signature (proves member authorization)
     * <p>
     * Only stores the key if both validation steps pass.
     *
     * @param registration Key registration with PoP and signature
     * @return ValidationResult indicating success (Valid) or failure (Invalid/Error)
     * @throws NullPointerException if registration is null
     */
    public ValidationResult registerKey(BLSKeyRegistration registration) {
        Objects.requireNonNull(registration, "registration cannot be null");

        try {
            // Step 1: Validate PoP and registration signature
            var validationResult = popValidator.validate(registration);

            if (validationResult instanceof ValidationResult.Invalid invalid) {
                log.warn("Key registration failed validation for member {}: {}",
                    registration.memberId(), invalid.reason());
                return invalid;
            }

            if (validationResult instanceof ValidationResult.Error error) {
                log.error("Key registration validation error for member {}: {}",
                    registration.memberId(), error.message());
                return error;
            }

            // Step 2: Register key in store
            var registered = keyStore.registerKey(registration);

            if (registered) {
                log.debug("Successfully registered key for member: {}", registration.memberId());
                return new ValidationResult.Valid(registration);
            } else {
                log.warn("Key registration rejected by store for member: {}", registration.memberId());
                return new ValidationResult.Invalid("Store rejected key registration (key already exists)");
            }
        } catch (Exception e) {
            log.error("Unexpected error during key registration for member: {}", registration.memberId(), e);
            return new ValidationResult.Error(e.getMessage());
        }
    }

    /**
     * Get the BLS public key for a committee member.
     * <p>
     * Returns the registered key if one exists, or empty if no key is registered.
     *
     * @param memberId Committee member identifier
     * @return Optional containing the public key if registered, empty otherwise
     * @throws NullPointerException if memberId is null
     */
    public Optional<BLSPublicKey> getPublicKey(Identifier memberId) {
        Objects.requireNonNull(memberId, "memberId cannot be null");
        return keyStore.getPublicKey(memberId);
    }

    /**
     * Get all registered BLS public keys.
     * <p>
     * Returns a snapshot Map of all currently registered keys.
     * The returned map is a defensive copy and can be safely modified.
     *
     * @return Map of member identifiers to BLS public keys (never null, may be empty)
     */
    public Map<Identifier, BLSPublicKey> getPublicKeys() {
        var members = keyStore.registeredMembers();
        var result = new HashMap<Identifier, BLSPublicKey>(members.size());

        for (var memberId : members) {
            keyStore.getPublicKey(memberId).ifPresent(key -> result.put(memberId, key));
        }

        return result;
    }

    /**
     * Check if a member has a registered BLS key.
     * <p>
     * This is more efficient than calling {@code getPublicKey().isPresent()}
     * when only existence needs to be checked.
     *
     * @param memberId Committee member identifier
     * @return true if key is registered, false otherwise
     * @throws NullPointerException if memberId is null
     */
    public boolean hasKey(Identifier memberId) {
        Objects.requireNonNull(memberId, "memberId cannot be null");
        return keyStore.hasKey(memberId);
    }
}
