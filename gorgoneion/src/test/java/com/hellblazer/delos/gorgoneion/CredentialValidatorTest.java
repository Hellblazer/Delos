/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.gorgoneion;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.proto.Digeste;
import com.hellblazer.delos.cryptography.proto.Sig;
import com.hellblazer.delos.gorgoneion.proto.MemberSignature;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.event.proto.Ident;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CredentialValidator.
 * Tests timestamp validation, signature verification, BFT helpers, and convenience validators.
 *
 * @author hal.hildebrand
 */
class CredentialValidatorTest {

    private static final DigestAlgorithm DIGEST_ALGORITHM = DigestAlgorithm.DEFAULT;
    private static final Duration MAX_DURATION = Duration.ofSeconds(30);
    private static final Duration CLOCK_SKEW_TOLERANCE = Duration.ofSeconds(5);

    private DynamicContext<Member> context;
    private Parameters parameters;
    private Digest localMemberId;
    private CredentialValidator validator;
    private Clock fixedClock;
    private Instant now;
    private ControlledIdentifierMember testMember;

    @BeforeEach
    void setUp() throws Exception {
        // Create a fixed clock for deterministic timestamp testing
        now = Instant.parse("2025-01-01T12:00:00Z");
        fixedClock = Clock.fixed(now, ZoneId.of("UTC"));

        // Create stereotomy for member identity
        var secureRandom = SecureRandom.getInstance("SHA1PRNG");
        secureRandom.setSeed(new byte[] { 1, 2, 3, 4 });
        var kerl = new MemKERL(DIGEST_ALGORITHM);
        var keyStore = new MemKeyStore();
        var stereotomy = new StereotomyImpl(keyStore, kerl, secureRandom);

        // Create member identifier
        testMember = new ControlledIdentifierMember(stereotomy.newIdentifier());
        localMemberId = testMember.getId();

        // Create test context
        var b = DynamicContext.<Member>newBuilder();
        b.setCardinality(10);
        context = b.build();
        context.activate(testMember);

        // Create parameters
        parameters = Parameters.newBuilder()
                               .setClock(fixedClock)
                               .setDigestAlgorithm(DIGEST_ALGORITHM)
                               .setMaxDuration(MAX_DURATION)
                               .setClockSkewTolerance(CLOCK_SKEW_TOLERANCE)
                               .setKerl(kerl)
                               .build();

        // Create validator
        validator = new CredentialValidator(context, parameters, localMemberId);
    }

    // ==================== Timestamp Validation Tests ====================

    @Test
    void testTimestampValid_withinBounds() {
        // Create a timestamp at the current time
        var timestamp = Timestamp.newBuilder()
                                 .setSeconds(now.getEpochSecond())
                                 .setNanos(now.getNano())
                                 .build();

        assertTrue(validator.isTimestampValid(timestamp), "Current timestamp should be valid");
    }

    @Test
    void testTimestampValid_slightlyInPast() {
        // Create a timestamp 10 seconds in the past (within MAX_DURATION)
        var pastInstant = now.minus(Duration.ofSeconds(10));
        var timestamp = Timestamp.newBuilder()
                                 .setSeconds(pastInstant.getEpochSecond())
                                 .setNanos(pastInstant.getNano())
                                 .build();

        assertTrue(validator.isTimestampValid(timestamp), "Timestamp slightly in past should be valid");
    }

    @Test
    void testTimestampValid_slightlyInFuture() {
        // Create a timestamp 3 seconds in the future (within CLOCK_SKEW_TOLERANCE)
        var futureInstant = now.plus(Duration.ofSeconds(3));
        var timestamp = Timestamp.newBuilder()
                                 .setSeconds(futureInstant.getEpochSecond())
                                 .setNanos(futureInstant.getNano())
                                 .build();

        assertTrue(validator.isTimestampValid(timestamp), "Timestamp slightly in future should be valid");
    }

    @Test
    void testTimestampInvalid_tooFarInPast() {
        // Create a timestamp 60 seconds in the past (beyond MAX_DURATION of 30s)
        var pastInstant = now.minus(Duration.ofSeconds(60));
        var timestamp = Timestamp.newBuilder()
                                 .setSeconds(pastInstant.getEpochSecond())
                                 .setNanos(pastInstant.getNano())
                                 .build();

        assertFalse(validator.isTimestampValid(timestamp), "Timestamp too far in past should be invalid");
    }

    @Test
    void testTimestampInvalid_tooFarInFuture() {
        // Create a timestamp 10 seconds in the future (beyond CLOCK_SKEW_TOLERANCE of 5s)
        var futureInstant = now.plus(Duration.ofSeconds(10));
        var timestamp = Timestamp.newBuilder()
                                 .setSeconds(futureInstant.getEpochSecond())
                                 .setNanos(futureInstant.getNano())
                                 .build();

        assertFalse(validator.isTimestampValid(timestamp), "Timestamp too far in future should be invalid");
    }

    @Test
    void testTimestampValid_atBoundary_past() {
        // Create a timestamp exactly at MAX_DURATION boundary (should still be valid)
        var boundaryInstant = now.minus(MAX_DURATION);
        var timestamp = Timestamp.newBuilder()
                                 .setSeconds(boundaryInstant.getEpochSecond())
                                 .setNanos(boundaryInstant.getNano())
                                 .build();

        assertTrue(validator.isTimestampValid(timestamp), "Timestamp at past boundary should be valid");
    }

    @Test
    void testTimestampValid_atBoundary_future() {
        // Create a timestamp exactly at CLOCK_SKEW_TOLERANCE boundary (should still be valid)
        var boundaryInstant = now.plus(CLOCK_SKEW_TOLERANCE);
        var timestamp = Timestamp.newBuilder()
                                 .setSeconds(boundaryInstant.getEpochSecond())
                                 .setNanos(boundaryInstant.getNano())
                                 .build();

        assertTrue(validator.isTimestampValid(timestamp), "Timestamp at future boundary should be valid");
    }

    @Test
    void testIsTimestampAtOrAfter_equal() {
        var ts1 = Timestamp.newBuilder().setSeconds(1000).setNanos(0).build();
        var ts2 = Timestamp.newBuilder().setSeconds(1000).setNanos(0).build();

        assertTrue(validator.isTimestampAtOrAfter(ts1, ts2), "Equal timestamps should pass");
    }

    @Test
    void testIsTimestampAtOrAfter_after() {
        var ts1 = Timestamp.newBuilder().setSeconds(1001).setNanos(0).build();
        var ts2 = Timestamp.newBuilder().setSeconds(1000).setNanos(0).build();

        assertTrue(validator.isTimestampAtOrAfter(ts1, ts2), "Later timestamp should pass");
    }

    @Test
    void testIsTimestampAtOrAfter_before() {
        var ts1 = Timestamp.newBuilder().setSeconds(999).setNanos(0).build();
        var ts2 = Timestamp.newBuilder().setSeconds(1000).setNanos(0).build();

        assertFalse(validator.isTimestampAtOrAfter(ts1, ts2), "Earlier timestamp should fail");
    }

    @Test
    void testToInstant() {
        var timestamp = Timestamp.newBuilder()
                                 .setSeconds(1704067200)  // 2024-01-01 00:00:00 UTC
                                 .setNanos(123456789)
                                 .build();

        var instant = CredentialValidator.toInstant(timestamp);

        assertEquals(1704067200, instant.getEpochSecond());
        assertEquals(123456789, instant.getNano());
    }

    // ==================== BFT Helpers Tests ====================

    @Test
    void testGetRequiredMajority_singleMember() {
        // With only one member, majority should be 1
        assertEquals(1, validator.getRequiredMajority(), "Single member context should require majority of 1");
    }

    @Test
    void testHasMajority_success() {
        var required = validator.getRequiredMajority();
        assertTrue(validator.hasMajority(required), "Exact majority count should pass");
        assertTrue(validator.hasMajority(required + 1), "Above majority count should pass");
    }

    @Test
    void testHasMajority_failure() {
        var required = validator.getRequiredMajority();
        assertFalse(validator.hasMajority(required - 1), "Below majority count should fail");
        assertFalse(validator.hasMajority(0), "Zero count should fail");
    }

    @Test
    void testExpectedBftSigners_singleMember() {
        var ident = Ident.newBuilder()
                         .setSelfAddressing(localMemberId.toDigeste())
                         .build();

        var signers = validator.expectedBftSigners(ident);

        assertEquals(1, signers.size(), "Single member context should have one signer");
        assertTrue(signers.contains(localMemberId), "Signer should be local member");
    }

    // ==================== Membership Helpers Tests ====================

    @Test
    void testIsMember_exists() {
        assertTrue(validator.isMember(localMemberId), "Activated member should be recognized");
    }

    @Test
    void testIsMember_notExists() {
        var unknownId = DIGEST_ALGORITHM.random();
        assertFalse(validator.isMember(unknownId), "Unknown member should not be recognized");
    }

    @Test
    void testGetMember_exists() {
        var member = validator.getMember(localMemberId);
        assertTrue(member.isPresent(), "Activated member should be found");
        assertEquals(localMemberId, member.get().getId(), "Member ID should match");
    }

    @Test
    void testGetMember_notExists() {
        var unknownId = DIGEST_ALGORITHM.random();
        var member = validator.getMember(unknownId);
        assertTrue(member.isEmpty(), "Unknown member should return empty");
    }

    // ==================== Value Validators Tests ====================

    @Test
    void testHasValidNoise_valid() {
        var noise = DIGEST_ALGORITHM.random().toDigeste();
        assertTrue(validator.hasValidNoise(noise), "Non-default noise should be valid");
    }

    @Test
    void testHasValidNoise_invalid() {
        var noise = Digeste.getDefaultInstance();
        assertFalse(validator.hasValidNoise(noise), "Default noise should be invalid");
    }

    @Test
    void testHasValidMember_valid() {
        var member = Ident.newBuilder()
                          .setSelfAddressing(localMemberId.toDigeste())
                          .build();
        assertTrue(validator.hasValidMember(member), "Non-default member should be valid");
    }

    @Test
    void testHasValidMember_invalid() {
        var member = Ident.getDefaultInstance();
        assertFalse(validator.hasValidMember(member), "Default member should be invalid");
    }

    // ==================== Signature Verification Tests ====================

    @Test
    void testCountValidSignatures_emptyList() {
        var count = validator.countValidSignatures(
            Collections.emptyList(),
            Set.of(localMemberId),
            ByteString.copyFromUtf8("test data")
        );

        assertEquals(0, count, "Empty signature list should return 0");
    }

    @Test
    void testCountValidSignatures_unknownMember() {
        var unknownId = DIGEST_ALGORITHM.random();
        var signature = MemberSignature.newBuilder()
                                        .setId(unknownId.toDigeste())
                                        .setSignature(Sig.getDefaultInstance())
                                        .build();

        var count = validator.countValidSignatures(
            List.of(signature),
            Set.of(unknownId),
            ByteString.copyFromUtf8("test data")
        );

        assertEquals(0, count, "Unknown member signature should not be counted");
    }

    @Test
    void testCountValidSignatures_notInExpectedSet() {
        // Create signature from known member but not in expected set
        var signature = MemberSignature.newBuilder()
                                        .setId(localMemberId.toDigeste())
                                        .setSignature(Sig.getDefaultInstance())
                                        .build();
        var otherMemberId = DIGEST_ALGORITHM.random();

        var count = validator.countValidSignatures(
            List.of(signature),
            Set.of(otherMemberId),  // localMemberId not in this set
            ByteString.copyFromUtf8("test data")
        );

        assertEquals(0, count, "Signature from member not in expected set should not be counted");
    }

    @Test
    void testCountValidSignatures_validSignature() {
        // Create a valid signature using the test member
        var testData = ByteString.copyFromUtf8("test data for signing");
        var johnHancock = testMember.sign(testData);

        var signature = MemberSignature.newBuilder()
                                        .setId(localMemberId.toDigeste())
                                        .setSignature(johnHancock.toSig())
                                        .build();

        var count = validator.countValidSignatures(
            List.of(signature),
            Set.of(localMemberId),
            testData
        );

        assertEquals(1, count, "Valid signature should be counted");
    }

    @Test
    void testCountValidSignatures_invalidSignature() {
        // Create an invalid signature (sign different data than what we verify)
        var signedData = ByteString.copyFromUtf8("original data");
        var differentData = ByteString.copyFromUtf8("different data");
        var johnHancock = testMember.sign(signedData);

        var signature = MemberSignature.newBuilder()
                                        .setId(localMemberId.toDigeste())
                                        .setSignature(johnHancock.toSig())
                                        .build();

        var count = validator.countValidSignatures(
            List.of(signature),
            Set.of(localMemberId),
            differentData  // Different from what was signed
        );

        assertEquals(0, count, "Invalid signature should not be counted");
    }

    @Test
    void testVerifySignature_valid() {
        var testData = ByteString.copyFromUtf8("test data");
        var johnHancock = testMember.sign(testData);

        assertTrue(validator.verifySignature(localMemberId, johnHancock, testData),
                   "Valid signature should verify");
    }

    @Test
    void testVerifySignature_invalid() {
        var signedData = ByteString.copyFromUtf8("original data");
        var differentData = ByteString.copyFromUtf8("different data");
        var johnHancock = testMember.sign(signedData);

        assertFalse(validator.verifySignature(localMemberId, johnHancock, differentData),
                    "Invalid signature should not verify");
    }

    @Test
    void testVerifySignature_unknownMember() {
        var unknownId = DIGEST_ALGORITHM.random();
        var testData = ByteString.copyFromUtf8("test data");
        var johnHancock = testMember.sign(testData);

        assertFalse(validator.verifySignature(unknownId, johnHancock, testData),
                    "Unknown member signature should not verify");
    }

    // ==================== Accessors Tests ====================

    @Test
    void testAccessors() {
        assertSame(parameters, validator.parameters(), "Parameters accessor should return same instance");
        assertSame(context, validator.context(), "Context accessor should return same instance");
        assertEquals(localMemberId, validator.localMemberId(), "LocalMemberId accessor should return correct ID");
    }

    // ==================== Constructor Tests ====================

    @Test
    void testConstructor_nullContext() {
        assertThrows(NullPointerException.class, () ->
            new CredentialValidator(null, parameters, localMemberId),
            "Null context should throw NullPointerException"
        );
    }

    @Test
    void testConstructor_nullParameters() {
        assertThrows(NullPointerException.class, () ->
            new CredentialValidator(context, null, localMemberId),
            "Null parameters should throw NullPointerException"
        );
    }

    @Test
    void testConstructor_nullLocalMemberId() {
        assertThrows(NullPointerException.class, () ->
            new CredentialValidator(context, parameters, null),
            "Null localMemberId should throw NullPointerException"
        );
    }
}
