/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.cryptography.bls.rotation.BLSKeyRotationManager;
import com.hellblazer.delos.cryptography.bls.rotation.KeyStatus;
import com.hellblazer.delos.cryptography.bls.rotation.KeyVersion;
import com.hellblazer.delos.cryptography.proto.Sig;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.google.protobuf.ByteString;

import java.security.KeyPair;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Test fixture factory for key rotation testing.
 * <p>
 * Provides reusable test helpers for creating mock objects, signing keys, and test data
 * used across both detection and validation package tests.
 * </p>
 *
 * @author hal.hildebrand
 * @since 1.0 (Phase 1C-3-A)
 */
public class TestFixtures {

    private static final SecureRandom ENTROPY = new SecureRandom();

    /**
     * Create a test SelfAddressingIdentifier with configurable digest.
     *
     * @return Mocked SelfAddressingIdentifier with test defaults
     */
    public static SelfAddressingIdentifier createTestMemberId() {
        var memberId = mock(SelfAddressingIdentifier.class);
        when(memberId.toString()).thenReturn("test-member-" + ENTROPY.nextInt(10000));

        // Mock digest for equality checks
        var digest = mock(Digest.class);
        when(digest.getBytes()).thenReturn(new byte[32]);
        when(memberId.getDigest()).thenReturn(digest);

        return memberId;
    }

    /**
     * Create a test SelfAddressingIdentifier with a specific name.
     *
     * @param name Member identifier name
     * @return Mocked SelfAddressingIdentifier with specified name
     */
    public static SelfAddressingIdentifier createTestMemberId(String name) {
        var memberId = mock(SelfAddressingIdentifier.class);
        when(memberId.toString()).thenReturn(name);

        var digest = mock(Digest.class);
        when(digest.getBytes()).thenReturn(name.getBytes());
        when(memberId.getDigest()).thenReturn(digest);

        return memberId;
    }

    /**
     * Create a test Ed25519 key pair.
     *
     * @return Generated Ed25519 key pair
     */
    public static KeyPair createTestKeyPair() {
        return SignatureAlgorithm.ED_25519.generateKeyPair(ENTROPY);
    }

    /**
     * Create a mock BLSKeyRotationManager configured with specified key statuses.
     *
     * @param statuses Varargs of KeyStatus values to include in manager's valid keys
     * @return Mocked BLSKeyRotationManager
     */
    public static BLSKeyRotationManager createMockRotationManager(KeyStatus... statuses) {
        var manager = mock(BLSKeyRotationManager.class);

        // Configure getValidKeys() to return map of version -> KeyVersion
        var validKeys = new HashMap<Integer, KeyVersion>();
        if (statuses.length == 0) {
            // Default: ACTIVE status only at version 1
            var keyVersion = mock(KeyVersion.class);
            when(keyVersion.status()).thenReturn(KeyStatus.ACTIVE);
            when(keyVersion.versionNumber()).thenReturn(1);
            validKeys.put(1, keyVersion);
        } else {
            // Add each status as a version
            for (int i = 0; i < statuses.length; i++) {
                var keyVersion = mock(KeyVersion.class);
                when(keyVersion.status()).thenReturn(statuses[i]);
                when(keyVersion.versionNumber()).thenReturn(i + 1);
                validKeys.put(i + 1, keyVersion);
            }
        }

        when(manager.getValidKeys(any())).thenReturn(validKeys);

        return manager;
    }

    /**
     * Create a mock BLSKeyRotationManager in grace period (ACTIVE and DEPRECATED keys valid).
     *
     * @return Mocked manager with grace period configuration
     */
    public static BLSKeyRotationManager createGracePeriodRotationManager() {
        return createMockRotationManager(KeyStatus.ACTIVE, KeyStatus.DEPRECATED);
    }

    /**
     * Create a signed JohnHancock for test data.
     *
     * @param keyPair Key pair to sign with
     * @param data    Data to sign
     * @return JohnHancock signature
     */
    public static JohnHancock signData(KeyPair keyPair, byte[] data) {
        try {
            var sig = Sig.newBuilder()
                .setCode(SignatureAlgorithm.ED_25519.signatureCode())
                .setSequenceNumber(0)
                .addSignatures(ByteString.copyFrom(data))
                .build();

            // Note: This creates a placeholder signature. For real signing, use Signer.
            // This is sufficient for test mocks where we verify signature acceptance.
            return JohnHancock.of(sig);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create test signature", e);
        }
    }

    /**
     * Create mock KeyState with specified public key.
     * <p>
     * Used for testing WitnessSignatureValidator.KeyLookup implementations
     * that need to verify against stored key state.
     * </p>
     *
     * @param activeKey  Active public key
     * @param deprecatedKey Deprecated public key (optional, can be null)
     * @return Mocked KeyState object
     */
    public static Object createMockKeyState(PublicKey activeKey, PublicKey deprecatedKey) {
        var keyState = mock(Object.class);
        // Store keys as attributes for test access
        // Note: KeyState interface would be defined in stereotomy module
        // This is a placeholder for test purposes
        return keyState;
    }

    /**
     * Create a test rotation identifier.
     *
     * @return Unique rotation ID for testing
     */
    public static String createTestRotationId() {
        return "rotation-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Create test binary data to sign/verify.
     *
     * @param length Length of test data
     * @return Random byte array of specified length
     */
    public static byte[] createTestData(int length) {
        var data = new byte[length];
        ENTROPY.nextBytes(data);
        return data;
    }

    /**
     * Create test binary data (default 32 bytes).
     *
     * @return Random 32-byte array
     */
    public static byte[] createTestData() {
        return createTestData(32);
    }

    /**
     * Create a mock Identifier (generic, not SelfAddressing).
     *
     * @return Mocked Identifier
     */
    public static Identifier createMockIdentifier() {
        var identifier = mock(Identifier.class);
        when(identifier.toString()).thenReturn("test-identifier");
        return identifier;
    }

    /**
     * Create multiple test member IDs.
     *
     * @param count Number of members to create
     * @return List of mocked SelfAddressingIdentifier instances
     */
    public static List<SelfAddressingIdentifier> createTestMemberIds(int count) {
        var members = new ArrayList<SelfAddressingIdentifier>();
        for (int i = 0; i < count; i++) {
            members.add(createTestMemberId("member-" + i));
        }
        return members;
    }
}
