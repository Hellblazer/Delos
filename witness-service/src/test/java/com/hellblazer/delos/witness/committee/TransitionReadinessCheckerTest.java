/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.delos.witness.committee;

import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.cryptography.bls.BLSKeyPair;
import com.hellblazer.delos.cryptography.bls.BLSProvider;
import com.hellblazer.delos.cryptography.bls.BLSTestFixtures;
import com.hellblazer.delos.stereotomy.identifier.BasicIdentifier;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.witness.WitnessParameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

/**
 * TDD-driven unit tests for TransitionReadinessChecker.
 *
 * Tests verify BFT quorum calculations and readiness conditions for
 * transitioning from DUAL to BLS_ONLY genesis phase.
 *
 * @author hal.hildebrand
 */
class TransitionReadinessCheckerTest {

    private BLSProvider blsProvider;

    @BeforeEach
    void setUp() {
        blsProvider = BLSProvider.getDefault();
    }

    // ===================================================================
    // 1. Constructor Validation (2 tests)
    // ===================================================================

    @Test
    void constructor_nullStore_throwsNPE() {
        var parameters = createParameters(4);

        assertThatThrownBy(() -> new TransitionReadinessChecker(null, parameters))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("store");
    }

    @Test
    void constructor_nullParameters_throwsNPE() {
        var store = new InMemoryCommitteeBLSKeyStore();

        assertThatThrownBy(() -> new TransitionReadinessChecker(store, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("parameters");
    }

    // ===================================================================
    // 2. BFT Quorum Calculations (4 tests)
    // ===================================================================

    @Test
    void bftCalculations_k4_f1_requiredQuorum3() {
        var store = new InMemoryCommitteeBLSKeyStore();
        var parameters = createParameters(4);
        var checker = new TransitionReadinessChecker(store, parameters);

        assertThat(checker.getTotalMemberCount()).isEqualTo(4);
        assertThat(checker.getFaultToleranceThreshold()).isEqualTo(1);  // f = (4-1)/3 = 1
        assertThat(checker.getRequiredQuorum()).isEqualTo(3);  // 2f+1 = 2*1+1 = 3
    }

    @Test
    void bftCalculations_k7_f2_requiredQuorum5() {
        var store = new InMemoryCommitteeBLSKeyStore();
        var parameters = createParameters(7);
        var checker = new TransitionReadinessChecker(store, parameters);

        assertThat(checker.getTotalMemberCount()).isEqualTo(7);
        assertThat(checker.getFaultToleranceThreshold()).isEqualTo(2);  // f = (7-1)/3 = 2
        assertThat(checker.getRequiredQuorum()).isEqualTo(5);  // 2f+1 = 2*2+1 = 5
    }

    @Test
    void bftCalculations_k10_f3_requiredQuorum7() {
        var store = new InMemoryCommitteeBLSKeyStore();
        var parameters = createParameters(10);
        var checker = new TransitionReadinessChecker(store, parameters);

        assertThat(checker.getTotalMemberCount()).isEqualTo(10);
        assertThat(checker.getFaultToleranceThreshold()).isEqualTo(3);  // f = (10-1)/3 = 3
        assertThat(checker.getRequiredQuorum()).isEqualTo(7);  // 2f+1 = 2*3+1 = 7
    }

    @Test
    void bftCalculations_k4_edgeCase_minimumValid() {
        // Minimum allowed k=4 per WitnessParameters validation
        var store = new InMemoryCommitteeBLSKeyStore();
        var parameters = createParameters(4);
        var checker = new TransitionReadinessChecker(store, parameters);

        assertThat(checker.getTotalMemberCount()).isEqualTo(4);
        assertThat(checker.getFaultToleranceThreshold()).isEqualTo(1);
        assertThat(checker.getRequiredQuorum()).isEqualTo(3);
    }

    // ===================================================================
    // 3. Readiness Checks (5 tests)
    // ===================================================================

    @Test
    void isReadyForTransition_noKeysRegistered_notReady() {
        var store = new InMemoryCommitteeBLSKeyStore();
        var parameters = createParameters(4);  // Requires 3 keys
        var checker = new TransitionReadinessChecker(store, parameters);

        assertThat(checker.getRegisteredMemberCount()).isZero();
        assertThat(checker.isReadyForTransition()).isFalse();
    }

    @Test
    void isReadyForTransition_belowQuorum_notReady() {
        var store = new InMemoryCommitteeBLSKeyStore();
        var parameters = createParameters(4);  // Requires 3 keys
        var checker = new TransitionReadinessChecker(store, parameters);

        // Register 2 keys (need 3)
        registerKey(store, createMemberId(1));
        registerKey(store, createMemberId(2));

        assertThat(checker.getRegisteredMemberCount()).isEqualTo(2);
        assertThat(checker.isReadyForTransition()).isFalse();
    }

    @Test
    void isReadyForTransition_exactlyQuorum_ready() {
        var store = new InMemoryCommitteeBLSKeyStore();
        var parameters = createParameters(4);  // Requires 3 keys
        var checker = new TransitionReadinessChecker(store, parameters);

        // Register exactly 3 keys
        registerKey(store, createMemberId(1));
        registerKey(store, createMemberId(2));
        registerKey(store, createMemberId(3));

        assertThat(checker.getRegisteredMemberCount()).isEqualTo(3);
        assertThat(checker.isReadyForTransition()).isTrue();
    }

    @Test
    void isReadyForTransition_allKeysRegistered_ready() {
        var store = new InMemoryCommitteeBLSKeyStore();
        var parameters = createParameters(4);  // Requires 3 keys
        var checker = new TransitionReadinessChecker(store, parameters);

        // Register all 4 keys (over-satisfied)
        registerKey(store, createMemberId(1));
        registerKey(store, createMemberId(2));
        registerKey(store, createMemberId(3));
        registerKey(store, createMemberId(4));

        assertThat(checker.getRegisteredMemberCount()).isEqualTo(4);
        assertThat(checker.isReadyForTransition()).isTrue();
    }

    @Test
    void isReadyForTransition_emptyStore_notReady() {
        var store = new InMemoryCommitteeBLSKeyStore();
        var parameters = createParameters(7);  // Requires 5 keys
        var checker = new TransitionReadinessChecker(store, parameters);

        assertThat(checker.getRegisteredMemberCount()).isZero();
        assertThat(checker.isReadyForTransition()).isFalse();
    }

    // ===================================================================
    // 4. Count Methods (3 tests)
    // ===================================================================

    @Test
    void getRegisteredMemberCount_matchesStoreSize() {
        var store = new InMemoryCommitteeBLSKeyStore();
        var parameters = createParameters(7);
        var checker = new TransitionReadinessChecker(store, parameters);

        // Register 3 keys
        registerKey(store, createMemberId(1));
        registerKey(store, createMemberId(2));
        registerKey(store, createMemberId(3));

        assertThat(checker.getRegisteredMemberCount()).isEqualTo(3);
        assertThat(store.keyCount()).isEqualTo(3);
    }

    @Test
    void getTotalMemberCount_matchesParameters() {
        var store = new InMemoryCommitteeBLSKeyStore();
        var parameters = createParameters(10);
        var checker = new TransitionReadinessChecker(store, parameters);

        assertThat(checker.getTotalMemberCount()).isEqualTo(10);
    }

    @Test
    void getRequiredQuorum_correctBFTFormula() {
        var store = new InMemoryCommitteeBLSKeyStore();

        // Test multiple committee sizes
        var checker4 = new TransitionReadinessChecker(store, createParameters(4));
        assertThat(checker4.getRequiredQuorum()).isEqualTo(3);  // 2*(4-1)/3 + 1 = 3

        var checker7 = new TransitionReadinessChecker(store, createParameters(7));
        assertThat(checker7.getRequiredQuorum()).isEqualTo(5);  // 2*(7-1)/3 + 1 = 5

        var checker10 = new TransitionReadinessChecker(store, createParameters(10));
        assertThat(checker10.getRequiredQuorum()).isEqualTo(7);  // 2*(10-1)/3 + 1 = 7
    }

    // ===================================================================
    // 5. Edge Cases (2 tests)
    // ===================================================================

    @Test
    void edgeCase_largeCommitteeSize_calculationsAccurate() {
        var store = new InMemoryCommitteeBLSKeyStore();
        var parameters = createParameters(100);
        var checker = new TransitionReadinessChecker(store, parameters);

        assertThat(checker.getTotalMemberCount()).isEqualTo(100);
        assertThat(checker.getFaultToleranceThreshold()).isEqualTo(33);  // f = (100-1)/3 = 33
        assertThat(checker.getRequiredQuorum()).isEqualTo(67);  // 2f+1 = 2*33+1 = 67

        // Verify readiness threshold
        for (int i = 1; i <= 66; i++) {
            registerKey(store, createMemberId(i));
        }
        assertThat(checker.isReadyForTransition()).isFalse();

        registerKey(store, createMemberId(67));
        assertThat(checker.isReadyForTransition()).isTrue();
    }

    @Test
    void edgeCase_dynamicKeyChanges_readinessUpdates() {
        var store = new InMemoryCommitteeBLSKeyStore();
        var parameters = createParameters(4);  // Requires 3
        var checker = new TransitionReadinessChecker(store, parameters);

        // Start not ready
        assertThat(checker.isReadyForTransition()).isFalse();

        // Add keys until ready
        var m1 = createMemberId(1);
        var m2 = createMemberId(2);
        var m3 = createMemberId(3);

        registerKey(store, m1);
        assertThat(checker.isReadyForTransition()).isFalse();

        registerKey(store, m2);
        assertThat(checker.isReadyForTransition()).isFalse();

        registerKey(store, m3);
        assertThat(checker.isReadyForTransition()).isTrue();

        // Remove a key, become not ready again
        store.removeKey(m3);
        assertThat(checker.isReadyForTransition()).isFalse();
    }

    // ===================================================================
    // Helper Methods
    // ===================================================================

    /**
     * Create WitnessParameters with given committee size.
     * Uses standard threshold formula: M = (2*k)/3 + 1
     */
    private WitnessParameters createParameters(int k) {
        int threshold = (2 * k) / 3 + 1;
        return WitnessParameters.newBuilder()
            .k(k)
            .threshold(threshold)
            .epoch(0)
            .drainPeriod(Duration.ofMillis(100))
            .build();
    }

    /**
     * Create a deterministic test member identifier.
     */
    private Identifier createMemberId(int seed) {
        var random = BLSTestFixtures.deterministicRandom(0xAAAAL + seed);
        var idKey = SignatureAlgorithm.ED_25519.generateKeyPair(random);
        return new BasicIdentifier(idKey.getPublic());
    }

    /**
     * Register a BLS key for a member.
     */
    private void registerKey(CommitteeBLSKeyStore store, Identifier memberId) {
        var registration = createRegistration(memberId, memberId.hashCode());
        store.registerKey(registration);
    }

    /**
     * Create a BLS key registration for testing.
     * Uses deterministic key generation for reproducibility.
     */
    private BLSKeyRegistration createRegistration(Identifier memberId, int seed) {
        var random = BLSTestFixtures.deterministicRandom(0x1000L + seed);
        var keyPair = BLSKeyPair.generate(random, blsProvider);

        var publicKey = keyPair.publicKey();
        var pop = publicKey.proofOfPossession();
        var signature = keyPair.sign(memberId.toIdent().toByteArray());

        return new BLSKeyRegistration(
            memberId,
            publicKey,
            pop,
            signature,
            0L,  // epoch
            Instant.now()
        );
    }
}
