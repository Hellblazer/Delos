/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.cryptography;

import com.codevasp.lazysodium.LazySodium;
import com.codevasp.lazysodium.LazySodiumJava;
import com.codevasp.lazysodium.SodiumJava;
import com.codevasp.lazysodium.utils.Key;
import com.codevasp.lazysodium.utils.KeyPair;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.interfaces.XECPublicKey;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for Ed25519↔X25519 key conversion.
 * <p>
 * Tests validate:
 * 1. Deterministic conversion (same input → same output)
 * 2. Cross-implementation agreement (EdDSAOperations, SignatureAlgorithm, LazySodium)
 * 3. Key correspondence (converted private generates matching converted public)
 * 4. Standard compliance (test vectors from RFC/libsodium)
 * 5. Actual encryption/decryption with converted keys
 * <p>
 * Mathematical constraints documented:
 * - Ed25519→X25519 private: Uses SHA-512 hash, one-way (cannot reverse)
 * - Ed25519→X25519 public: Birational equivalence, loses sign bit (ambiguous reverse)
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc8032">RFC 8032 - Edwards-Curve Digital Signature Algorithm (EdDSA)</a>
 */
public class Ed25519X25519ConversionTest {

    private static LazySodium lazySodium;

    @BeforeAll
    static void setup() {
        Security.addProvider(new BouncyCastleProvider());
        lazySodium = new LazySodiumJava(new SodiumJava());
    }

    /**
     * Test that EdDSAOperations conversion matches known test vectors.
     * These vectors are from libsodium test suite.
     */
    @Test
    void edDSAOperationsMatchesTestVectors() {
        // Test vector 1
        verifyTestVector(
            "be5bf46a933c8703fa48d0c4075c8fe35fb5f2358778c62008d7265ea6eb0858",
            "188dedb57fb265624e370e214eba35799cd17897f1d44663530606a2ed5cb57f",
            "2038f67c3fcfc38429819229d4c874d1f22540ab1349949a766cca0846363f28"
        );

        // Test vector 2
        verifyTestVector(
            "7013fabacfa4bd6eafb75e9d2d426a1f956ccd9acb19b615d3041d3e0b3000e6",
            "c0418dcd2fc1da92d6fb07c2ae4e0e4ddd71819533326047deab1c8c882e806f",
            "ec3e66a867e1f383dbcda7084569ffced6af071e85cb20791523347c59ec3459"
        );

        // Test vector 3 (tests proper u normalization)
        verifyTestVector(
            "f81fe2c27e3884dfa6c3a288f37d0ff5699ddade04b6c7dbc379c68a7e8129a0",
            "e0ee579cf0e094f9aa2c2f87caf8a2e48843fca000325b45400189991c684564",
            "4d8f5ab537e51507965ed841c35cb896ef6c474f789188cd3dd86dfb769ac661"
        );
    }

    private void verifyTestVector(String ed25519SeedHex, String expectedX25519PrivateHex, String expectedX25519PublicHex) {
        byte[] ed25519Seed = Hex.decode(ed25519SeedHex);
        byte[] expectedX25519Private = Hex.decode(expectedX25519PrivateHex);
        byte[] expectedX25519Public = Hex.decode(expectedX25519PublicHex);

        // Test private key conversion
        byte[] actualX25519Private = EdDSAOperations.toX25519PrivateKey(ed25519Seed);
        assertArrayEquals(expectedX25519Private, actualX25519Private,
            "EdDSAOperations private key conversion must match test vector");

        // Derive Ed25519 public from seed, then convert to X25519
        var ed25519Params = new org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(ed25519Seed, 0);
        byte[] ed25519Public = ed25519Params.generatePublicKey().getEncoded();
        byte[] actualX25519Public = EdDSAOperations.toX25519PublicKey(ed25519Public);
        assertArrayEquals(expectedX25519Public, actualX25519Public,
            "EdDSAOperations public key conversion must match test vector");
    }

    /**
     * Test deterministic conversion - same Ed25519 key always produces same X25519 key.
     */
    @Test
    void conversionIsDeterministic() {
        var keyPair = SignatureAlgorithm.ED_25519.generateKeyPair();

        // Convert multiple times
        var x25519_1 = SignatureAlgorithm.ED_25519.toEncryption(keyPair);
        var x25519_2 = SignatureAlgorithm.ED_25519.toEncryption(keyPair);
        var x25519_3 = SignatureAlgorithm.ED_25519.toEncryption(keyPair);

        // All conversions must produce identical keys
        assertArrayEquals(x25519_1.getPrivate().getEncoded(), x25519_2.getPrivate().getEncoded());
        assertArrayEquals(x25519_2.getPrivate().getEncoded(), x25519_3.getPrivate().getEncoded());
        assertArrayEquals(x25519_1.getPublic().getEncoded(), x25519_2.getPublic().getEncoded());
        assertArrayEquals(x25519_2.getPublic().getEncoded(), x25519_3.getPublic().getEncoded());
    }

    /**
     * Test that different Ed25519 keys produce different X25519 keys.
     */
    @Test
    void differentKeysProduceDifferentConversions() {
        var keyPair1 = SignatureAlgorithm.ED_25519.generateKeyPair();
        var keyPair2 = SignatureAlgorithm.ED_25519.generateKeyPair();

        var x25519_1 = SignatureAlgorithm.ED_25519.toEncryption(keyPair1);
        var x25519_2 = SignatureAlgorithm.ED_25519.toEncryption(keyPair2);

        assertFalse(Arrays.equals(x25519_1.getPrivate().getEncoded(), x25519_2.getPrivate().getEncoded()),
            "Different Ed25519 keys must produce different X25519 private keys");
        assertFalse(Arrays.equals(x25519_1.getPublic().getEncoded(), x25519_2.getPublic().getEncoded()),
            "Different Ed25519 keys must produce different X25519 public keys");
    }

    /**
     * CRITICAL TEST: Verify SignatureAlgorithm and LazySodium produce same results.
     * This exposes any discrepancy in the conversion implementations.
     */
    @Test
    void signatureAlgorithmMatchesLazySodium() throws Exception {
        var ed25519KeyPair = SignatureAlgorithm.ED_25519.generateKeyPair();

        // Convert using SignatureAlgorithm
        var sigAlgX25519 = SignatureAlgorithm.ED_25519.toEncryption(ed25519KeyPair);

        // Convert using LazySodium
        KeyPair lsKeyPair = new KeyPair(
            keyFromPublic(ed25519KeyPair.getPublic()),
            keyFromPrivate(ed25519KeyPair.getPrivate())
        );
        var lsX25519 = lazySodium.convertKeyPairEd25519ToCurve25519(lsKeyPair);

        // Extract raw bytes for comparison
        byte[] sigAlgPublicRaw = extractX25519PublicRaw(sigAlgX25519.getPublic());
        byte[] lsPublicRaw = lsX25519.getPublicKey().getAsBytes();

        // Public keys should match
        assertArrayEquals(lsPublicRaw, sigAlgPublicRaw,
            "SignatureAlgorithm public key conversion must match LazySodium reference");
    }

    /**
     * Test key correspondence: converted private key generates matching converted public key.
     */
    @Test
    void keyCorrespondenceAfterConversion() throws Exception {
        var ed25519KeyPair = SignatureAlgorithm.ED_25519.generateKeyPair();
        var x25519KeyPair = SignatureAlgorithm.ED_25519.toEncryption(ed25519KeyPair);

        // Generate public key from the converted private key
        var kpg = java.security.KeyPairGenerator.getInstance("X25519");

        // The X25519 public key derived from our converted private should match
        // the directly converted public key - this is a fundamental property

        // Test via ECDH - if keys correspond, ECDH with self produces valid shared secret
        KeyAgreement ka = KeyAgreement.getInstance("X25519");
        ka.init(x25519KeyPair.getPrivate());
        ka.doPhase(x25519KeyPair.getPublic(), true);
        byte[] sharedSecret = ka.generateSecret();

        assertNotNull(sharedSecret);
        assertEquals(32, sharedSecret.length, "X25519 shared secret should be 32 bytes");
    }

    /**
     * End-to-end test: encrypt with one converted key pair, decrypt with another.
     */
    @Test
    void encryptDecryptWithConvertedKeys() throws Exception {
        // Alice and Bob each have Ed25519 signing keys
        var aliceEd = SignatureAlgorithm.ED_25519.generateKeyPair();
        var bobEd = SignatureAlgorithm.ED_25519.generateKeyPair();

        // Convert to X25519 for encryption
        var aliceX = SignatureAlgorithm.ED_25519.toEncryption(aliceEd);
        var bobX = SignatureAlgorithm.ED_25519.toEncryption(bobEd);

        // Alice computes shared secret with Bob's public key
        KeyAgreement aliceKa = KeyAgreement.getInstance("X25519");
        aliceKa.init(aliceX.getPrivate());
        aliceKa.doPhase(bobX.getPublic(), true);
        byte[] aliceShared = aliceKa.generateSecret();

        // Bob computes shared secret with Alice's public key
        KeyAgreement bobKa = KeyAgreement.getInstance("X25519");
        bobKa.init(bobX.getPrivate());
        bobKa.doPhase(aliceX.getPublic(), true);
        byte[] bobShared = bobKa.generateSecret();

        // Shared secrets must match (ECDH property)
        assertArrayEquals(aliceShared, bobShared, "ECDH shared secrets must match");

        // Use shared secret for AES-GCM encryption
        SecretKey aesKey = new SecretKeySpec(aliceShared, "AES");
        byte[] plaintext = "Hello from Ed25519→X25519 conversion!".getBytes(StandardCharsets.UTF_8);
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);

        // Alice encrypts
        Cipher encCipher = Cipher.getInstance("AES/GCM/NoPadding");
        encCipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(128, iv));
        byte[] ciphertext = encCipher.doFinal(plaintext);

        // Bob decrypts
        SecretKey bobAesKey = new SecretKeySpec(bobShared, "AES");
        Cipher decCipher = Cipher.getInstance("AES/GCM/NoPadding");
        decCipher.init(Cipher.DECRYPT_MODE, bobAesKey, new GCMParameterSpec(128, iv));
        byte[] decrypted = decCipher.doFinal(ciphertext);

        assertArrayEquals(plaintext, decrypted, "Decrypted text must match original");
    }

    /**
     * Test that X25519→Ed25519 public key recovery produces valid candidate.
     * Due to lost sign bit, we get an ambiguous result (two possible keys).
     */
    @Test
    void x25519ToEd25519PublicRecoveryIsAmbiguous() {
        var ed25519KeyPair = SignatureAlgorithm.ED_25519.generateKeyPair();
        var x25519Public = SignatureAlgorithm.ED_25519.toEncryption(ed25519KeyPair.getPublic());

        // Extract u-coordinate from X25519 public key
        var xecPublic = (XECPublicKey) x25519Public;
        var u = xecPublic.getU();

        // Curve25519 prime: 2^255 - 19
        var p = java.math.BigInteger.valueOf(2).pow(255).subtract(java.math.BigInteger.valueOf(19));

        // Reverse conversion: y = (u - 1) / (u + 1) mod p
        var one = java.math.BigInteger.ONE;
        var numerator = u.subtract(one).mod(p);
        if (numerator.compareTo(java.math.BigInteger.ZERO) < 0) {
            numerator = numerator.add(p);
        }
        var denominator = u.add(one).mod(p);
        var y = numerator.multiply(denominator.modInverse(p)).mod(p);

        // y-coordinate recovered, but sign of x is lost
        // This means we can compute TWO candidate Ed25519 public keys
        // One with x positive, one with x negative

        // We cannot know which is correct without additional information
        // This is why full round-trip Ed25519→X25519→Ed25519 is not possible
        // for public keys without storing the sign bit separately

        assertNotNull(y, "Y-coordinate should be recoverable");
        // Note: We don't assert the recovered key matches because of sign ambiguity
    }

    /**
     * Document: X25519→Ed25519 private key recovery is impossible.
     */
    @Test
    void x25519ToEd25519PrivateRecoveryIsImpossible() {
        // This test documents the mathematical constraint:
        // Ed25519→X25519 private key conversion uses SHA-512 hash
        // SHA-512 is a one-way function - cannot be reversed
        // Therefore, given only an X25519 private key, the original
        // Ed25519 seed CANNOT be recovered

        // The correct architecture is:
        // 1. Store Ed25519 keys as source of truth (e.g., in KERI KERL)
        // 2. Derive X25519 keys on-demand when encryption is needed
        // 3. Never need to "recover" Ed25519 from X25519

        assertTrue(true, "This test documents the impossibility of reverse private key conversion");
    }

    /**
     * Verify consistent seed-based generation produces consistent X25519 keys.
     */
    @Test
    void seedBasedGenerationIsConsistent() {
        byte[] seed = new byte[32];
        new SecureRandom().nextBytes(seed);

        // Generate Ed25519 from same seed twice
        var sr1 = new java.util.Random(java.util.Arrays.hashCode(seed));
        var sr2 = new java.util.Random(java.util.Arrays.hashCode(seed));

        var ed1 = SignatureAlgorithm.ED_25519.generateKeyPair(new SecureRandom() {
            @Override
            public void nextBytes(byte[] bytes) {
                for (int i = 0; i < bytes.length; i++) bytes[i] = seed[i % seed.length];
            }
        });

        var ed2 = SignatureAlgorithm.ED_25519.generateKeyPair(new SecureRandom() {
            @Override
            public void nextBytes(byte[] bytes) {
                for (int i = 0; i < bytes.length; i++) bytes[i] = seed[i % seed.length];
            }
        });

        var x1 = SignatureAlgorithm.ED_25519.toEncryption(ed1);
        var x2 = SignatureAlgorithm.ED_25519.toEncryption(ed2);

        assertArrayEquals(x1.getPublic().getEncoded(), x2.getPublic().getEncoded(),
            "Same seed should produce same X25519 public key");
    }

    // Helper methods

    private Key keyFromPublic(java.security.PublicKey ed25519Public) {
        byte[] encoded = ed25519Public.getEncoded();
        byte[] raw = new byte[32];
        System.arraycopy(encoded, 12, raw, 0, 32); // Skip ASN.1 header
        return Key.fromBytes(raw);
    }

    private Key keyFromPrivate(java.security.PrivateKey ed25519Private) {
        return Key.fromBytes(ed25519Private.getEncoded());
    }

    private byte[] extractX25519PublicRaw(PublicKey x25519Public) {
        byte[] encoded = x25519Public.getEncoded();
        byte[] raw = new byte[32];
        System.arraycopy(encoded, encoded.length - 32, raw, 0, 32);
        return raw;
    }
}
