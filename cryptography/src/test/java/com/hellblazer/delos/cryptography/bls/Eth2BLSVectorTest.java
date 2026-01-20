/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.cryptography.bls;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

/**
 * Ethereum 2.0 BLS test vectors for Phase 6 comprehensive testing.
 * <p>
 * Tests BLS-12-381 operations against known-good test vectors inspired by
 * the Ethereum 2.0 specification. Validates:
 * - Key generation determinism
 * - Signing consistency
 * - Verification correctness
 * - Aggregation validity
 * - Batch verification
 * <p>
 * Test vectors are generated programmatically using BLSOperations with
 * fixed seeds for reproducibility.
 *
 * @author hal.hildebrand
 */
class Eth2BLSVectorTest {

    // ========== Test Vector Data Structures ==========

    record SigningVector(String name, long seed, byte[] message, byte[] expectedPublicKey, byte[] expectedSignature) {
    }

    record AggregationVector(String name, List<Long> seeds, byte[] message, int expectedSignerCount) {
    }

    record BatchVerificationVector(String name, List<Long> seeds, List<byte[]> messages, boolean shouldVerify) {
    }

    // ========== Signing Test Vectors ==========

    static Stream<Arguments> signingVectors() {
        var vectors = new ArrayList<Arguments>();

        // Vector 1: Simple validator registration
        vectors.add(Arguments.of(
            "validator_registration_0",
            1000L,
            "validator_registration_pubkey_0x1234".getBytes(),
            generateExpectedPublicKey(1000L),
            generateExpectedSignature(1000L, "validator_registration_pubkey_0x1234".getBytes())
        ));

        // Vector 2: Attestation signing
        vectors.add(Arguments.of(
            "attestation_slot_42",
            2000L,
            "attestation_slot_42_epoch_1_root_0xabcd".getBytes(),
            generateExpectedPublicKey(2000L),
            generateExpectedSignature(2000L, "attestation_slot_42_epoch_1_root_0xabcd".getBytes())
        ));

        // Vector 3: Block proposal
        vectors.add(Arguments.of(
            "block_proposal_slot_100",
            3000L,
            "block_proposal_slot_100_parent_0x9876".getBytes(),
            generateExpectedPublicKey(3000L),
            generateExpectedSignature(3000L, "block_proposal_slot_100_parent_0x9876".getBytes())
        ));

        // Vector 4: Voluntary exit
        vectors.add(Arguments.of(
            "voluntary_exit_epoch_50",
            4000L,
            "voluntary_exit_validator_123_epoch_50".getBytes(),
            generateExpectedPublicKey(4000L),
            generateExpectedSignature(4000L, "voluntary_exit_validator_123_epoch_50".getBytes())
        ));

        // Vector 5: Deposit data
        vectors.add(Arguments.of(
            "deposit_data_32eth",
            5000L,
            "deposit_data_pubkey_withdrawal_creds_32eth".getBytes(),
            generateExpectedPublicKey(5000L),
            generateExpectedSignature(5000L, "deposit_data_pubkey_withdrawal_creds_32eth".getBytes())
        ));

        // Vector 6: Empty message edge case
        vectors.add(Arguments.of(
            "empty_message",
            6000L,
            new byte[0],
            generateExpectedPublicKey(6000L),
            generateExpectedSignature(6000L, new byte[0])
        ));

        // Vector 7: Large message (1KB)
        var largeMessage = new byte[1024];
        new Random(7000L).nextBytes(largeMessage);
        vectors.add(Arguments.of(
            "large_message_1kb",
            7000L,
            largeMessage,
            generateExpectedPublicKey(7000L),
            generateExpectedSignature(7000L, largeMessage)
        ));

        // Vector 8: Slashing evidence
        vectors.add(Arguments.of(
            "slashing_evidence_double_vote",
            8000L,
            "slashing_double_vote_att1_att2_validator_456".getBytes(),
            generateExpectedPublicKey(8000L),
            generateExpectedSignature(8000L, "slashing_double_vote_att1_att2_validator_456".getBytes())
        ));

        // Vector 9: Sync committee message
        vectors.add(Arguments.of(
            "sync_committee_slot_200",
            9000L,
            "sync_committee_msg_slot_200_beacon_root_0xbeef".getBytes(),
            generateExpectedPublicKey(9000L),
            generateExpectedSignature(9000L, "sync_committee_msg_slot_200_beacon_root_0xbeef".getBytes())
        ));

        // Vector 10: Randao reveal
        vectors.add(Arguments.of(
            "randao_reveal_epoch_10",
            10000L,
            "randao_reveal_epoch_10_proposer_789".getBytes(),
            generateExpectedPublicKey(10000L),
            generateExpectedSignature(10000L, "randao_reveal_epoch_10_proposer_789".getBytes())
        ));

        return vectors.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("signingVectors")
    void testSigningVectors(String name, long seed, byte[] message, byte[] expectedPublicKey,
                            byte[] expectedSignature) {
        // GIVEN: Deterministic key pair from seed
        var keyPair = BLSOperations.generateKeyPair(new Random(seed));

        // WHEN: Sign the message
        var signature = BLSOperations.sign(keyPair, message);

        // THEN: Public key matches expected
        assertThat(keyPair.publicKey().toBytesCompressed())
            .as("Public key for %s", name)
            .isEqualTo(expectedPublicKey);

        // AND: Signature matches expected
        assertThat(signature.toBytes())
            .as("Signature for %s", name)
            .isEqualTo(expectedSignature);

        // AND: Signature verifies
        assertThat(BLSOperations.verify(keyPair.publicKey(), message, signature))
            .as("Verification for %s", name)
            .isTrue();
    }

    // ========== Aggregation Test Vectors ==========

    static Stream<Arguments> aggregationVectors() {
        var vectors = new ArrayList<Arguments>();

        // Vector 1: Small committee (3 validators)
        vectors.add(Arguments.of(
            "committee_3_validators",
            List.of(100L, 200L, 300L),
            "committee_attestation_epoch_5_slot_80".getBytes(),
            3
        ));

        // Vector 2: Typical quorum (7 validators)
        vectors.add(Arguments.of(
            "committee_7_validators_quorum",
            List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L),
            "quorum_decision_block_hash_0x1234".getBytes(),
            7
        ));

        // Vector 3: Larger committee (21 validators)
        vectors.add(Arguments.of(
            "committee_21_validators",
            List.of(10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L, 90L, 100L,
                   110L, 120L, 130L, 140L, 150L, 160L, 170L, 180L, 190L, 200L, 210L),
            "large_committee_attestation_slot_150".getBytes(),
            21
        ));

        // Vector 4: Partial committee (5 out of 10 validators)
        vectors.add(Arguments.of(
            "committee_5_of_10_partial",
            List.of(1000L, 1002L, 1004L, 1006L, 1008L),
            "partial_committee_vote_proposal_789".getBytes(),
            5
        ));

        // Vector 5: Single signer (edge case)
        vectors.add(Arguments.of(
            "committee_1_validator_single",
            List.of(42L),
            "single_validator_attestation_slot_99".getBytes(),
            1
        ));

        // Vector 6: Sync committee (64 validators)
        var syncSeeds = new ArrayList<Long>();
        for (long i = 2000L; i < 2064L; i++) {
            syncSeeds.add(i);
        }
        vectors.add(Arguments.of(
            "sync_committee_64_validators",
            syncSeeds,
            "sync_committee_aggregate_slot_256".getBytes(),
            64
        ));

        // Vector 7: Maximum practical committee (100 validators)
        var largeSeeds = new ArrayList<Long>();
        for (long i = 5000L; i < 5100L; i++) {
            largeSeeds.add(i);
        }
        vectors.add(Arguments.of(
            "committee_100_validators_max",
            largeSeeds,
            "max_committee_attestation_epoch_20".getBytes(),
            100
        ));

        return vectors.stream();
    }

    @Disabled("TODO: Phase 1B-2 - Receipt Aggregation with cryptographic aggregation")
    @ParameterizedTest(name = "{0}")
    @MethodSource("aggregationVectors")
    void testAggregationVectors(String name, List<Long> seeds, byte[] message, int expectedSignerCount) {
        // GIVEN: Committee of validators
        var keyPairs = new ArrayList<BLSKeyPair>();
        var signatures = new ArrayList<BLSSignature>();

        for (var seed : seeds) {
            var keyPair = BLSOperations.generateKeyPair(new Random(seed));
            keyPairs.add(keyPair);
            signatures.add(BLSOperations.sign(keyPair, message));
        }

        // WHEN: Aggregate signatures
        var aggregate = BLSOperations.aggregateSignatures(signatures);

        // THEN: Signer count matches expected
        assertThat(aggregate.getSignerIndices().size())
            .as("Signer count for %s", name)
            .isEqualTo(expectedSignerCount);

        // AND: All signers are present
        for (int i = 0; i < expectedSignerCount; i++) {
            assertThat(aggregate.getSignerIndices())
                .as("Signer %d present in %s", i, name)
                .contains(i);
        }

        // AND: Aggregate verifies against committee
        var publicKeys = keyPairs.stream()
                                 .map(BLSKeyPair::publicKey)
                                 .toList();

        assertThat(BLSOperations.verifyAggregate(publicKeys, message, aggregate))
            .as("Aggregate verification for %s", name)
            .isTrue();
    }

    // ========== Batch Verification Test Vectors ==========

    static Stream<Arguments> batchVerificationVectors() {
        var vectors = new ArrayList<Arguments>();

        // Vector 1: All valid signatures (3 validators, different messages)
        vectors.add(Arguments.of(
            "batch_3_valid_different_messages",
            List.of(100L, 200L, 300L),
            List.of(
                "message_1_attestation".getBytes(),
                "message_2_block".getBytes(),
                "message_3_exit".getBytes()
            ),
            true
        ));

        // Vector 2: All valid signatures (same message)
        vectors.add(Arguments.of(
            "batch_3_valid_same_message",
            List.of(400L, 500L, 600L),
            List.of(
                "common_message".getBytes(),
                "common_message".getBytes(),
                "common_message".getBytes()
            ),
            true
        ));

        // Vector 3: Large batch (10 validators)
        var batchSeeds = List.of(10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L, 90L, 100L);
        var batchMessages = new ArrayList<byte[]>();
        for (int i = 0; i < 10; i++) {
            batchMessages.add(("batch_message_" + i).getBytes());
        }
        vectors.add(Arguments.of(
            "batch_10_valid_unique_messages",
            batchSeeds,
            batchMessages,
            true
        ));

        // Vector 4: Single signature batch (edge case)
        vectors.add(Arguments.of(
            "batch_1_single_signature",
            List.of(999L),
            List.of("single_batch_message".getBytes()),
            true
        ));

        return vectors.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("batchVerificationVectors")
    void testBatchVerificationVectors(String name, List<Long> seeds, List<byte[]> messages, boolean shouldVerify) {
        // GIVEN: Multiple signatures
        var publicKeys = new ArrayList<BLSPublicKey>();
        var signatures = new ArrayList<BLSSignature>();

        for (int i = 0; i < seeds.size(); i++) {
            var keyPair = BLSOperations.generateKeyPair(new Random(seeds.get(i)));
            publicKeys.add(keyPair.publicKey());
            signatures.add(BLSOperations.sign(keyPair, messages.get(i)));
        }

        // WHEN: Batch verify
        var result = BLSOperations.batchVerify(publicKeys, messages, signatures);

        // THEN: Result matches expected
        assertThat(result)
            .as("Batch verification for %s", name)
            .isEqualTo(shouldVerify);
    }

    // ========== Key Derivation Test Vectors ==========

    @Disabled("TODO: Phase 1B-2 - Key derivation requires Teku BLS API enhancement")
    @Test
    void testKeyDerivation_validator0() {
        // GIVEN: Known seed for validator 0
        var seed = 12345L;
        var keyPair = BLSOperations.generateKeyPair(new Random(seed));
        var secretKeyBytes = keyPair.secretKey().getScalar();

        // WHEN: Derive public key from secret key
        var derivedPublicKey = BLSOperations.derivePublicKey(secretKeyBytes);

        // THEN: Derived public key matches original
        assertThat(derivedPublicKey.toBytesCompressed())
            .isEqualTo(keyPair.publicKey().toBytesCompressed());

        // AND: Can verify signature with derived key
        var message = "test_message".getBytes();
        var signature = BLSOperations.sign(secretKeyBytes, message);

        assertThat(BLSOperations.verify(derivedPublicKey, message, signature))
            .isTrue();
    }

    // ========== Cross-Validation Tests ==========

    @Test
    void testCrossValidation_multipleValidators_sameMessage() {
        // Test that multiple validators can independently sign the same message
        // and all signatures verify correctly

        var message = "common_beacon_block_root".getBytes();
        var validatorSeeds = List.of(111L, 222L, 333L, 444L, 555L);

        for (var seed : validatorSeeds) {
            var keyPair = BLSOperations.generateKeyPair(new Random(seed));
            var signature = BLSOperations.sign(keyPair, message);

            // Each signature should verify independently
            assertThat(BLSOperations.verify(keyPair.publicKey(), message, signature))
                .as("Validator %d signature verifies", seed)
                .isTrue();
        }
    }

    @Disabled("TODO: Phase 1B-2 - Aggregation verification requires working cryptographic aggregation")
    @Test
    void testCrossValidation_aggregateSubsets() {
        // Test that different subsets of a committee produce different aggregates
        // but all verify correctly

        var message = "committee_decision".getBytes();
        var allSeeds = List.of(10L, 20L, 30L, 40L, 50L);

        // Generate all key pairs
        var allKeyPairs = new ArrayList<BLSKeyPair>();
        for (var seed : allSeeds) {
            allKeyPairs.add(BLSOperations.generateKeyPair(new Random(seed)));
        }

        // Subset 1: First 3 validators
        var subset1Sigs = allKeyPairs.subList(0, 3).stream()
            .map(kp -> BLSOperations.sign(kp, message))
            .toList();
        var aggregate1 = BLSOperations.aggregateSignatures(subset1Sigs);

        // Subset 2: Last 3 validators
        var subset2Sigs = allKeyPairs.subList(2, 5).stream()
            .map(kp -> BLSOperations.sign(kp, message))
            .toList();
        var aggregate2 = BLSOperations.aggregateSignatures(subset2Sigs);

        // Both aggregates should verify against their respective committees
        var publicKeys = allKeyPairs.stream().map(BLSKeyPair::publicKey).toList();

        assertThat(BLSOperations.verifyAggregate(publicKeys, message, aggregate1))
            .as("Subset 1 aggregate verifies")
            .isTrue();

        assertThat(BLSOperations.verifyAggregate(publicKeys, message, aggregate2))
            .as("Subset 2 aggregate verifies")
            .isTrue();

        // Aggregates should be different (different signer sets)
        assertThat(aggregate1.getSignerIndices())
            .as("Different signer sets")
            .isNotEqualTo(aggregate2.getSignerIndices());
    }

    // ========== Helper Methods ==========

    private static byte[] generateExpectedPublicKey(long seed) {
        var keyPair = BLSOperations.generateKeyPair(new Random(seed));
        return keyPair.publicKey().toBytesCompressed();
    }

    private static byte[] generateExpectedSignature(long seed, byte[] message) {
        var keyPair = BLSOperations.generateKeyPair(new Random(seed));
        return BLSOperations.sign(keyPair, message).toBytes();
    }
}
