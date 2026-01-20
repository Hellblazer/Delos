/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.committee;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.*;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.committee.ProofOfPossessionValidator.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for KeyRegistrationService with real cryptographic operations.
 * Validates full registration workflow with actual BLS operations.
 *
 * @author hal.hildebrand
 */
class KeyRegistrationServiceIntegrationTest {

    private CommitteeBLSKeyStore keyStore;
    private ProofOfPossessionValidator popValidator;
    private BLSProvider blsProvider;
    private KeyRegistrationService service;
    private Random random;

    @BeforeEach
    void setUp() {
        blsProvider = BLSProvider.getDefault();
        keyStore = new InMemoryCommitteeBLSKeyStore();
        popValidator = new ProofOfPossessionValidator(blsProvider);
        random = new Random(42);

        service = new KeyRegistrationService(keyStore, popValidator);
    }

    private Identifier createMemberId(long seed) {
        var digest = DigestAlgorithm.DEFAULT.digest("member-" + seed);
        return new SelfAddressingIdentifier(digest);
    }

    @Test
    void testFullRegistrationWorkflow_WithRealCryptography() {
        // Arrange - Generate real BLS key pair
        var keyPair = BLSOperations.generateKeyPair(random);
        var memberId = createMemberId(1234L);

        // Create registration with proper message signing
        var publicKey = keyPair.publicKey();
        var pop = publicKey.proofOfPossession();
        var memberMessage = memberId.getDigest(DigestAlgorithm.DEFAULT).getBytes();
        var registrationSignature = keyPair.sign(memberMessage);

        var registration = new BLSKeyRegistration(
            memberId,
            publicKey,
            pop,
            registrationSignature,
            1L,
            Instant.now()
        );

        // Act - Register the key
        var result = service.registerKey(registration);

        // Assert - Validation should succeed
        assertThat(result).isInstanceOf(ValidationResult.Valid.class);

        // Assert - Key should be retrievable
        var retrievedKey = service.getPublicKey(memberId);
        assertThat(retrievedKey).isPresent().contains(publicKey);

        // Assert - HasKey should return true
        assertThat(service.hasKey(memberId)).isTrue();

        // Assert - GetPublicKeys should contain the key
        var allKeys = service.getPublicKeys();
        assertThat(allKeys).containsEntry(memberId, publicKey);
    }

    @Test
    void testMultipleKeyRegistrations_AllSucceed() {
        // Arrange - Generate multiple key pairs
        var memberCount = 5;
        var registrations = new ArrayList<BLSKeyRegistration>();

        for (int i = 0; i < memberCount; i++) {
            var keyPair = BLSOperations.generateKeyPair(random);
            var memberId = createMemberId(1000L + i);

            var publicKey = keyPair.publicKey();
            var pop = publicKey.proofOfPossession();
            var memberMessage = memberId.getDigest(DigestAlgorithm.DEFAULT).getBytes();
            var registrationSignature = keyPair.sign(memberMessage);

            var registration = new BLSKeyRegistration(
                memberId,
                publicKey,
                pop,
                registrationSignature,
                1L,
                Instant.now()
            );
            registrations.add(registration);
        }

        // Act - Register all keys
        for (var registration : registrations) {
            var result = service.registerKey(registration);
            assertThat(result).isInstanceOf(ValidationResult.Valid.class);
        }

        // Assert - All keys should be retrievable
        var allKeys = service.getPublicKeys();
        assertThat(allKeys).hasSize(memberCount);

        for (var registration : registrations) {
            assertThat(service.hasKey(registration.memberId())).isTrue();
            assertThat(service.getPublicKey(registration.memberId()))
                .isPresent()
                .contains(registration.publicKey());
        }
    }

    @Test
    void testConcurrentRegistrations_AllSucceed() throws InterruptedException {
        // Arrange - Generate registrations
        var memberCount = 10;
        var registrations = new ArrayList<BLSKeyRegistration>();

        for (int i = 0; i < memberCount; i++) {
            var keyPair = BLSOperations.generateKeyPair(random);
            var memberId = createMemberId(2000L + i);

            var publicKey = keyPair.publicKey();
            var pop = publicKey.proofOfPossession();
            var memberMessage = memberId.getDigest(DigestAlgorithm.DEFAULT).getBytes();
            var registrationSignature = keyPair.sign(memberMessage);

            var registration = new BLSKeyRegistration(
                memberId,
                publicKey,
                pop,
                registrationSignature,
                1L,
                Instant.now()
            );
            registrations.add(registration);
        }

        // Act - Register concurrently using virtual threads
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var futures = new ArrayList<Future<ValidationResult>>();

        for (var registration : registrations) {
            var future = executor.submit(() -> service.registerKey(registration));
            futures.add(future);
        }

        // Wait for all registrations to complete
        var results = new ArrayList<ValidationResult>();
        for (var future : futures) {
            try {
                results.add(future.get(5, TimeUnit.SECONDS));
            } catch (ExecutionException | TimeoutException e) {
                fail("Registration failed with exception", e);
            }
        }

        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        // Assert - All registrations should succeed
        assertThat(results).hasSize(memberCount);
        for (var result : results) {
            assertThat(result).isInstanceOf(ValidationResult.Valid.class);
        }

        // Assert - All keys should be retrievable
        var allKeys = service.getPublicKeys();
        assertThat(allKeys).hasSize(memberCount);

        for (var registration : registrations) {
            assertThat(service.hasKey(registration.memberId())).isTrue();
        }
    }

    @Test
    void testInvalidPoP_RegistrationFails() {
        // Arrange - Create registration with mismatched PoP
        var keyPair1 = BLSOperations.generateKeyPair(random);
        var keyPair2 = BLSOperations.generateKeyPair(random); // Different key!
        var memberId = createMemberId(3000L);

        var publicKey1 = keyPair1.publicKey();
        var wrongPoP = keyPair2.publicKey().proofOfPossession(); // Wrong PoP!
        var memberMessage = memberId.getDigest(DigestAlgorithm.DEFAULT).getBytes();
        var registrationSignature = keyPair1.sign(memberMessage);

        var registration = new BLSKeyRegistration(
            memberId,
            publicKey1,
            wrongPoP, // Wrong PoP!
            registrationSignature,
            1L,
            Instant.now()
        );

        // Act
        var result = service.registerKey(registration);

        // Assert - Should fail validation
        assertThat(result).isInstanceOf(ValidationResult.Invalid.class);
        var invalid = (ValidationResult.Invalid) result;
        assertThat(invalid.reason()).contains("Proof of Possession");

        // Assert - Key should NOT be stored
        assertThat(service.hasKey(memberId)).isFalse();
        assertThat(service.getPublicKey(memberId)).isEmpty();
    }
}
