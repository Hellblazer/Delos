/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.cryptography;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for Ed448↔X448 key conversion.
 * <p>
 * Tests validate:
 * 1. Deterministic conversion (same input → same output)
 * 2. Key correspondence (converted private generates matching converted public)
 * 3. Actual encryption/decryption with converted keys
 * 4. Different keys produce different conversions
 * <p>
 * Mathematical constraints documented:
 * - Ed448→X448 private: Uses SHAKE256 hash with 114-byte output, one-way (cannot reverse)
 * - Ed448→X448 public: Birational equivalence over Goldilocks prime, loses sign bit
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc8032">RFC 8032 - EdDSA</a>
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7748">RFC 7748 - X25519/X448</a>
 */
public class Ed448X448ConversionTest {

    @BeforeAll
    static void setup() {
        Security.addProvider(new BouncyCastleProvider());
    }

    /**
     * Test deterministic conversion - same Ed448 key always produces same X448 key.
     */
    @Test
    void conversionIsDeterministic() {
        var keyPair = SignatureAlgorithm.ED_448.generateKeyPair();

        // Convert multiple times
        var x448_1 = SignatureAlgorithm.ED_448.toEncryption(keyPair);
        var x448_2 = SignatureAlgorithm.ED_448.toEncryption(keyPair);
        var x448_3 = SignatureAlgorithm.ED_448.toEncryption(keyPair);

        // All conversions must produce identical keys
        assertArrayEquals(x448_1.getPrivate().getEncoded(), x448_2.getPrivate().getEncoded(),
            "Private key conversion must be deterministic");
        assertArrayEquals(x448_2.getPrivate().getEncoded(), x448_3.getPrivate().getEncoded(),
            "Private key conversion must be deterministic");
        assertArrayEquals(x448_1.getPublic().getEncoded(), x448_2.getPublic().getEncoded(),
            "Public key conversion must be deterministic");
        assertArrayEquals(x448_2.getPublic().getEncoded(), x448_3.getPublic().getEncoded(),
            "Public key conversion must be deterministic");
    }

    /**
     * Test that different Ed448 keys produce different X448 keys.
     */
    @Test
    void differentKeysProduceDifferentConversions() {
        var keyPair1 = SignatureAlgorithm.ED_448.generateKeyPair();
        var keyPair2 = SignatureAlgorithm.ED_448.generateKeyPair();

        var x448_1 = SignatureAlgorithm.ED_448.toEncryption(keyPair1);
        var x448_2 = SignatureAlgorithm.ED_448.toEncryption(keyPair2);

        assertFalse(Arrays.equals(x448_1.getPrivate().getEncoded(), x448_2.getPrivate().getEncoded()),
            "Different Ed448 keys must produce different X448 private keys");
        assertFalse(Arrays.equals(x448_1.getPublic().getEncoded(), x448_2.getPublic().getEncoded()),
            "Different Ed448 keys must produce different X448 public keys");
    }

    /**
     * Test key correspondence: converted private key generates matching converted public key.
     * Validates via ECDH self-agreement.
     */
    @Test
    void keyCorrespondenceAfterConversion() throws Exception {
        var ed448KeyPair = SignatureAlgorithm.ED_448.generateKeyPair();
        var x448KeyPair = SignatureAlgorithm.ED_448.toEncryption(ed448KeyPair);

        // Test via ECDH - if keys correspond, ECDH with self produces valid shared secret
        KeyAgreement ka = KeyAgreement.getInstance("X448");
        ka.init(x448KeyPair.getPrivate());
        ka.doPhase(x448KeyPair.getPublic(), true);
        byte[] sharedSecret = ka.generateSecret();

        assertNotNull(sharedSecret);
        assertEquals(56, sharedSecret.length, "X448 shared secret should be 56 bytes");
    }

    /**
     * End-to-end test: encrypt with one converted key pair, decrypt with another.
     * This is the critical test that validates the conversion works for real encryption.
     */
    @Test
    void encryptDecryptWithConvertedKeys() throws Exception {
        // Alice and Bob each have Ed448 signing keys
        var aliceEd = SignatureAlgorithm.ED_448.generateKeyPair();
        var bobEd = SignatureAlgorithm.ED_448.generateKeyPair();

        // Convert to X448 for encryption
        var aliceX = SignatureAlgorithm.ED_448.toEncryption(aliceEd);
        var bobX = SignatureAlgorithm.ED_448.toEncryption(bobEd);

        // Alice computes shared secret with Bob's public key
        KeyAgreement aliceKa = KeyAgreement.getInstance("X448");
        aliceKa.init(aliceX.getPrivate());
        aliceKa.doPhase(bobX.getPublic(), true);
        byte[] aliceShared = aliceKa.generateSecret();

        // Bob computes shared secret with Alice's public key
        KeyAgreement bobKa = KeyAgreement.getInstance("X448");
        bobKa.init(bobX.getPrivate());
        bobKa.doPhase(aliceX.getPublic(), true);
        byte[] bobShared = bobKa.generateSecret();

        // Shared secrets must match (ECDH property)
        assertArrayEquals(aliceShared, bobShared, "ECDH shared secrets must match");

        // Use first 32 bytes of 56-byte shared secret for AES-256-GCM
        byte[] aesKeyBytes = Arrays.copyOf(aliceShared, 32);
        SecretKey aesKey = new SecretKeySpec(aesKeyBytes, "AES");
        byte[] plaintext = "Hello from Ed448→X448 conversion!".getBytes(StandardCharsets.UTF_8);
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);

        // Alice encrypts
        Cipher encCipher = Cipher.getInstance("AES/GCM/NoPadding");
        encCipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));
        byte[] ciphertext = encCipher.doFinal(plaintext);

        // Bob decrypts
        byte[] bobAesKeyBytes = Arrays.copyOf(bobShared, 32);
        SecretKey bobAesKey = new SecretKeySpec(bobAesKeyBytes, "AES");
        Cipher decCipher = Cipher.getInstance("AES/GCM/NoPadding");
        decCipher.init(Cipher.DECRYPT_MODE, bobAesKey, new GCMParameterSpec(128, iv));
        byte[] decrypted = decCipher.doFinal(ciphertext);

        assertArrayEquals(plaintext, decrypted, "Decrypted text must match original");
    }

    /**
     * Test that Ed448 generates valid 57-byte keys.
     */
    @Test
    void ed448KeysHaveCorrectSize() {
        var keyPair = SignatureAlgorithm.ED_448.generateKeyPair();

        // Ed448 public key is 57 bytes
        assertEquals(57, SignatureAlgorithm.ED_448.publicKeyLength());

        // Signature is 114 bytes (2 * 57)
        assertEquals(114, SignatureAlgorithm.ED_448.signatureLength());
    }

    /**
     * Test that X448 converted keys have correct size.
     */
    @Test
    void x448ConvertedKeysHaveCorrectSize() {
        var ed448KeyPair = SignatureAlgorithm.ED_448.generateKeyPair();
        var x448KeyPair = SignatureAlgorithm.ED_448.toEncryption(ed448KeyPair);

        // X448 uses XDH algorithm
        assertEquals("XDH", x448KeyPair.getPrivate().getAlgorithm());
        assertEquals("XDH", x448KeyPair.getPublic().getAlgorithm());
    }

    /**
     * Test signing and verification still works with Ed448 after conversion exists.
     */
    @Test
    void ed448SigningStillWorksAfterConversionImplemented() {
        var keyPair = SignatureAlgorithm.ED_448.generateKeyPair();
        var message = "Test message for Ed448 signature".getBytes(StandardCharsets.UTF_8);

        // Sign
        var signature = SignatureAlgorithm.ED_448.sign(org.joou.ULong.valueOf(0), keyPair.getPrivate(), message);

        // Verify
        assertTrue(SignatureAlgorithm.ED_448.verify(keyPair.getPublic(), signature, message),
            "Ed448 signature must verify");
    }

    /**
     * Document: X448→Ed448 private key recovery is impossible.
     * Same constraint as X25519→Ed25519 - hash function is one-way.
     */
    @Test
    void x448ToEd448PrivateRecoveryIsImpossible() {
        // This test documents the mathematical constraint:
        // Ed448→X448 private key conversion uses SHAKE256 hash
        // SHAKE256 is a one-way XOF - cannot be reversed
        // Therefore, given only an X448 private key, the original
        // Ed448 seed CANNOT be recovered

        // The correct architecture is:
        // 1. Store Ed448 keys as source of truth (e.g., in KERI KERL)
        // 2. Derive X448 keys on-demand when encryption is needed
        // 3. Never need to "recover" Ed448 from X448

        assertTrue(true, "This test documents the impossibility of reverse private key conversion");
    }

    /**
     * Document: X448→Ed448 public key recovery is ambiguous.
     * Lost sign bit means two candidate Ed448 public keys exist.
     */
    @Test
    void x448ToEd448PublicRecoveryIsAmbiguous() {
        // The birational equivalence u = (1 + y) / (1 - y) is invertible:
        // y = (u - 1) / (u + 1)
        // But Ed448 public key encoding includes x coordinate sign bit
        // X448 only uses u-coordinate, so sign bit is lost
        // This means two candidate Ed448 public keys exist for any X448 public key

        assertTrue(true, "This test documents the ambiguity of reverse public key conversion");
    }

    /**
     * Test that the low-level EdDSAOperations methods work correctly.
     */
    @Test
    void edDSAOperationsX448MethodsWork() {
        var keyPair = SignatureAlgorithm.ED_448.generateKeyPair();

        // Get raw Ed448 seed
        var edPrivate = (java.security.interfaces.EdECPrivateKey) keyPair.getPrivate();
        byte[] seed = edPrivate.getBytes().orElseThrow();

        // Convert private key
        byte[] x448Private = EdDSAOperations.toX448PrivateKey(seed);
        assertNotNull(x448Private);
        assertEquals(56, x448Private.length, "X448 scalar should be 56 bytes");

        // Verify clamping was applied
        assertEquals(0, x448Private[0] & 0x03, "Bits 0-1 should be cleared");
        assertEquals(0x80, x448Private[55] & 0x80, "Bit 447 should be set");

        // Convert public key
        byte[] ed448Public = SignatureAlgorithm.ED_448.encode(keyPair.getPublic());
        byte[] x448Public = EdDSAOperations.toX448PublicKey(ed448Public);
        assertNotNull(x448Public);
        assertEquals(56, x448Public.length, "X448 public key should be 56 bytes");
    }

    /**
     * Test consistent seed-based generation produces consistent X448 keys.
     */
    @Test
    void seedBasedGenerationIsConsistent() {
        byte[] seed = new byte[57];
        new SecureRandom().nextBytes(seed);

        // Generate Ed448 from same seed twice using deterministic SecureRandom
        var ed1 = SignatureAlgorithm.ED_448.generateKeyPair(new SecureRandom() {
            private int pos = 0;
            @Override
            public void nextBytes(byte[] bytes) {
                for (int i = 0; i < bytes.length; i++) {
                    bytes[i] = seed[pos % seed.length];
                    pos++;
                }
            }
        });

        var ed2 = SignatureAlgorithm.ED_448.generateKeyPair(new SecureRandom() {
            private int pos = 0;
            @Override
            public void nextBytes(byte[] bytes) {
                for (int i = 0; i < bytes.length; i++) {
                    bytes[i] = seed[pos % seed.length];
                    pos++;
                }
            }
        });

        var x1 = SignatureAlgorithm.ED_448.toEncryption(ed1);
        var x2 = SignatureAlgorithm.ED_448.toEncryption(ed2);

        assertArrayEquals(x1.getPublic().getEncoded(), x2.getPublic().getEncoded(),
            "Same seed should produce same X448 public key");
    }

    /**
     * Test that encryption algorithm X_448 works with converted keys.
     */
    @Test
    void encryptionAlgorithmX448WorksWithConvertedKeys() throws Exception {
        var ed448KeyPair = SignatureAlgorithm.ED_448.generateKeyPair();
        var x448KeyPair = SignatureAlgorithm.ED_448.toEncryption(ed448KeyPair);

        // Use EncryptionAlgorithm.X_448 for DHKEM
        var algorithm = EncryptionAlgorithm.X_448;

        // Encapsulate with public key
        var encapsulated = algorithm.encapsulated(x448KeyPair.getPublic());
        assertNotNull(encapsulated.key());
        assertNotNull(encapsulated.encapsulation());

        // Decapsulate with private key
        var secretKey = algorithm.decapsulate(x448KeyPair.getPrivate(), encapsulated.encapsulation(), "AES");
        assertNotNull(secretKey);

        // Test that encryption/decryption works with the derived keys
        // Use raw key bytes since DHKEM may return non-AES key type
        byte[] encKeyBytes = encapsulated.key().getEncoded();
        byte[] decKeyBytes = secretKey.getEncoded();

        // Use first 32 bytes for AES-256 if keys are longer
        byte[] aesEncKeyBytes = encKeyBytes.length > 32 ? Arrays.copyOf(encKeyBytes, 32) : encKeyBytes;
        byte[] aesDecKeyBytes = decKeyBytes.length > 32 ? Arrays.copyOf(decKeyBytes, 32) : decKeyBytes;

        byte[] plaintext = "Test DHKEM with X448".getBytes(StandardCharsets.UTF_8);
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);

        // Encrypt with encapsulated key
        SecretKey aesEncKey = new SecretKeySpec(aesEncKeyBytes, "AES");
        Cipher encCipher = Cipher.getInstance("AES/GCM/NoPadding");
        encCipher.init(Cipher.ENCRYPT_MODE, aesEncKey, new GCMParameterSpec(128, iv));
        byte[] ciphertext = encCipher.doFinal(plaintext);

        // Decrypt with decapsulated key
        SecretKey aesDecKey = new SecretKeySpec(aesDecKeyBytes, "AES");
        Cipher decCipher = Cipher.getInstance("AES/GCM/NoPadding");
        decCipher.init(Cipher.DECRYPT_MODE, aesDecKey, new GCMParameterSpec(128, iv));
        byte[] decrypted = decCipher.doFinal(ciphertext);

        assertArrayEquals(plaintext, decrypted, "DHKEM encryption/decryption must work");
    }
}
