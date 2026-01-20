/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.committee;

import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.cryptography.bls.BLSKeyPair;
import com.hellblazer.delos.cryptography.bls.BLSProvider;
import com.hellblazer.delos.cryptography.bls.BLSTestFixtures;
import com.hellblazer.delos.stereotomy.identifier.BasicIdentifier;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test suite for CommitteeBLSKeyStore interface and InMemoryCommitteeBLSKeyStore implementation.
 * Validates thread-safe key storage operations for committee member BLS keys.
 *
 * @author hal.hildebrand
 */
class CommitteeBLSKeyStoreTest {

    private CommitteeBLSKeyStore keyStore;
    private BLSProvider blsProvider;
    private BLSKeyPair keyPair1;
    private BLSKeyPair keyPair2;
    private BLSKeyPair keyPair3;
    private Identifier member1;
    private Identifier member2;
    private Identifier member3;
    private SecureRandom entropy;

    @BeforeEach
    void setUp() {
        keyStore = new InMemoryCommitteeBLSKeyStore();
        blsProvider = BLSProvider.getDefault();
        entropy = BLSTestFixtures.deterministicRandom(0x4242L);

        // Generate deterministic BLS test keys (our wrapper type)
        var random1 = BLSTestFixtures.deterministicRandom(0x1234L);
        var random2 = BLSTestFixtures.deterministicRandom(0x5678L);
        var random3 = BLSTestFixtures.deterministicRandom(0x9ABCL);

        keyPair1 = BLSKeyPair.generate(random1, blsProvider);
        keyPair2 = BLSKeyPair.generate(random2, blsProvider);
        keyPair3 = BLSKeyPair.generate(random3, blsProvider);

        // Create test identifiers using ED25519 keys
        var idRandom = BLSTestFixtures.deterministicRandom(0xAAAAL);
        var idKey1 = SignatureAlgorithm.ED_25519.generateKeyPair(idRandom);
        var idKey2 = SignatureAlgorithm.ED_25519.generateKeyPair(idRandom);
        var idKey3 = SignatureAlgorithm.ED_25519.generateKeyPair(idRandom);

        member1 = new BasicIdentifier(idKey1.getPublic());
        member2 = new BasicIdentifier(idKey2.getPublic());
        member3 = new BasicIdentifier(idKey3.getPublic());
    }

    @Test
    void testRegisterKey_Success() {
        // Given: A valid key registration
        var registration = createRegistration(member1, keyPair1, 1);

        // When: Registering the key
        var result = keyStore.registerKey(registration);

        // Then: Registration succeeds
        assertThat(result).isTrue();
        assertThat(keyStore.hasKey(member1)).isTrue();
        assertThat(keyStore.getPublicKey(member1)).isPresent()
                                                    .hasValue(registration.publicKey());
        assertThat(keyStore.keyCount()).isEqualTo(1);
    }

    @Test
    void testRegisterKey_DuplicateRejected() {
        // Given: A key already registered
        var registration1 = createRegistration(member1, keyPair1, 1);
        keyStore.registerKey(registration1);

        // When: Attempting to register another key for the same member
        var registration2 = createRegistration(member1, keyPair2, 2);
        var result = keyStore.registerKey(registration2);

        // Then: Registration is rejected, original key remains
        assertThat(result).isFalse();
        assertThat(keyStore.getPublicKey(member1)).isPresent()
                                                    .hasValue(registration1.publicKey());
        assertThat(keyStore.keyCount()).isEqualTo(1);
    }

    @Test
    void testGetPublicKey_Found() {
        // Given: A registered key
        var registration = createRegistration(member1, keyPair1, 1);
        keyStore.registerKey(registration);

        // When: Getting the public key
        var result = keyStore.getPublicKey(member1);

        // Then: Key is found
        assertThat(result).isPresent()
                          .hasValue(registration.publicKey());
    }

    @Test
    void testGetPublicKey_NotFound() {
        // Given: No key registered for member
        // When: Getting the public key
        var result = keyStore.getPublicKey(member1);

        // Then: Key is not found
        assertThat(result).isEmpty();
    }

    @Test
    void testGetPublicKeys_FiltersUnregistered() {
        // Given: Keys registered for member1 and member2
        var registration1 = createRegistration(member1, keyPair1, 1);
        var registration2 = createRegistration(member2, keyPair2, 1);
        keyStore.registerKey(registration1);
        keyStore.registerKey(registration2);

        // When: Requesting keys for member1, member2, and unregistered member3
        var memberIds = Set.of(member1, member2, member3);
        var result = keyStore.getPublicKeys(memberIds);

        // Then: Returns only registered keys, filters out member3
        assertThat(result).hasSize(2)
                          .containsExactlyInAnyOrder(registration1.publicKey(), registration2.publicKey());
    }

    @Test
    void testHasKey_True() {
        // Given: A registered key
        var registration = createRegistration(member1, keyPair1, 1);
        keyStore.registerKey(registration);

        // When: Checking if key exists
        var result = keyStore.hasKey(member1);

        // Then: Returns true
        assertThat(result).isTrue();
    }

    @Test
    void testHasKey_False() {
        // Given: No key registered
        // When: Checking if key exists
        var result = keyStore.hasKey(member1);

        // Then: Returns false
        assertThat(result).isFalse();
    }

    @Test
    void testRemoveKey_Success() {
        // Given: A registered key
        var registration = createRegistration(member1, keyPair1, 1);
        keyStore.registerKey(registration);

        // When: Removing the key
        var result = keyStore.removeKey(member1);

        // Then: Removal succeeds, key no longer exists
        assertThat(result).isTrue();
        assertThat(keyStore.hasKey(member1)).isFalse();
        assertThat(keyStore.getPublicKey(member1)).isEmpty();
        assertThat(keyStore.keyCount()).isEqualTo(0);
    }

    @Test
    void testRemoveKey_NotFound() {
        // Given: No key registered
        // When: Attempting to remove non-existent key
        var result = keyStore.removeKey(member1);

        // Then: Returns false
        assertThat(result).isFalse();
    }

    @Test
    void testKeyCount_Accurate() {
        // Given: Empty store
        assertThat(keyStore.keyCount()).isEqualTo(0);

        // When: Adding keys
        keyStore.registerKey(createRegistration(member1, keyPair1, 1));
        assertThat(keyStore.keyCount()).isEqualTo(1);

        keyStore.registerKey(createRegistration(member2, keyPair2, 1));
        assertThat(keyStore.keyCount()).isEqualTo(2);

        keyStore.registerKey(createRegistration(member3, keyPair3, 1));
        assertThat(keyStore.keyCount()).isEqualTo(3);

        // When: Removing a key
        keyStore.removeKey(member2);

        // Then: Count decreases
        assertThat(keyStore.keyCount()).isEqualTo(2);
    }

    @Test
    void testRegisteredMembers_ReturnsAll() {
        // Given: Multiple registered keys
        keyStore.registerKey(createRegistration(member1, keyPair1, 1));
        keyStore.registerKey(createRegistration(member2, keyPair2, 1));
        keyStore.registerKey(createRegistration(member3, keyPair3, 1));

        // When: Getting registered members
        var result = keyStore.registeredMembers();

        // Then: Returns all member IDs
        assertThat(result).hasSize(3)
                          .containsExactlyInAnyOrder(member1, member2, member3);
    }

    @Test
    void testConcurrentRegistration_ThreadSafe() throws InterruptedException {
        // Given: 100 concurrent threads attempting to register keys
        var threadCount = 100;
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var latch = new CountDownLatch(threadCount);
        var successfulRegistrations = new ArrayList<Boolean>(threadCount);

        try {
            // When: Multiple threads register keys concurrently
            for (int i = 0; i < threadCount; i++) {
                final var idRandom = BLSTestFixtures.deterministicRandom(0x2000L + i);
                final var idKey = SignatureAlgorithm.ED_25519.generateKeyPair(idRandom);
                final var memberId = new BasicIdentifier(idKey.getPublic());

                final var blsRandom = BLSTestFixtures.deterministicRandom(0x1000L + i);
                final var keyPair = BLSKeyPair.generate(blsRandom, blsProvider);

                executor.submit(() -> {
                    try {
                        var registration = createRegistration(memberId, keyPair, 1);
                        var result = keyStore.registerKey(registration);
                        synchronized (successfulRegistrations) {
                            successfulRegistrations.add(result);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            // Wait for all threads to complete
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();

            // Then: All registrations succeed without corruption
            assertThat(successfulRegistrations).hasSize(threadCount)
                                                .allMatch(result -> result);
            assertThat(keyStore.keyCount()).isEqualTo(threadCount);
            assertThat(keyStore.registeredMembers()).hasSize(threadCount);
        } finally {
            executor.close();
        }
    }

    // Helper method to create test registrations
    private BLSKeyRegistration createRegistration(Identifier memberId, BLSKeyPair keyPair, long epoch) {
        var publicKey = keyPair.publicKey();
        var pop = publicKey.proofOfPossession();
        // Sign the member ID as the registration signature
        var signature = keyPair.sign(memberId.toIdent().toByteArray());

        return new BLSKeyRegistration(
            memberId,
            publicKey,
            pop,
            signature,
            epoch,
            Instant.now()
        );
    }
}
