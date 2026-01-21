/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
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
        provider = TekuBLSProvider.getInstance();
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

    // ===== Task 1C-1-B: Caching and Singleton Tests =====

    @Test
    @DisplayName("singleton pattern returns same instance")
    void singletonPatternReturnsSameInstance() {
        var provider1 = TekuBLSProvider.getInstance();
        var provider2 = TekuBLSProvider.getInstance();

        assertThat(provider1)
            .as("Should return same singleton instance")
            .isSameAs(provider2);
    }

    @Test
    @DisplayName("BLSProvider.getDefault returns singleton instance")
    void blsProviderGetDefaultReturnsSingleton() {
        var provider1 = BLSProvider.getDefault();
        var provider2 = BLSProvider.getDefault();

        assertThat(provider1)
            .as("BLSProvider.getDefault() should return singleton")
            .isSameAs(provider2);
        assertThat(provider1)
            .as("Should be instance of TekuBLSProvider")
            .isInstanceOf(TekuBLSProvider.class);
    }

    @Test
    @DisplayName("public key caching reduces repeated parsing overhead")
    void publicKeyCachingReducesRepeatedParsingOverhead() {
        var singletonProvider = TekuBLSProvider.getInstance();
        singletonProvider.clearCache();

        var random = new Random(12345);
        var keyPair = singletonProvider.generateKeyPair(random);
        var message = "cache test message".getBytes();
        var signature = singletonProvider.sign(keyPair.secretKey(), message);

        // First verification (cache miss)
        var valid1 = singletonProvider.verify(keyPair.publicKey(), message, signature);
        assertThat(valid1).as("First verification should succeed").isTrue();

        var stats1 = singletonProvider.getCacheStats();
        var initialMisses = stats1.missCount();
        var initialHits = stats1.hitCount();

        // Second verification with same key (cache hit)
        var valid2 = singletonProvider.verify(keyPair.publicKey(), message, signature);
        assertThat(valid2).as("Second verification should succeed").isTrue();

        var stats2 = singletonProvider.getCacheStats();
        var newHits = stats2.hitCount();

        assertThat(newHits)
            .as("Cache hit count should increase on second call with same key")
            .isGreaterThan(initialHits);
    }

    @Test
    @DisplayName("cache hit performance is significantly faster than cache miss")
    void cacheHitPerformanceSignificantlyFasterThanCacheMiss() {
        var singletonProvider = TekuBLSProvider.getInstance();
        singletonProvider.clearCache();

        var random = new Random(54321);
        var keyPair = singletonProvider.generateKeyPair(random);
        var message = "performance test".getBytes();
        var signature = singletonProvider.sign(keyPair.secretKey(), message);

        // Warmup to stabilize JIT
        for (int i = 0; i < 100; i++) {
            singletonProvider.verify(keyPair.publicKey(), message, signature);
        }

        // Measure cache miss latency (first call after clearing)
        var missDurations = new ArrayList<Long>();
        for (int i = 0; i < 10; i++) {
            singletonProvider.clearCache();
            var start = System.nanoTime();
            singletonProvider.verify(keyPair.publicKey(), message, signature);
            var duration = (System.nanoTime() - start) / 1000; // Convert to µs
            missDurations.add(duration);
        }
        var avgMissDuration = missDurations.stream().mapToLong(Long::longValue).average().orElse(0);

        // Measure cache hit latency (repeated calls with populated cache)
        singletonProvider.clearCache();
        singletonProvider.verify(keyPair.publicKey(), message, signature); // Populate cache
        var hitDurations = new ArrayList<Long>();
        for (int i = 0; i < 100; i++) {
            var start = System.nanoTime();
            singletonProvider.verify(keyPair.publicKey(), message, signature);
            var duration = (System.nanoTime() - start) / 1000; // Convert to µs
            hitDurations.add(duration);
        }
        var avgHitDuration = hitDurations.stream().mapToLong(Long::longValue).average().orElse(0);

        // Cache hit should be faster than cache miss
        assertThat(avgHitDuration)
            .as("Cache hit latency should be less than cache miss latency")
            .isLessThan(avgMissDuration);
    }

    @Test
    @DisplayName("cache eviction when exceeding maximum size")
    void cacheEvictionWhenExceedingMaximumSize() {
        var singletonProvider = TekuBLSProvider.getInstance();
        singletonProvider.clearCache();

        var random = new Random(99999);
        var message = "eviction test".getBytes();

        // Generate more keys than cache size (10,000)
        for (int i = 0; i < 10_100; i++) {
            var keyPair = singletonProvider.generateKeyPair(random);
            var signature = singletonProvider.sign(keyPair.secretKey(), message);
            singletonProvider.verify(keyPair.publicKey(), message, signature);
        }

        var stats = singletonProvider.getCacheStats();

        assertThat(stats.evictionCount())
            .as("Should have evicted entries when cache exceeded max size")
            .isGreaterThan(0);
    }

    @Test
    @DisplayName("cache efficiency in verifyAggregate with repeated keys")
    void cacheEfficiencyInVerifyAggregateWithRepeatedKeys() {
        var singletonProvider = TekuBLSProvider.getInstance();
        singletonProvider.clearCache();

        var random = new Random(777);
        var message = "aggregate cache test".getBytes();

        // Generate 100 keys and signatures
        var publicKeys = new ArrayList<byte[]>();
        var signatures = new ArrayList<byte[]>();
        for (int i = 0; i < 100; i++) {
            var keyPair = singletonProvider.generateKeyPair(random);
            publicKeys.add(keyPair.publicKey());
            signatures.add(singletonProvider.sign(keyPair.secretKey(), message));
        }

        var aggregate = singletonProvider.aggregateSignatures(signatures);

        // Get initial cache stats
        singletonProvider.clearCache();
        singletonProvider.verifyAggregate(publicKeys, message, aggregate);
        var stats1 = singletonProvider.getCacheStats();
        var misses1 = stats1.missCount();

        // Verify again - should have cache hits
        singletonProvider.verifyAggregate(publicKeys, message, aggregate);
        var stats2 = singletonProvider.getCacheStats();
        var hits2 = stats2.hitCount();

        assertThat(hits2)
            .as("Second verifyAggregate call should have cache hits for keys")
            .isGreaterThan(0);
    }

    // ===== Phase 1C-1-C: Batch Aggregate Verification Tests =====

    @Test
    @DisplayName("batchVerifyAggregatesImpl with valid aggregates returns true")
    void batchVerifyAggregatesImplWithValidAggregatesReturnsTrue() {
        var singletonProvider = TekuBLSProvider.getInstance();
        var random = new Random(888);

        // Create committee of 10 keys
        var committee = new ArrayList<byte[]>();
        var secretKeys = new ArrayList<byte[]>();
        for (int i = 0; i < 10; i++) {
            var keyPair = singletonProvider.generateKeyPair(random);
            committee.add(keyPair.publicKey());
            secretKeys.add(keyPair.secretKey());
        }

        // Create 3 aggregates with different messages and signer subsets
        var message1 = "Block 100".getBytes();
        var message2 = "Block 101".getBytes();
        var message3 = "Block 102".getBytes();

        // Aggregate 1: Signers 0, 1, 2
        var sigs1 = new ArrayList<byte[]>();
        for (int i = 0; i <= 2; i++) {
            sigs1.add(singletonProvider.sign(secretKeys.get(i), message1));
        }
        var agg1 = singletonProvider.aggregateSignatures(sigs1);

        // Aggregate 2: Signers 3, 4, 5, 6
        var sigs2 = new ArrayList<byte[]>();
        for (int i = 3; i <= 6; i++) {
            sigs2.add(singletonProvider.sign(secretKeys.get(i), message2));
        }
        var agg2 = singletonProvider.aggregateSignatures(sigs2);

        // Aggregate 3: Signers 7, 8, 9
        var sigs3 = new ArrayList<byte[]>();
        for (int i = 7; i <= 9; i++) {
            sigs3.add(singletonProvider.sign(secretKeys.get(i), message3));
        }
        var agg3 = singletonProvider.aggregateSignatures(sigs3);

        // Prepare batch verification inputs
        var filteredKeyLists = List.of(
            List.of(committee.get(0), committee.get(1), committee.get(2)),
            List.of(committee.get(3), committee.get(4), committee.get(5), committee.get(6)),
            List.of(committee.get(7), committee.get(8), committee.get(9))
        );
        var messages = List.of(message1, message2, message3);
        var signatures = List.of(agg1, agg2, agg3);

        // WHEN: Batch verify aggregates
        var result = provider.batchVerifyAggregatesImpl(filteredKeyLists, messages, signatures);

        // THEN: Should verify successfully
        assertThat(result)
            .as("Valid aggregates should batch-verify")
            .isTrue();
    }

    @Test
    @DisplayName("batchVerifyAggregatesImpl with invalid signature returns false")
    void batchVerifyAggregatesImplWithInvalidSignatureReturnsFalse() {
        var singletonProvider = TekuBLSProvider.getInstance();
        var random = new Random(999);

        var committee = new ArrayList<byte[]>();
        var secretKeys = new ArrayList<byte[]>();
        for (int i = 0; i < 5; i++) {
            var keyPair = singletonProvider.generateKeyPair(random);
            committee.add(keyPair.publicKey());
            secretKeys.add(keyPair.secretKey());
        }

        var message1 = "Valid message".getBytes();
        var message2 = "Invalid message".getBytes();

        // Create valid aggregate for message1
        var sigs1 = new ArrayList<byte[]>();
        for (int i = 0; i < 3; i++) {
            sigs1.add(singletonProvider.sign(secretKeys.get(i), message1));
        }
        var agg1 = singletonProvider.aggregateSignatures(sigs1);

        // Create valid aggregate for message2, but use wrong message for verification
        var sigs2 = new ArrayList<byte[]>();
        for (int i = 3; i < 5; i++) {
            sigs2.add(singletonProvider.sign(secretKeys.get(i), message2));
        }
        var agg2 = singletonProvider.aggregateSignatures(sigs2);

        var filteredKeyLists = List.of(
            List.of(committee.get(0), committee.get(1), committee.get(2)),
            List.of(committee.get(3), committee.get(4))
        );
        // Use wrong message for the second aggregate - verification should fail
        var messages = List.of(message1, message1);
        var signatures = List.of(agg1, agg2);

        // WHEN: Batch verify with one invalid
        var result = provider.batchVerifyAggregatesImpl(filteredKeyLists, messages, signatures);

        // THEN: Should return false
        assertThat(result)
            .as("One invalid aggregate should cause batch verification to fail")
            .isFalse();
    }

    @Test
    @DisplayName("batchVerifyAggregatesImpl with empty list returns true")
    void batchVerifyAggregatesImplWithEmptyListReturnsTrue() {
        var result = provider.batchVerifyAggregatesImpl(List.of(), List.of(), List.of());

        assertThat(result)
            .as("Empty batch should verify successfully")
            .isTrue();
    }

    @Test
    @DisplayName("batchVerifyAggregatesImpl rejects mismatched list sizes")
    void batchVerifyAggregatesImplRejectsMismatchedListSizes() {
        assertThatThrownBy(() -> provider.batchVerifyAggregatesImpl(
            List.of(List.of()),
            List.of(),
            List.of()
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("List sizes must match");
    }

    @Test
    @DisplayName("batchVerifyAggregatesImpl rejects null parameters")
    void batchVerifyAggregatesImplRejectsNullParameters() {
        assertThatThrownBy(() -> provider.batchVerifyAggregatesImpl(null, List.of(), List.of()))
            .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> provider.batchVerifyAggregatesImpl(List.of(), null, List.of()))
            .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> provider.batchVerifyAggregatesImpl(List.of(), List.of(), null))
            .isInstanceOf(NullPointerException.class);
    }
}
