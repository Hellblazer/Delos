/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.delos.witness;

import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.BLSPublicKey;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.witness.committee.BLSKeyRegistration;
import com.hellblazer.delos.witness.committee.CommitteeBLSKeyStore;
import com.hellblazer.delos.witness.committee.InMemoryCommitteeBLSKeyStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for WitnessContext integration with CommitteeBLSKeyStore.
 * <p>
 * Verifies Phase 1B-3-A-4 implementation requirements:
 * - getCommitteeBLSKeys() returns store from constructor
 * - Backward-compatible constructor defaults to InMemoryCommitteeBLSKeyStore
 * - Multiple instances have separate stores
 * - Null store detection
 *
 * @author hal.hildebrand
 */
@DisplayName("WitnessContext BLS Integration Tests")
class WitnessContextBLSIntegrationTest {

    @Test
    @DisplayName("getCommitteeBLSKeys() returns store from constructor")
    void testGetCommitteeBLSKeys_ReturnsStoreFromConstructor() {
        // Given: A custom CommitteeBLSKeyStore
        var customStore = new InMemoryCommitteeBLSKeyStore(10);
        var context = createWitnessContext(customStore);

        // When: Getting the BLS key store
        var retrievedStore = context.getCommitteeBLSKeys();

        // Then: It should be the exact store we provided
        assertThat(retrievedStore)
            .as("Should return the store passed to constructor")
            .isSameAs(customStore);
    }

    @Test
    @DisplayName("Backward-compatible constructor creates InMemoryCommitteeBLSKeyStore")
    void testBackwardCompatibleConstructor_CreatesInMemoryStore() {
        // Given: A WitnessContext created with old constructor (no explicit store)
        var firefliesContext = mockFirefliesContext();
        var parameters = WitnessParameters.newBuilder()
            .k(4)              // Minimum 4 witnesses for BFT (N >= 3f+1 with f=1)
            .threshold(3)      // BFT threshold (number of signatures required)
            .epoch(1L)
            .drainPeriod(java.time.Duration.ofSeconds(10))
            .build();
        var digestAlgorithm = DigestAlgorithm.DEFAULT;

        // When: Creating context with backward-compatible constructor
        var context = new WitnessContext(firefliesContext, parameters, digestAlgorithm);

        // Then: It should have a non-null InMemoryCommitteeBLSKeyStore
        var store = context.getCommitteeBLSKeys();
        assertThat(store)
            .as("Backward-compatible constructor should create default store")
            .isNotNull()
            .isInstanceOf(InMemoryCommitteeBLSKeyStore.class);
    }

    @Test
    @DisplayName("Backward-compatible constructor defaults work correctly")
    void testBackwardCompatibleConstructor_DefaultsWorkCorrectly() {
        // Given: A WitnessContext created with old constructor
        var context = createWitnessContextWithDefaults();

        // When: Using the store to register a key
        var store = context.getCommitteeBLSKeys();
        var registration = createMockRegistration();
        var registered = store.registerKey(registration);

        // Then: Store operations should work correctly
        assertThat(registered)
            .as("Should successfully register key in default store")
            .isTrue();
        assertThat(store.keyCount())
            .as("Store should contain the registered key")
            .isEqualTo(1);
        assertThat(store.hasKey(registration.memberId()))
            .as("Store should have key for registered member")
            .isTrue();
    }

    @Test
    @DisplayName("Multiple instances have separate stores")
    void testMultipleInstances_HaveSeparateStores() {
        // Given: Two WitnessContext instances
        var context1 = createWitnessContextWithDefaults();
        var context2 = createWitnessContextWithDefaults();

        // When: Getting stores from both contexts
        var store1 = context1.getCommitteeBLSKeys();
        var store2 = context2.getCommitteeBLSKeys();

        // Then: They should be different instances
        assertThat(store1)
            .as("Each context should have its own store instance")
            .isNotSameAs(store2);

        // And: Operations on one should not affect the other
        var registration = createMockRegistration();
        store1.registerKey(registration);

        assertThat(store1.keyCount())
            .as("Store 1 should have 1 key")
            .isEqualTo(1);
        assertThat(store2.keyCount())
            .as("Store 2 should remain empty")
            .isEqualTo(0);
    }

    @Test
    @DisplayName("Store accessible after context creation")
    void testStoreAccessible_AfterContextCreation() {
        // Given: A WitnessContext
        var context = createWitnessContextWithDefaults();

        // When: Immediately accessing the store
        var store = context.getCommitteeBLSKeys();

        // Then: It should be fully functional
        assertThat(store)
            .as("Store should be accessible immediately after context creation")
            .isNotNull();
        assertThat(store.keyCount())
            .as("New store should be empty")
            .isEqualTo(0);
        assertThat(store.registeredMembers())
            .as("New store should have no registered members")
            .isEmpty();
    }

    @Test
    @DisplayName("Null store throws NPE when constructed with explicit null")
    void testNullStore_ThrowsNPE() {
        // Given: A null store
        CommitteeBLSKeyStore nullStore = null;
        var firefliesContext = mockFirefliesContext();
        var parameters = WitnessParameters.newBuilder()
            .k(4)              // Minimum 4 witnesses for BFT (N >= 3f+1 with f=1)
            .threshold(3)      // BFT threshold (number of signatures required)
            .epoch(1L)
            .drainPeriod(java.time.Duration.ofSeconds(10))
            .build();
        var digestAlgorithm = DigestAlgorithm.DEFAULT;

        // When/Then: Creating context with null store should throw NPE
        assertThatThrownBy(() ->
            new WitnessContext(firefliesContext, parameters, digestAlgorithm, nullStore)
        )
            .as("Constructor should reject null store")
            .isInstanceOf(NullPointerException.class);
    }

    // ============ Helper Methods ============

    /**
     * Create a WitnessContext with custom CommitteeBLSKeyStore.
     */
    private WitnessContext createWitnessContext(CommitteeBLSKeyStore store) {
        var firefliesContext = mockFirefliesContext();
        var parameters = WitnessParameters.newBuilder()
            .k(4)              // Minimum 4 witnesses for BFT (N >= 3f+1 with f=1)
            .threshold(3)      // BFT threshold (number of signatures required)
            .epoch(1L)
            .drainPeriod(java.time.Duration.ofSeconds(10))
            .build();
        var digestAlgorithm = DigestAlgorithm.DEFAULT;
        return new WitnessContext(firefliesContext, parameters, digestAlgorithm, store);
    }

    /**
     * Create a WitnessContext using backward-compatible constructor (no explicit store).
     */
    private WitnessContext createWitnessContextWithDefaults() {
        var firefliesContext = mockFirefliesContext();
        var parameters = WitnessParameters.newBuilder()
            .k(4)              // Minimum 4 witnesses for BFT (N >= 3f+1 with f=1)
            .threshold(3)      // BFT threshold (number of signatures required)
            .epoch(1L)
            .drainPeriod(java.time.Duration.ofSeconds(10))
            .build();
        var digestAlgorithm = DigestAlgorithm.DEFAULT;
        return new WitnessContext(firefliesContext, parameters, digestAlgorithm);
    }

    /**
     * Mock a Fireflies context with minimal required behavior.
     */
    @SuppressWarnings("unchecked")
    private Context<?> mockFirefliesContext() {
        var context = mock(Context.class);
        when(context.allMembers()).thenReturn(Stream.empty());
        return context;
    }

    /**
     * Create a real BLSKeyRegistration for testing.
     * Uses real BLS cryptography since BLSPublicKey and BLSSignature are final classes.
     */
    private BLSKeyRegistration createMockRegistration() {
        var memberId = mock(Identifier.class);
        var blsKeyPair = com.hellblazer.delos.cryptography.bls.BLSOperations.generateKeyPair(
            new java.security.SecureRandom()
        );
        var publicKey = blsKeyPair.publicKey();
        var proofOfPossession = publicKey.proofOfPossession();  // PoP is embedded in public key

        // Create real signature since BLSSignature is final (sign dummy data)
        var registrationSignature = blsKeyPair.sign("test-registration".getBytes());

        return new BLSKeyRegistration(
            memberId,
            publicKey,
            proofOfPossession,
            registrationSignature,
            0L,
            java.time.Instant.now()
        );
    }
}
