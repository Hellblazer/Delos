/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.validation;

import com.hellblazer.delos.cryptography.bls.*;
import com.hellblazer.delos.witness.aggregation.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive tests for AggregateValidator.
 * <p>
 * Phase 1B-2-B: TDD implementation for BLS aggregate signature validation.
 * Tests cover:
 * - Valid aggregate validation
 * - Invalid bitmap detection
 * - Threshold mismatch detection
 * - Malformed signature rejection
 * - Concurrent validation safety
 * - Edge cases (empty bitmap, single signer, max committee)
 *
 * @author hal.hildebrand
 */
@DisplayName("AggregateValidator Tests")
class AggregateValidatorTest {

    private BLSProvider provider;
    private AggregateValidator validator;
    private Random entropy;

    // Test fixtures
    private List<BLSKeyPair> committeeKeys;
    private List<BLSPublicKey> committeePublicKeys;
    private byte[] testMessage;

    @BeforeEach
    void setUp() {
        provider = BLSProvider.getDefault();
        validator = new AggregateValidator(provider);
        entropy = new SecureRandom();

        // Create test message
        testMessage = new byte[32];
        entropy.nextBytes(testMessage);

        // Create committee of 7 members (Byzantine threshold 5 = ceil((7+5)/2))
        var committeeSize = 7;
        committeeKeys = new ArrayList<>();
        committeePublicKeys = new ArrayList<>();

        for (int i = 0; i < committeeSize; i++) {
            var keyPair = BLSKeyPair.generate(entropy, provider);
            committeeKeys.add(keyPair);
            committeePublicKeys.add(keyPair.publicKey());
        }
    }

    // ========== Valid Aggregate Tests ==========

    @Test
    @DisplayName("Valid aggregate with threshold met should validate")
    void testValidAggregateThresholdMet() {
        // Create aggregate from 5 signers (meets Byzantine threshold)
        var signers = List.of(0, 1, 2, 3, 4);
        var aggregate = createAggregate(signers);

        var result = validator.validate(aggregate, committeePublicKeys, testMessage);

        assertThat(result).isInstanceOf(ValidationResult.Valid.class);
        var valid = (ValidationResult.Valid) result;
        assertThat(valid.aggregate()).isEqualTo(aggregate);
    }

    @Test
    @DisplayName("Valid aggregate with all members should validate")
    void testValidAggregateAllMembers() {
        // All 7 members sign
        var signers = List.of(0, 1, 2, 3, 4, 5, 6);
        var aggregate = createAggregate(signers);

        var result = validator.validate(aggregate, committeePublicKeys, testMessage);

        assertThat(result).isInstanceOf(ValidationResult.Valid.class);
    }

    @Test
    @DisplayName("Valid aggregate with single signer should validate")
    void testValidAggregateSingleSigner() {
        // Single member signs
        var signers = List.of(3);
        var aggregate = createAggregate(signers);

        var result = validator.validate(aggregate, committeePublicKeys, testMessage);

        assertThat(result).isInstanceOf(ValidationResult.Valid.class);
    }

    // ========== Invalid Signature Tests ==========

    @Test
    @DisplayName("Aggregate with wrong message should fail signature validation")
    void testInvalidSignatureWrongMessage() {
        var signers = List.of(0, 1, 2);
        var aggregate = createAggregate(signers);

        // Validate against different message
        var wrongMessage = new byte[32];
        entropy.nextBytes(wrongMessage);

        var result = validator.validate(aggregate, committeePublicKeys, wrongMessage);

        assertThat(result).isInstanceOf(ValidationResult.ValidationFailed.class);
        var failed = (ValidationResult.ValidationFailed) result;
        assertThat(failed.reason()).contains("signature verification failed");
    }

    @Test
    @DisplayName("Aggregate with corrupted signature should fail validation")
    void testInvalidSignatureCorrupted() {
        var signers = List.of(0, 1, 2);
        var aggregate = createAggregate(signers);

        // Corrupt the signature
        var corruptedSigBytes = aggregate.aggregatedSignature().toBytes();
        corruptedSigBytes[0] ^= 0xFF; // Flip bits in first byte
        var corruptedSig = new BLSSignature(corruptedSigBytes);
        var corruptedAggregate = new BLSAggregate(corruptedSig, aggregate.signerBitmap());

        var result = validator.validate(corruptedAggregate, committeePublicKeys, testMessage);

        assertThat(result).isInstanceOf(ValidationResult.ValidationFailed.class);
    }

    @Test
    @DisplayName("Aggregate with mismatched signer keys should fail validation")
    void testInvalidSignatureMismatchedKeys() {
        var signers = List.of(0, 1, 2);
        var aggregate = createAggregate(signers);

        // Create different committee public keys
        var wrongKeys = new ArrayList<BLSPublicKey>();
        for (int i = 0; i < 7; i++) {
            var keyPair = BLSKeyPair.generate(entropy, provider);
            wrongKeys.add(keyPair.publicKey());
        }

        var result = validator.validate(aggregate, wrongKeys, testMessage);

        assertThat(result).isInstanceOf(ValidationResult.ValidationFailed.class);
    }

    // ========== Invalid Bitmap Tests ==========

    @Test
    @DisplayName("Empty bitmap should fail validation")
    void testInvalidBitmapEmpty() {
        var signers = List.of(0, 1, 2);
        var aggregate = createAggregate(signers);

        // Create aggregate with empty bitmap
        var emptyBitmap = new byte[1]; // All zeros
        var invalidAggregate = new BLSAggregate(aggregate.aggregatedSignature(), emptyBitmap);

        var result = validator.validateBitmap(invalidAggregate.signerBitmap(), 3);

        assertThat(result).isInstanceOf(ValidationResult.InvalidBitmap.class);
        var invalid = (ValidationResult.InvalidBitmap) result;
        assertThat(invalid.reason()).contains("no signers");
    }

    @Test
    @DisplayName("Bitmap with out-of-bounds indices should fail validation")
    void testInvalidBitmapOutOfBounds() {
        // Create bitmap with bit set beyond committee size
        var oversizedBitmap = new byte[2];
        oversizedBitmap[1] = (byte) 0x80; // Bit 15 set (beyond 7-member committee)

        var result = validator.validateBitmap(oversizedBitmap, committeePublicKeys.size());

        assertThat(result).isInstanceOf(ValidationResult.InvalidBitmap.class);
        var invalid = (ValidationResult.InvalidBitmap) result;
        assertThat(invalid.reason()).contains("beyond committee size");
    }

    @Test
    @DisplayName("Bitmap size validation should reject negative expected count")
    void testInvalidBitmapNegativeCount() {
        var bitmap = new byte[]{0x01}; // Valid bitmap

        assertThatThrownBy(() -> validator.validateBitmap(bitmap, -1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("expectedSignerCount must be positive");
    }

    // ========== Threshold Validation Tests ==========

    @Test
    @DisplayName("Threshold validation should detect insufficient signers")
    void testInvalidThresholdInsufficient() {
        var signers = List.of(0, 1); // Only 2 signers
        var aggregate = createAggregate(signers);

        var result = validator.validateThreshold(aggregate, 5); // Requires 5

        assertThat(result).isInstanceOf(ValidationResult.InvalidThreshold.class);
        var invalid = (ValidationResult.InvalidThreshold) result;
        assertThat(invalid.expected()).isEqualTo(5);
        assertThat(invalid.actual()).isEqualTo(2);
    }

    @Test
    @DisplayName("Threshold validation should accept exact threshold")
    void testValidThresholdExact() {
        var signers = List.of(0, 1, 2, 3, 4); // Exactly 5 signers
        var aggregate = createAggregate(signers);

        var result = validator.validateThreshold(aggregate, 5);

        assertThat(result).isInstanceOf(ValidationResult.Valid.class);
        var valid = (ValidationResult.Valid) result;
        assertThat(valid.aggregate()).isEqualTo(aggregate);
    }

    @Test
    @DisplayName("Threshold validation should accept above threshold")
    void testValidThresholdAbove() {
        var signers = List.of(0, 1, 2, 3, 4, 5); // 6 signers
        var aggregate = createAggregate(signers);

        var result = validator.validateThreshold(aggregate, 5);

        assertThat(result).isInstanceOf(ValidationResult.Valid.class);
        var valid = (ValidationResult.Valid) result;
        assertThat(valid.aggregate()).isEqualTo(aggregate);
    }

    // ========== Signature Format Validation Tests ==========

    @Test
    @DisplayName("Valid BLS signature format should pass validation")
    void testValidSignatureFormat() {
        var keyPair = BLSKeyPair.generate(entropy, provider);
        var signature = keyPair.secretKey().sign(testMessage);

        var result = validator.validateSignatureFormat(signature);

        // Standalone signature validation returns a non-failure marker
        assertThat(result).isNotInstanceOf(ValidationResult.InvalidSignature.class);
    }

    @Test
    @DisplayName("Null signature should fail format validation")
    void testInvalidSignatureFormatNull() {
        assertThatThrownBy(() -> validator.validateSignatureFormat(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("All-zero signature should fail format validation")
    void testInvalidSignatureFormatAllZeros() {
        var zeroSig = new BLSSignature(new byte[96]);

        var result = validator.validateSignatureFormat(zeroSig);

        assertThat(result).isInstanceOf(ValidationResult.ValidationFailed.class);
        var failed = (ValidationResult.ValidationFailed) result;
        assertThat(failed.reason()).contains("invalid signature format");
    }

    // ========== Edge Cases ==========

    @Test
    @DisplayName("Large committee (64 members) should validate correctly")
    void testLargeCommitteeValidation() {
        // Create large committee
        var largeCommittee = new ArrayList<BLSPublicKey>();
        var largeKeys = new ArrayList<BLSKeyPair>();
        for (int i = 0; i < 64; i++) {
            var keyPair = BLSKeyPair.generate(entropy, provider);
            largeKeys.add(keyPair);
            largeCommittee.add(keyPair.publicKey());
        }

        // Create aggregate from 42 signers (Byzantine threshold)
        var signers = new ArrayList<Integer>();
        var signatures = new ArrayList<BLSSignature>();
        for (int i = 0; i < 42; i++) {
            signers.add(i);
            signatures.add(largeKeys.get(i).secretKey().sign(testMessage));
        }

        var aggregate = BLSAggregate.aggregate(signatures, signers);
        var result = validator.validate(aggregate, largeCommittee, testMessage);

        assertThat(result).isInstanceOf(ValidationResult.Valid.class);
    }

    @Test
    @DisplayName("Null parameters should throw NullPointerException")
    void testNullParameterValidation() {
        var signers = List.of(0, 1, 2);
        var aggregate = createAggregate(signers);

        assertThatThrownBy(() -> validator.validate(null, committeePublicKeys, testMessage))
            .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> validator.validate(aggregate, null, testMessage))
            .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> validator.validate(aggregate, committeePublicKeys, null))
            .isInstanceOf(NullPointerException.class);
    }

    // ========== Concurrent Validation Tests ==========

    @Test
    @DisplayName("Concurrent validation should be thread-safe")
    void testConcurrentValidation() throws InterruptedException {
        var threadCount = 10;
        var iterationsPerThread = 50;
        var successCount = new AtomicInteger(0);
        var failureCount = new AtomicInteger(0);
        var latch = new CountDownLatch(threadCount);

        // Create aggregates for concurrent validation
        var validAggregates = new ArrayList<BLSAggregate>();
        for (int i = 0; i < threadCount; i++) {
            var signers = List.of(i % 7, (i + 1) % 7, (i + 2) % 7);
            validAggregates.add(createAggregate(signers));
        }

        // Spawn concurrent validation threads
        for (int t = 0; t < threadCount; t++) {
            var aggregate = validAggregates.get(t);
            Thread.ofVirtual().start(() -> {
                try {
                    for (int i = 0; i < iterationsPerThread; i++) {
                        var result = validator.validate(aggregate, committeePublicKeys, testMessage);
                        if (result instanceof ValidationResult.Valid) {
                            successCount.incrementAndGet();
                        } else {
                            failureCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();

        // All validations should succeed
        assertThat(successCount.get()).isEqualTo(threadCount * iterationsPerThread);
        assertThat(failureCount.get()).isZero();
    }

    // ========== Helper Methods ==========

    /**
     * Create a valid BLS aggregate from specified signer indices.
     */
    private BLSAggregate createAggregate(List<Integer> signerIndices) {
        var signatures = new ArrayList<BLSSignature>();
        for (var index : signerIndices) {
            var keyPair = committeeKeys.get(index);
            var signature = keyPair.secretKey().sign(testMessage);
            signatures.add(signature);
        }
        return BLSAggregate.aggregate(signatures, signerIndices);
    }
}
