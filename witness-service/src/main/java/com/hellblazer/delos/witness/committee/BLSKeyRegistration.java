/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.committee;

import com.hellblazer.delos.cryptography.bls.BLSPublicKey;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.cryptography.bls.ProofOfPossession;
import com.hellblazer.delos.stereotomy.identifier.Identifier;

import java.time.Instant;
import java.util.Objects;

/**
 * BLS key registration record with Proof of Possession and registration signature.
 * Immutable record for thread-safe sharing during committee key management.
 * <p>
 * Two-step validation design:
 * <ul>
 *   <li><b>proofOfPossession</b>: Proves key ownership (signs public key itself)</li>
 *   <li><b>registrationSignature</b>: Proves member authorization (signs member identifier)</li>
 * </ul>
 * <p>
 * This separation prevents unauthorized key registration by requiring both:
 * 1. Possession of the BLS secret key (PoP)
 * 2. Authorization from the committee member (registration signature)
 *
 * @param memberId               Committee member identifier
 * @param publicKey              BLS public key with embedded PoP
 * @param proofOfPossession      Proof of key ownership (signature over public key)
 * @param registrationSignature  Member authorization (signature over member identifier)
 * @param registrationEpoch      Epoch when key was registered
 * @param registrationTime       Wall-clock time of registration
 * @author hal.hildebrand
 */
public record BLSKeyRegistration(
    Identifier memberId,
    BLSPublicKey publicKey,
    ProofOfPossession proofOfPossession,
    BLSSignature registrationSignature,
    long registrationEpoch,
    Instant registrationTime
) {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException     if any reference parameter is null
     * @throws IllegalArgumentException if registrationEpoch is negative
     */
    public BLSKeyRegistration {
        Objects.requireNonNull(memberId, "memberId cannot be null");
        Objects.requireNonNull(publicKey, "publicKey cannot be null");
        Objects.requireNonNull(proofOfPossession, "proofOfPossession cannot be null");
        Objects.requireNonNull(registrationSignature, "registrationSignature cannot be null");
        Objects.requireNonNull(registrationTime, "registrationTime cannot be null");
        if (registrationEpoch < 0) {
            throw new IllegalArgumentException("registrationEpoch must be >= 0, got: " + registrationEpoch);
        }
    }

    /**
     * Create a key registration for the current epoch and time.
     * <p>
     * Convenience factory method for creating registrations with current timestamp.
     *
     * @param memberId              Committee member identifier
     * @param publicKey             BLS public key with embedded PoP
     * @param proofOfPossession     Proof of key ownership
     * @param registrationSignature Member authorization signature
     * @param currentEpoch          Current epoch number
     * @return New BLSKeyRegistration with current timestamp
     */
    public static BLSKeyRegistration create(
        Identifier memberId,
        BLSPublicKey publicKey,
        ProofOfPossession proofOfPossession,
        BLSSignature registrationSignature,
        long currentEpoch
    ) {
        return new BLSKeyRegistration(
            memberId,
            publicKey,
            proofOfPossession,
            registrationSignature,
            currentEpoch,
            Instant.now()
        );
    }
}
