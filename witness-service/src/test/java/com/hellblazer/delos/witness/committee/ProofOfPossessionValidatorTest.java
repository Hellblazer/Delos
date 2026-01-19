/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.delos.witness.committee;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.BLSOperations;
import com.hellblazer.delos.cryptography.bls.BLSProvider;
import com.hellblazer.delos.cryptography.bls.ProofOfPossession;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test suite for ProofOfPossessionValidator.
 * Validates the two-step validation process:
 * 1. Proof of Possession (key ownership)
 * 2. Registration signature (member authorization)
 *
 * @author hal.hildebrand
 */
class ProofOfPossessionValidatorTest {

    private ProofOfPossessionValidator validator;
    private BLSProvider blsProvider;
    private Random random;

    @BeforeEach
    void setUp() {
        blsProvider = BLSProvider.getDefault();
        validator = new ProofOfPossessionValidator(blsProvider);
        random = new Random(42);
    }

    private Identifier createMemberId() {
        var digest = DigestAlgorithm.DEFAULT.digest("member-" + random.nextLong());
        return new SelfAddressingIdentifier(digest);
    }

    @Test
    void testValidPoP_ReturnsValid() {
        var keyPair = BLSOperations.generateKeyPair(random);
        var memberId = createMemberId();
        var memberMessage = memberId.getDigest(DigestAlgorithm.DEFAULT).getBytes();
        var registrationSignature = keyPair.sign(memberMessage);
        var registration = BLSKeyRegistration.create(memberId, keyPair.publicKey(),
            keyPair.publicKey().proofOfPossession(), registrationSignature, 1L);

        var result = validator.validate(registration);

        assertThat(result).isInstanceOf(ProofOfPossessionValidator.ValidationResult.Valid.class);
        var valid = (ProofOfPossessionValidator.ValidationResult.Valid) result;
        assertThat(valid.registration()).isEqualTo(registration);
    }

    @Test
    void testInvalidPop_ReturnsInvalid() {
        var keyPair1 = BLSOperations.generateKeyPair(random);
        var keyPair2 = BLSOperations.generateKeyPair(random);
        var memberId = createMemberId();
        var invalidPoP = keyPair2.publicKey().proofOfPossession();
        var memberMessage = memberId.getDigest(DigestAlgorithm.DEFAULT).getBytes();
        var registrationSignature = keyPair1.sign(memberMessage);
        var registration = BLSKeyRegistration.create(memberId, keyPair1.publicKey(),
            invalidPoP, registrationSignature, 1L);

        var result = validator.validate(registration);

        assertThat(result).isInstanceOf(ProofOfPossessionValidator.ValidationResult.Invalid.class);
        var invalid = (ProofOfPossessionValidator.ValidationResult.Invalid) result;
        assertThat(invalid.reason()).contains("Proof of Possession");
    }

    @Test
    void testInvalidRegistrationSignature_ReturnsInvalid() {
        var keyPair = BLSOperations.generateKeyPair(random);
        var memberId = createMemberId();
        var wrongMessage = "wrong message".getBytes();
        var invalidRegistrationSignature = keyPair.sign(wrongMessage);
        var registration = BLSKeyRegistration.create(memberId, keyPair.publicKey(),
            keyPair.publicKey().proofOfPossession(), invalidRegistrationSignature, 1L);

        var result = validator.validate(registration);

        assertThat(result).isInstanceOf(ProofOfPossessionValidator.ValidationResult.Invalid.class);
        var invalid = (ProofOfPossessionValidator.ValidationResult.Invalid) result;
        assertThat(invalid.reason()).contains("Registration signature");
    }

    @Test
    void testBothInvalid_ReturnsInvalid() {
        var keyPair1 = BLSOperations.generateKeyPair(random);
        var keyPair2 = BLSOperations.generateKeyPair(random);
        var memberId = createMemberId();
        var invalidPoP = keyPair2.publicKey().proofOfPossession();
        var wrongMessage = "wrong message".getBytes();
        var invalidRegistrationSignature = keyPair1.sign(wrongMessage);
        var registration = BLSKeyRegistration.create(memberId, keyPair1.publicKey(),
            invalidPoP, invalidRegistrationSignature, 1L);

        var result = validator.validate(registration);

        assertThat(result).isInstanceOf(ProofOfPossessionValidator.ValidationResult.Invalid.class);
        var invalid = (ProofOfPossessionValidator.ValidationResult.Invalid) result;
        assertThat(invalid.reason()).contains("Proof of Possession");
    }

    @Test
    void testValidationException_ReturnsError() {
        var keyPair = BLSOperations.generateKeyPair(random);
        var memberId = createMemberId();
        var corruptedPopBytes = new byte[96];
        random.nextBytes(corruptedPopBytes);
        var corruptedPoP = new ProofOfPossession(corruptedPopBytes);
        var memberMessage = memberId.getDigest(DigestAlgorithm.DEFAULT).getBytes();
        var registrationSignature = keyPair.sign(memberMessage);
        var registration = BLSKeyRegistration.create(memberId, keyPair.publicKey(),
            corruptedPoP, registrationSignature, 1L);

        var result = validator.validate(registration);

        assertThat(result).isInstanceOf(ProofOfPossessionValidator.ValidationResult.Invalid.class);
    }

    @Test
    void testNullRegistration_Throws() {
        assertThatThrownBy(() -> validator.validate(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("registration cannot be null");
    }
}
