/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.delos.witness.committee;

import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.cryptography.bls.*;
import com.hellblazer.delos.stereotomy.identifier.BasicIdentifier;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive TDD test suite for BLSKeyRegistration record.
 * Tests all validation rules, factory methods, and immutability.
 *
 * @author hal.hildebrand
 */
class BLSKeyRegistrationTest {

    private Identifier memberId;
    private BLSPublicKey publicKey;
    private ProofOfPossession proofOfPossession;
    private BLSSignature registrationSignature;
    private long registrationEpoch;
    private Instant registrationTime;
    private BLSKeyPair testKeyPair;
    private BLSProvider blsProvider;

    @BeforeEach
    void setUp() {
        // Create test fixtures using BLS API
        blsProvider = BLSProvider.getDefault();
        var blsRandom = BLSTestFixtures.deterministicRandom(0x4242L);
        testKeyPair = BLSKeyPair.generate(blsRandom, blsProvider);

        // Create test member identifier
        var idRandom = BLSTestFixtures.deterministicRandom(0xAAAAL);
        var idKey = SignatureAlgorithm.ED_25519.generateKeyPair(idRandom);
        memberId = new BasicIdentifier(idKey.getPublic());

        // Extract BLS components
        publicKey = testKeyPair.publicKey();
        proofOfPossession = publicKey.proofOfPossession();
        registrationSignature = testKeyPair.sign(memberId.toIdent().toByteArray());
        registrationEpoch = 100L;
        registrationTime = Instant.now();
    }

    @Test
    @DisplayName("Valid construction with all required fields")
    void testValidConstruction() {
        // Given valid inputs

        // When constructing record
        var registration = new BLSKeyRegistration(
            memberId,
            publicKey,
            proofOfPossession,
            registrationSignature,
            registrationEpoch,
            registrationTime
        );

        // Then all fields are correctly set
        assertNotNull(registration);
        assertEquals(memberId, registration.memberId());
        assertEquals(publicKey, registration.publicKey());
        assertEquals(proofOfPossession, registration.proofOfPossession());
        assertEquals(registrationSignature, registration.registrationSignature());
        assertEquals(registrationEpoch, registration.registrationEpoch());
        assertEquals(registrationTime, registration.registrationTime());
    }

    @Test
    @DisplayName("Null memberId throws NullPointerException")
    void testNullMemberIdThrows() {
        // When constructing with null memberId
        // Then NPE is thrown
        var exception = assertThrows(NullPointerException.class, () -> {
            new BLSKeyRegistration(
                null,
                publicKey,
                proofOfPossession,
                registrationSignature,
                registrationEpoch,
                registrationTime
            );
        });

        assertTrue(exception.getMessage().contains("memberId"));
    }

    @Test
    @DisplayName("Null publicKey throws NullPointerException")
    void testNullPublicKeyThrows() {
        // When constructing with null publicKey
        // Then NPE is thrown
        var exception = assertThrows(NullPointerException.class, () -> {
            new BLSKeyRegistration(
                memberId,
                null,
                proofOfPossession,
                registrationSignature,
                registrationEpoch,
                registrationTime
            );
        });

        assertTrue(exception.getMessage().contains("publicKey"));
    }

    @Test
    @DisplayName("Null proofOfPossession throws NullPointerException")
    void testNullPoPThrows() {
        // When constructing with null proofOfPossession
        // Then NPE is thrown
        var exception = assertThrows(NullPointerException.class, () -> {
            new BLSKeyRegistration(
                memberId,
                publicKey,
                null,
                registrationSignature,
                registrationEpoch,
                registrationTime
            );
        });

        assertTrue(exception.getMessage().contains("proofOfPossession"));
    }

    @Test
    @DisplayName("Null registrationSignature throws NullPointerException (AUDIT FIX #2)")
    void testNullRegistrationSignatureThrows() {
        // When constructing with null registrationSignature
        // Then NPE is thrown (validates audit fix #2 field)
        var exception = assertThrows(NullPointerException.class, () -> {
            new BLSKeyRegistration(
                memberId,
                publicKey,
                proofOfPossession,
                null,
                registrationEpoch,
                registrationTime
            );
        });

        assertTrue(exception.getMessage().contains("registrationSignature"));
    }

    @Test
    @DisplayName("Negative registrationEpoch throws IllegalArgumentException")
    void testNegativeEpochThrows() {
        // When constructing with negative epoch
        // Then IllegalArgumentException is thrown
        var exception = assertThrows(IllegalArgumentException.class, () -> {
            new BLSKeyRegistration(
                memberId,
                publicKey,
                proofOfPossession,
                registrationSignature,
                -1L,
                registrationTime
            );
        });

        assertTrue(exception.getMessage().contains("registrationEpoch"));
        assertTrue(exception.getMessage().contains("-1"));
    }

    @Test
    @DisplayName("Null registrationTime throws NullPointerException")
    void testNullRegistrationTimeThrows() {
        // When constructing with null registrationTime
        // Then NPE is thrown
        var exception = assertThrows(NullPointerException.class, () -> {
            new BLSKeyRegistration(
                memberId,
                publicKey,
                proofOfPossession,
                registrationSignature,
                registrationEpoch,
                null
            );
        });

        assertTrue(exception.getMessage().contains("registrationTime"));
    }

    @Test
    @DisplayName("Factory method creates registration with current timestamp")
    void testCreateFactoryMethod() {
        // Given current epoch
        var currentEpoch = 42L;
        var beforeCreate = Instant.now();

        // When using factory method
        var registration = BLSKeyRegistration.create(
            memberId,
            publicKey,
            proofOfPossession,
            registrationSignature,
            currentEpoch
        );

        var afterCreate = Instant.now();

        // Then registration is created with current time
        assertNotNull(registration);
        assertEquals(memberId, registration.memberId());
        assertEquals(publicKey, registration.publicKey());
        assertEquals(proofOfPossession, registration.proofOfPossession());
        assertEquals(registrationSignature, registration.registrationSignature());
        assertEquals(currentEpoch, registration.registrationEpoch());

        // Timestamp should be between before/after create
        assertFalse(registration.registrationTime().isBefore(beforeCreate));
        assertFalse(registration.registrationTime().isAfter(afterCreate));
    }

    @Test
    @DisplayName("Zero epoch is valid (genesis case)")
    void testZeroEpochValid() {
        // When constructing with epoch 0 (genesis)
        var registration = new BLSKeyRegistration(
            memberId,
            publicKey,
            proofOfPossession,
            registrationSignature,
            0L,
            registrationTime
        );

        // Then registration is valid
        assertNotNull(registration);
        assertEquals(0L, registration.registrationEpoch());
    }

    @Test
    @DisplayName("Record is immutable (accessor methods return same values)")
    void testImmutability() {
        // Given a registration
        var registration = new BLSKeyRegistration(
            memberId,
            publicKey,
            proofOfPossession,
            registrationSignature,
            registrationEpoch,
            registrationTime
        );

        // When accessing fields multiple times
        // Then values remain consistent
        assertEquals(memberId, registration.memberId());
        assertEquals(memberId, registration.memberId());

        assertEquals(publicKey, registration.publicKey());
        assertEquals(publicKey, registration.publicKey());

        assertEquals(proofOfPossession, registration.proofOfPossession());
        assertEquals(proofOfPossession, registration.proofOfPossession());

        assertEquals(registrationSignature, registration.registrationSignature());
        assertEquals(registrationSignature, registration.registrationSignature());

        assertEquals(registrationEpoch, registration.registrationEpoch());
        assertEquals(registrationTime, registration.registrationTime());
    }

    @Test
    @DisplayName("Two registrations with same data are equal")
    void testEquality() {
        // Given two registrations with identical data
        var registration1 = new BLSKeyRegistration(
            memberId,
            publicKey,
            proofOfPossession,
            registrationSignature,
            registrationEpoch,
            registrationTime
        );

        var registration2 = new BLSKeyRegistration(
            memberId,
            publicKey,
            proofOfPossession,
            registrationSignature,
            registrationEpoch,
            registrationTime
        );

        // Then they are equal
        assertEquals(registration1, registration2);
        assertEquals(registration1.hashCode(), registration2.hashCode());
    }

    @Test
    @DisplayName("Different registrations are not equal")
    void testInequality() {
        // Given two registrations with different epochs
        var registration1 = new BLSKeyRegistration(
            memberId,
            publicKey,
            proofOfPossession,
            registrationSignature,
            100L,
            registrationTime
        );

        var registration2 = new BLSKeyRegistration(
            memberId,
            publicKey,
            proofOfPossession,
            registrationSignature,
            200L,
            registrationTime
        );

        // Then they are not equal
        assertNotEquals(registration1, registration2);
    }
}
