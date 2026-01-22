/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.witness.aggregation;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.BLSProvider;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Verification correctness tests for MultiCommitteeAggregate.
 * <p>
 * Phase 1C-2-E: Tests BLS signature verification across multi-committee aggregates:
 * - Valid aggregates pass verification
 * - Invalid aggregates fail verification
 * - Tampered signatures detected
 * - Wrong message rejection
 * - Partial committee participation
 * <p>
 * Uses real BLS cryptography to ensure correctness.
 *
 * @author hal.hildebrand
 */
@DisplayName("MultiCommitteeAggregate - Verification Correctness")
class MultiCommitteeAggregateVerificationTest {

    private static BLSProvider provider;
    private static SecureRandom random;
    private static List<BLSProvider.KeyPair> keyPairs;
    private static EventCoordinates testEvent;

    @BeforeAll
    static void setup() {
        provider = BLSProvider.getDefault();
        random = new SecureRandom();

        // Generate test key pairs
        keyPairs = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            keyPairs.add(provider.generateKeyPair(random));
        }

        // Create test event
        var identifier = new SelfAddressingIdentifier(
            DigestAlgorithm.DEFAULT.digest("test".getBytes())
        );
        testEvent = new EventCoordinates(
            identifier,
            ULong.valueOf(1),
            DigestAlgorithm.DEFAULT.digest("event".getBytes()),
            "test_event"
        );
    }

    @Test
    @DisplayName("Valid single committee aggregate passes verification")
    void validSingleCommitteeAggregatePassesVerification() {
        var aggregator = new CrownAggregator(provider);
        var message = testEvent.getDigest().getBytes();

        // Create signatures from 7 signers
        var signatures = createSignatures(0, 7, message);
        var committeeSignatures = new HashMap<Long, List<BLSSignature>>();
        committeeSignatures.put(100L, signatures);

        var crown = aggregator.createMultiCommitteeAggregate(committeeSignatures, testEvent);

        // Collect all public keys
        var publicKeys = new ArrayList<byte[]>();
        for (int i = 0; i < 7; i++) {
            publicKeys.add(keyPairs.get(i).publicKey());
        }

        // Verify aggregate
        var isValid = provider.verifyAggregate(publicKeys, message, crown.aggregatedSignature().toBytes());

        assertThat(isValid).isTrue();
    }

    @Test
    @DisplayName("Valid multi-committee aggregate passes verification")
    void validMultiCommitteeAggregatePassesVerification() {
        var aggregator = new CrownAggregator(provider);
        var message = testEvent.getDigest().getBytes();

        // Create signatures from 3 committees
        var committee1Sigs = createSignatures(0, 7, message);
        var committee2Sigs = createSignatures(7, 14, message);
        var committee3Sigs = createSignatures(14, 21, message);

        var committeeSignatures = new HashMap<Long, List<BLSSignature>>();
        committeeSignatures.put(100L, committee1Sigs);
        committeeSignatures.put(200L, committee2Sigs);
        committeeSignatures.put(300L, committee3Sigs);

        var crown = aggregator.createMultiCommitteeAggregate(committeeSignatures, testEvent);

        // Collect all public keys from all committees
        var publicKeys = new ArrayList<byte[]>();
        for (int i = 0; i < 21; i++) {
            publicKeys.add(keyPairs.get(i).publicKey());
        }

        // Verify aggregate
        var isValid = provider.verifyAggregate(publicKeys, message, crown.aggregatedSignature().toBytes());

        assertThat(isValid).isTrue();
    }

    @Test
    @DisplayName("Invalid aggregate with wrong message fails verification")
    void invalidAggregateWithWrongMessageFailsVerification() {
        var aggregator = new CrownAggregator(provider);
        var message = testEvent.getDigest().getBytes();

        // Create signatures with correct message
        var signatures = createSignatures(0, 7, message);
        var committeeSignatures = new HashMap<Long, List<BLSSignature>>();
        committeeSignatures.put(100L, signatures);

        var crown = aggregator.createMultiCommitteeAggregate(committeeSignatures, testEvent);

        // Collect public keys
        var publicKeys = new ArrayList<byte[]>();
        for (int i = 0; i < 7; i++) {
            publicKeys.add(keyPairs.get(i).publicKey());
        }

        // Try to verify with WRONG message
        var wrongMessage = DigestAlgorithm.DEFAULT.digest("wrong".getBytes()).getBytes();
        var isValid = provider.verifyAggregate(publicKeys, wrongMessage, crown.aggregatedSignature().toBytes());

        assertThat(isValid).isFalse();
    }

    @Test
    @DisplayName("Invalid aggregate with tampered signature fails verification")
    void invalidAggregateWithTamperedSignatureFailsVerification() {
        var aggregator = new CrownAggregator(provider);
        var message = testEvent.getDigest().getBytes();

        // Create valid signatures
        var signatures = createSignatures(0, 7, message);
        var committeeSignatures = new HashMap<Long, List<BLSSignature>>();
        committeeSignatures.put(100L, signatures);

        var crown = aggregator.createMultiCommitteeAggregate(committeeSignatures, testEvent);

        // Tamper with signature
        var tamperedSigBytes = crown.aggregatedSignature().toBytes().clone();
        tamperedSigBytes[50] ^= 0xFF; // Flip bits
        var tamperedSignature = new BLSSignature(tamperedSigBytes);

        // Collect public keys
        var publicKeys = new ArrayList<byte[]>();
        for (int i = 0; i < 7; i++) {
            publicKeys.add(keyPairs.get(i).publicKey());
        }

        // Try to verify tampered signature
        var isValid = provider.verifyAggregate(publicKeys, message, tamperedSignature.toBytes());

        assertThat(isValid).isFalse();
    }

    @Test
    @DisplayName("Invalid aggregate with wrong public keys fails verification")
    void invalidAggregateWithWrongPublicKeysFailsVerification() {
        var aggregator = new CrownAggregator(provider);
        var message = testEvent.getDigest().getBytes();

        // Create signatures from keys 0-6
        var signatures = createSignatures(0, 7, message);
        var committeeSignatures = new HashMap<Long, List<BLSSignature>>();
        committeeSignatures.put(100L, signatures);

        var crown = aggregator.createMultiCommitteeAggregate(committeeSignatures, testEvent);

        // Use WRONG public keys (7-13 instead of 0-6)
        var wrongPublicKeys = new ArrayList<byte[]>();
        for (int i = 7; i < 14; i++) {
            wrongPublicKeys.add(keyPairs.get(i).publicKey());
        }

        // Try to verify with wrong keys
        var isValid = provider.verifyAggregate(wrongPublicKeys, message, crown.aggregatedSignature().toBytes());

        assertThat(isValid).isFalse();
    }

    @Test
    @DisplayName("Aggregate with partial committee participation verifies correctly")
    void aggregateWithPartialCommitteeParticipationVerifiesCorrectly() {
        var aggregator = new CrownAggregator(provider);
        var message = testEvent.getDigest().getBytes();

        // Committee 1: 3 out of 7 signers
        var committee1Sigs = createSignatures(0, 3, message);
        // Committee 2: All 7 signers
        var committee2Sigs = createSignatures(7, 14, message);
        // Committee 3: 2 out of 7 signers
        var committee3Sigs = createSignatures(14, 16, message);

        var committeeSignatures = new HashMap<Long, List<BLSSignature>>();
        committeeSignatures.put(100L, committee1Sigs);
        committeeSignatures.put(200L, committee2Sigs);
        committeeSignatures.put(300L, committee3Sigs);

        var crown = aggregator.createMultiCommitteeAggregate(committeeSignatures, testEvent);

        // Collect public keys for ACTUAL signers only (3 + 7 + 2 = 12)
        var publicKeys = new ArrayList<byte[]>();
        for (int i = 0; i < 3; i++) {
            publicKeys.add(keyPairs.get(i).publicKey());
        }
        for (int i = 7; i < 14; i++) {
            publicKeys.add(keyPairs.get(i).publicKey());
        }
        for (int i = 14; i < 16; i++) {
            publicKeys.add(keyPairs.get(i).publicKey());
        }

        // Verify aggregate
        var isValid = provider.verifyAggregate(publicKeys, message, crown.aggregatedSignature().toBytes());

        assertThat(isValid).isTrue();
    }

    @Test
    @DisplayName("Aggregate with mismatched signer count fails verification")
    void aggregateWithMismatchedSignerCountFailsVerification() {
        var aggregator = new CrownAggregator(provider);
        var message = testEvent.getDigest().getBytes();

        // Create signatures from 7 signers
        var signatures = createSignatures(0, 7, message);
        var committeeSignatures = new HashMap<Long, List<BLSSignature>>();
        committeeSignatures.put(100L, signatures);

        var crown = aggregator.createMultiCommitteeAggregate(committeeSignatures, testEvent);

        // Provide only 5 public keys (but aggregate contains 7 signatures)
        var publicKeys = new ArrayList<byte[]>();
        for (int i = 0; i < 5; i++) {
            publicKeys.add(keyPairs.get(i).publicKey());
        }

        // Try to verify with wrong number of keys
        var isValid = provider.verifyAggregate(publicKeys, message, crown.aggregatedSignature().toBytes());

        assertThat(isValid).isFalse();
    }

    @Test
    @DisplayName("Aggregate with single signer per committee verifies correctly")
    void aggregateWithSingleSignerPerCommitteeVerifiesCorrectly() {
        var aggregator = new CrownAggregator(provider);
        var message = testEvent.getDigest().getBytes();

        // 5 committees, each with 1 signer
        var committeeSignatures = new HashMap<Long, List<BLSSignature>>();
        for (int i = 0; i < 5; i++) {
            var epoch = (i + 1) * 100L;
            var signatures = createSignatures(i, i + 1, message);
            committeeSignatures.put(epoch, signatures);
        }

        var crown = aggregator.createMultiCommitteeAggregate(committeeSignatures, testEvent);

        // Collect public keys
        var publicKeys = new ArrayList<byte[]>();
        for (int i = 0; i < 5; i++) {
            publicKeys.add(keyPairs.get(i).publicKey());
        }

        // Verify aggregate
        var isValid = provider.verifyAggregate(publicKeys, message, crown.aggregatedSignature().toBytes());

        assertThat(isValid).isTrue();
    }

    @Test
    @DisplayName("Large aggregate (100 signers) verifies correctly")
    void largeAggregateVerifiesCorrectly() {
        var aggregator = new CrownAggregator(provider);
        var message = testEvent.getDigest().getBytes();

        // Generate additional keys for this test
        var largeKeyPool = new ArrayList<BLSProvider.KeyPair>();
        for (int i = 0; i < 100; i++) {
            largeKeyPool.add(provider.generateKeyPair(random));
        }

        // Create 10 committees with 10 signers each
        var committeeSignatures = new HashMap<Long, List<BLSSignature>>();
        for (int i = 0; i < 10; i++) {
            var epoch = (i + 1) * 100L;
            var signatures = new ArrayList<BLSSignature>();
            for (int j = 0; j < 10; j++) {
                var keyIndex = i * 10 + j;
                var sig = provider.sign(largeKeyPool.get(keyIndex).secretKey(), message);
                signatures.add(new BLSSignature(sig));
            }
            committeeSignatures.put(epoch, signatures);
        }

        var crown = aggregator.createMultiCommitteeAggregate(committeeSignatures, testEvent);

        // Collect all 100 public keys
        var publicKeys = new ArrayList<byte[]>();
        for (int i = 0; i < 100; i++) {
            publicKeys.add(largeKeyPool.get(i).publicKey());
        }

        // Verify aggregate
        var isValid = provider.verifyAggregate(publicKeys, message, crown.aggregatedSignature().toBytes());

        assertThat(isValid).isTrue();
    }

    @Test
    @DisplayName("Verification detects mixed valid and invalid signatures")
    void verificationDetectsMixedValidAndInvalidSignatures() {
        var message = testEvent.getDigest().getBytes();
        var wrongMessage = DigestAlgorithm.DEFAULT.digest("wrong".getBytes()).getBytes();

        // Create some valid signatures and some invalid (on wrong message)
        var validSigs = createSignatures(0, 3, message);
        var invalidSigs = createSignatures(3, 5, wrongMessage); // Wrong message!

        var allSigs = new ArrayList<BLSSignature>();
        allSigs.addAll(validSigs);
        allSigs.addAll(invalidSigs);

        // Try to aggregate mixed signatures
        var committeeSignatures = new HashMap<Long, List<BLSSignature>>();
        committeeSignatures.put(100L, allSigs);

        var aggregator = new CrownAggregator(provider);
        var crown = aggregator.createMultiCommitteeAggregate(committeeSignatures, testEvent);

        // Collect public keys
        var publicKeys = new ArrayList<byte[]>();
        for (int i = 0; i < 5; i++) {
            publicKeys.add(keyPairs.get(i).publicKey());
        }

        // Verification should FAIL because signatures were on different messages
        var isValid = provider.verifyAggregate(publicKeys, message, crown.aggregatedSignature().toBytes());

        assertThat(isValid).isFalse();
    }

    // Helper methods

    private List<BLSSignature> createSignatures(int startIdx, int endIdx, byte[] message) {
        var signatures = new ArrayList<BLSSignature>();
        for (int i = startIdx; i < endIdx && i < keyPairs.size(); i++) {
            var sig = provider.sign(keyPairs.get(i).secretKey(), message);
            signatures.add(new BLSSignature(sig));
        }
        return signatures;
    }
}
