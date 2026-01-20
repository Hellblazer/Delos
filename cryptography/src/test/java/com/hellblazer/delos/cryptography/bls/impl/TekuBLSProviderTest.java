package com.hellblazer.delos.cryptography.bls.impl;

import com.hellblazer.delos.cryptography.bls.BLSProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;

/**
 * Test suite for TekuBLSProvider - BLS12-381 implementation using tech.pegasys.teku:bls library.
 * <p>
 * Phase 4 - Day 6: Key Generation (4 tests)
 * Tests written FIRST following TDD discipline (RED phase).
 */
@DisplayName("TekuBLSProvider - Key Generation Tests")
class TekuBLSProviderTest {

    private BLSProvider provider;

    @BeforeEach
    void setUp() {
        provider = new TekuBLSProvider();
    }

    // ===== Task 4.1: Key Generation Tests (RED phase) =====

    @Test
    @DisplayName("generateKeyPair produces valid key sizes")
    void generateKeyPairProducesValidKeySizes() {
        var random = new Random(42);
        var keyPair = provider.generateKeyPair(random);

        assertThat(keyPair).isNotNull();
        assertThat(keyPair.secretKey())
            .as("Secret key must be 32 bytes")
            .hasSize(32);
        assertThat(keyPair.publicKey())
            .as("Public key must be 48 bytes (compressed G1)")
            .hasSize(48);
    }

    @Test
    @DisplayName("generateKeyPair is deterministic with seeded Random")
    void generateKeyPairIsDeterministicWithSeededRandom() {
        var random1 = new Random(123456);
        var keyPair1 = provider.generateKeyPair(random1);

        var random2 = new Random(123456);
        var keyPair2 = provider.generateKeyPair(random2);

        assertThat(keyPair1.secretKey())
            .as("Same seed should produce same secret key")
            .isEqualTo(keyPair2.secretKey());
        assertThat(keyPair1.publicKey())
            .as("Same seed should produce same public key")
            .isEqualTo(keyPair2.publicKey());
    }

    @Test
    @DisplayName("generateKeyPair produces different keys on multiple calls")
    void generateKeyPairProducesDifferentKeysOnMultipleCalls() {
        var random = new Random();
        var keyPair1 = provider.generateKeyPair(random);
        var keyPair2 = provider.generateKeyPair(random);
        var keyPair3 = provider.generateKeyPair(random);

        // All keys should be unique
        assertThat(keyPair1.secretKey())
            .as("First and second secret keys should differ")
            .isNotEqualTo(keyPair2.secretKey());
        assertThat(keyPair2.secretKey())
            .as("Second and third secret keys should differ")
            .isNotEqualTo(keyPair3.secretKey());
        assertThat(keyPair1.publicKey())
            .as("First and second public keys should differ")
            .isNotEqualTo(keyPair2.publicKey());
    }

    @Test
    @DisplayName("generateKeyPair rejects null Random")
    void generateKeyPairRejectsNullRandom() {
        assertThatThrownBy(() -> provider.generateKeyPair(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("random");
    }

    // ===== Task 4.3: Signing and Verification Tests (RED phase) =====

    @Test
    @DisplayName("sign and verify work for valid signature")
    void signAndVerifyWorkForValidSignature() {
        var random = new Random(123);
        var keyPair = provider.generateKeyPair(random);
        var message = "Hello BLS12-381".getBytes();

        var signature = provider.sign(keyPair.secretKey(), message);

        assertThat(signature)
            .as("Signature should be 96 bytes (compressed G2)")
            .hasSize(96);
        assertThat(provider.verify(keyPair.publicKey(), message, signature))
            .as("Valid signature should verify")
            .isTrue();
    }

    @Test
    @DisplayName("verify rejects signature with wrong public key")
    void verifyRejectsSignatureWithWrongPublicKey() {
        var random = new Random(456);
        var keyPair1 = provider.generateKeyPair(random);
        var keyPair2 = provider.generateKeyPair(random);
        var message = "Test message".getBytes();

        var signature = provider.sign(keyPair1.secretKey(), message);

        assertThat(provider.verify(keyPair2.publicKey(), message, signature))
            .as("Signature with wrong key should not verify")
            .isFalse();
    }

    @Test
    @DisplayName("verify rejects signature for wrong message")
    void verifyRejectsSignatureForWrongMessage() {
        var random = new Random(789);
        var keyPair = provider.generateKeyPair(random);
        var message1 = "Original message".getBytes();
        var message2 = "Different message".getBytes();

        var signature = provider.sign(keyPair.secretKey(), message1);

        assertThat(provider.verify(keyPair.publicKey(), message2, signature))
            .as("Signature for different message should not verify")
            .isFalse();
    }

    @Test
    @DisplayName("verify rejects corrupted signature")
    void verifyRejectsCorruptedSignature() {
        var random = new Random(101112);
        var keyPair = provider.generateKeyPair(random);
        var message = "Test".getBytes();

        var signature = provider.sign(keyPair.secretKey(), message);
        signature[0] ^= 0xFF; // Corrupt first byte

        assertThat(provider.verify(keyPair.publicKey(), message, signature))
            .as("Corrupted signature should not verify")
            .isFalse();
    }

    @Test
    @DisplayName("different messages produce different signatures")
    void differentMessagesProduceDifferentSignatures() {
        var random = new Random(131415);
        var keyPair = provider.generateKeyPair(random);
        var message1 = "Message 1".getBytes();
        var message2 = "Message 2".getBytes();

        var sig1 = provider.sign(keyPair.secretKey(), message1);
        var sig2 = provider.sign(keyPair.secretKey(), message2);

        assertThat(sig1)
            .as("Different messages should produce different signatures")
            .isNotEqualTo(sig2);
    }

    @Test
    @DisplayName("sign rejects null parameters")
    void signRejectsNullParameters() {
        var random = new Random();
        var keyPair = provider.generateKeyPair(random);
        var message = "test".getBytes();

        assertThatThrownBy(() -> provider.sign(null, message))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> provider.sign(keyPair.secretKey(), null))
            .isInstanceOf(NullPointerException.class);
    }

    // ===== Task 4.5: Aggregation Tests (RED phase) =====

    @Test
    @DisplayName("aggregateSignatures combines multiple signatures")
    void aggregateSignaturesCombinesMultipleSignatures() {
        var random = new Random(161718);
        var message = "Common message for aggregation".getBytes();

        // Generate 3 key pairs and signatures
        var signatures = new ArrayList<byte[]>();
        for (int i = 0; i < 3; i++) {
            var keyPair = provider.generateKeyPair(random);
            signatures.add(provider.sign(keyPair.secretKey(), message));
        }

        var aggregate = provider.aggregateSignatures(signatures);

        assertThat(aggregate)
            .as("Aggregate signature should be 96 bytes")
            .hasSize(96);
        assertThat(aggregate)
            .as("Aggregate should differ from individual signatures")
            .isNotEqualTo(signatures.get(0))
            .isNotEqualTo(signatures.get(1))
            .isNotEqualTo(signatures.get(2));
    }

    @Test
    @DisplayName("verifyAggregate accepts valid aggregate signature")
    void verifyAggregateAcceptsValidAggregateSignature() {
        var random = new Random(192021);
        var message = "Aggregate test message".getBytes();

        // Generate 7 signers
        var publicKeys = new ArrayList<byte[]>();
        var signatures = new ArrayList<byte[]>();
        for (int i = 0; i < 7; i++) {
            var keyPair = provider.generateKeyPair(random);
            publicKeys.add(keyPair.publicKey());
            signatures.add(provider.sign(keyPair.secretKey(), message));
        }

        var aggregate = provider.aggregateSignatures(signatures);

        assertThat(provider.verifyAggregate(publicKeys, message, aggregate))
            .as("Valid aggregate should verify")
            .isTrue();
    }

    @Test
    @DisplayName("verifyAggregate rejects aggregate with wrong public keys")
    void verifyAggregateRejectsAggregateWithWrongPublicKeys() {
        var random = new Random(222324);
        var message = "Test message".getBytes();

        // Generate 5 actual signers
        var actualPublicKeys = new ArrayList<byte[]>();
        var signatures = new ArrayList<byte[]>();
        for (int i = 0; i < 5; i++) {
            var keyPair = provider.generateKeyPair(random);
            actualPublicKeys.add(keyPair.publicKey());
            signatures.add(provider.sign(keyPair.secretKey(), message));
        }

        var aggregate = provider.aggregateSignatures(signatures);

        // Generate 5 different public keys
        var wrongPublicKeys = new ArrayList<byte[]>();
        for (int i = 0; i < 5; i++) {
            var keyPair = provider.generateKeyPair(random);
            wrongPublicKeys.add(keyPair.publicKey());
        }

        assertThat(provider.verifyAggregate(wrongPublicKeys, message, aggregate))
            .as("Aggregate with wrong keys should not verify")
            .isFalse();
    }

    @Test
    @DisplayName("aggregateSignatures handles single signature")
    void aggregateSignaturesHandlesSingleSignature() {
        var random = new Random(252627);
        var keyPair = provider.generateKeyPair(random);
        var message = "Single signature".getBytes();
        var signature = provider.sign(keyPair.secretKey(), message);

        var aggregate = provider.aggregateSignatures(List.of(signature));

        assertThat(aggregate)
            .as("Single signature aggregation should work")
            .hasSize(96);
        assertThat(provider.verifyAggregate(List.of(keyPair.publicKey()), message, aggregate))
            .as("Single signature aggregate should verify")
            .isTrue();
    }

    @Test
    @DisplayName("aggregateSignatures rejects empty list")
    void aggregateSignaturesRejectsEmptyList() {
        assertThatThrownBy(() -> provider.aggregateSignatures(List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("empty");
    }

    // ===== Task 4.9: Batch Verification Tests (RED phase) =====

    @Test
    @DisplayName("batchVerify accepts all valid signatures")
    void batchVerifyAcceptsAllValidSignatures() {
        var random = new Random(282930);
        var publicKeys = new ArrayList<byte[]>();
        var messages = new ArrayList<byte[]>();
        var signatures = new ArrayList<byte[]>();

        // Generate 5 different message/signature pairs
        for (int i = 0; i < 5; i++) {
            var keyPair = provider.generateKeyPair(random);
            var message = ("Message " + i).getBytes();
            publicKeys.add(keyPair.publicKey());
            messages.add(message);
            signatures.add(provider.sign(keyPair.secretKey(), message));
        }

        assertThat(provider.batchVerify(publicKeys, messages, signatures))
            .as("All valid signatures should verify in batch")
            .isTrue();
    }

    @Test
    @DisplayName("batchVerify rejects if any signature is invalid")
    void batchVerifyRejectsIfAnySignatureIsInvalid() {
        var random = new Random(313233);
        var publicKeys = new ArrayList<byte[]>();
        var messages = new ArrayList<byte[]>();
        var signatures = new ArrayList<byte[]>();

        // Generate 3 valid signatures
        for (int i = 0; i < 3; i++) {
            var keyPair = provider.generateKeyPair(random);
            var message = ("Valid message " + i).getBytes();
            publicKeys.add(keyPair.publicKey());
            messages.add(message);
            signatures.add(provider.sign(keyPair.secretKey(), message));
        }

        // Add 1 invalid signature (wrong key)
        var wrongKeyPair = provider.generateKeyPair(random);
        var validMessage = "Wrong key message".getBytes();
        var wrongSigner = provider.generateKeyPair(random);
        publicKeys.add(wrongKeyPair.publicKey());
        messages.add(validMessage);
        signatures.add(provider.sign(wrongSigner.secretKey(), validMessage));

        assertThat(provider.batchVerify(publicKeys, messages, signatures))
            .as("Batch verification should fail if any signature is invalid")
            .isFalse();
    }

    // ===== Task 4.11: End-to-End Integration Test (RED phase) =====

    @Test
    @DisplayName("endToEnd workflow: keygen -> sign -> aggregate -> verify")
    void endToEndWorkflowKeygenSignAggregateVerify() {
        var random = new Random(343536);
        var message = "Committee decision approved".getBytes();

        // Simulate a 21-node committee
        var publicKeys = new ArrayList<byte[]>();
        var signatures = new ArrayList<byte[]>();

        for (int i = 0; i < 21; i++) {
            var keyPair = provider.generateKeyPair(random);
            publicKeys.add(keyPair.publicKey());
            signatures.add(provider.sign(keyPair.secretKey(), message));
        }

        // Aggregate all signatures
        var aggregate = provider.aggregateSignatures(signatures);

        // Verify aggregate
        assertThat(provider.verifyAggregate(publicKeys, message, aggregate))
            .as("Full workflow should succeed: keygen -> sign -> aggregate -> verify")
            .isTrue();

        // Ensure aggregate is compact (96 bytes vs 21*96 = 2016 bytes)
        assertThat(aggregate.length)
            .as("Aggregate should be constant 96 bytes")
            .isEqualTo(96);
    }
}
