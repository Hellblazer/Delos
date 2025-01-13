package com.hellblazer.delos.cryptography;

import com.codevasp.lazysodium.LazySodium;
import com.codevasp.lazysodium.LazySodiumJava;
import com.codevasp.lazysodium.SodiumJava;
import com.codevasp.lazysodium.utils.Key;
import com.codevasp.lazysodium.utils.KeyPair;
import com.google.protobuf.ByteString;
import com.hellblazer.delos.utils.Entropy;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.edec.EdECObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.util.Arrays;
import org.bouncycastle.util.encoders.Hex;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author hal.hildebrand
 */
public class EdToXAndBackTest {
    public static final String AES_GCM_NO_PADDING = "AES/GCM/NoPadding";
    public static final String AES                = "AES";
    public static final int    TAG_LENGTH         = 128; // bits
    public static final int    IV_LENGTH          = 16; // bytes

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    public static byte[] decrypt(Encrypted encrypted, SecretKey secretKey) {
        try {
            final Cipher cipher = Cipher.getInstance(AES_GCM_NO_PADDING);
            AlgorithmParameterSpec gcmIv = new GCMParameterSpec(TAG_LENGTH, encrypted.iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmIv);

            if (encrypted.associatedData != null) {
                cipher.updateAAD(encrypted.associatedData);
            }
            return cipher.doFinal(encrypted.cipherText);
        } catch (Throwable t) {
            throw new IllegalStateException("Unable to decrypt", t);
        }
    }

    public static Encrypted encrypt(byte[] plaintext, SecretKey secretKey, byte[] associatedData) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            Entropy.nextSecureBytes(iv);
            final Cipher cipher = Cipher.getInstance(AES_GCM_NO_PADDING);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(TAG_LENGTH, iv); //128 bit auth associatedData length
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);

            if (associatedData != null) {
                cipher.updateAAD(associatedData);
            }

            return new Encrypted(cipher.doFinal(plaintext), iv, associatedData);
        } catch (Throwable t) {
            throw new IllegalStateException("Unable to encrypt", t);
        }
    }

    @Test
    public void shareRoundTrip() throws Exception {
        var algorithm = EncryptionAlgorithm.X_25519;
        var sessionKeyPair = algorithm.generateKeyPair(new SecureRandom());
        var share = "give me food or give me slack or kill me".getBytes(StandardCharsets.UTF_8);
        var wrapped = ByteString.copyFrom(share);
        var associatedData = "Hello world    ".getBytes();

        var encapsulated = EncryptionAlgorithm.DEFAULT.encapsulated(sessionKeyPair.getPublic());
        var secretKey = new SecretKeySpec(encapsulated.key().getEncoded(), "AES");
        var encrypted = encrypt(wrapped.toByteArray(), secretKey, associatedData);

        var secretKey2 = algorithm.decapsulate(sessionKeyPair.getPrivate(), encapsulated.encapsulation(), AES);
        var en = new Encrypted(encrypted.cipherText(), encrypted.iv(), associatedData);
        var decrypted = decrypt(en, secretKey2);
        assertArrayEquals(share, decrypted);
    }

    @Test
    public void smokin() throws Exception {
        var sigPair = SignatureAlgorithm.ED_25519.generateKeyPair();
        LazySodium lazySodium = new LazySodiumJava(new SodiumJava());
        KeyPair lsKeyPair = new KeyPair(from(sigPair.getPublic()), from(sigPair.getPrivate()));
        var lsEncryptionPair = lazySodium.convertKeyPairEd25519ToCurve25519(lsKeyPair);
        assertNotNull(lsEncryptionPair);

        var encryptionPair = new java.security.KeyPair(toX25519Public(lsEncryptionPair.getPublicKey()),
                                                       toX25519Private(lsEncryptionPair.getSecretKey()));

        var algorithm = EncryptionAlgorithm.X_25519;
//        var encryptionPair = algorithm.generateKeyPair(new SecureRandom());
        var share = "give me food or give me slack or kill me".getBytes(StandardCharsets.UTF_8);
        var wrapped = ByteString.copyFrom(share);
        var associatedData = "Hello world    ".getBytes();

        var encapsulated = algorithm.encapsulated(encryptionPair.getPublic());
        var secretKey = new SecretKeySpec(encapsulated.key().getEncoded(), "AES");
        var encrypted = encrypt(wrapped.toByteArray(), secretKey, associatedData);

        var secretKey2 = algorithm.decapsulate(encryptionPair.getPrivate(), encapsulated.encapsulation(), AES);
        var en = new Encrypted(encrypted.cipherText(), encrypted.iv(), associatedData);
        var decrypted = decrypt(en, secretKey2);
        assertArrayEquals(share, decrypted);
    }

    @Test
    public void testEd25519ToX25519() {
        checkEd25519ToX25519Vector("be5bf46a933c8703fa48d0c4075c8fe35fb5f2358778c62008d7265ea6eb0858",
                                   "188dedb57fb265624e370e214eba35799cd17897f1d44663530606a2ed5cb57f",
                                   "2038f67c3fcfc38429819229d4c874d1f22540ab1349949a766cca0846363f28",
                                   "Ed25519 to X25519 vector #1");
        checkEd25519ToX25519Vector("7013fabacfa4bd6eafb75e9d2d426a1f956ccd9acb19b615d3041d3e0b3000e6",
                                   "c0418dcd2fc1da92d6fb07c2ae4e0e4ddd71819533326047deab1c8c882e806f",
                                   "ec3e66a867e1f383dbcda7084569ffced6af071e85cb20791523347c59ec3459",
                                   "Ed25519 to X25519 vector #2");
        // This vector checks proper normalization of 'u' (X25519 public key) when
        // converting from Ed25519 public key
        checkEd25519ToX25519Vector("f81fe2c27e3884dfa6c3a288f37d0ff5699ddade04b6c7dbc379c68a7e8129a0",
                                   "e0ee579cf0e094f9aa2c2f87caf8a2e48843fca000325b45400189991c684564",
                                   "4d8f5ab537e51507965ed841c35cb896ef6c474f789188cd3dd86dfb769ac661",
                                   "Ed25519 to X25519 vector #3");
    }

    private void checkEd25519ToX25519Vector(String ed25519SK, String x25519SK, String x25519PK, String text) {
        byte[] esk = Hex.decode(ed25519SK);
        byte[] xsk = Hex.decode(x25519SK);
        byte[] xpk = Hex.decode(x25519PK);

        // Check Ed25519 secret key converts to expected X25519 secret key
        {
            byte[] converted = EdDSAOperations.toX25519PrivateKey(esk);
            assertTrue(Arrays.areEqual(xsk, converted), text);
        }

        // Derive X25519 public key from X25519 secret key and check
        {
            X25519PrivateKeyParameters x25519PrivateKeyParams = new X25519PrivateKeyParameters(xsk, 0);
            byte[] derived = x25519PrivateKeyParams.generatePublicKey().getEncoded();
            assertTrue(Arrays.areEqual(xpk, derived), text);
        }

        // Derive Ed25519 public key from Ed25519 secret key,
        // then convert Ed25519 public key to X25519 public key and check
        {
            Ed25519PrivateKeyParameters ed25519PrivateKeyParams = new Ed25519PrivateKeyParameters(esk, 0);
            byte[] derived = ed25519PrivateKeyParams.generatePublicKey().getEncoded();

            byte[] converted = EdDSAOperations.toX25519PublicKey(derived);
            assertTrue(Arrays.areEqual(xpk, converted), text);
        }
    }

    private Key from(java.security.PublicKey ed25519PublicKey) {
        byte[] ed25519PublicKeyBytes = new byte[32];
        System.arraycopy(ed25519PublicKey.getEncoded(), 12, ed25519PublicKeyBytes, 0, 32);
        return Key.fromBytes(ed25519PublicKeyBytes);
    }

    private Key from(java.security.PrivateKey ed25519PrivateKey) {
        return Key.fromBytes(ed25519PrivateKey.getEncoded());
    }

    private PrivateKey toX25519Private(Key privateKey) throws Exception {// Raw X25519 key to java.security.PrivateKey
        KeyFactory keyFactory = KeyFactory.getInstance("X25519");
        PrivateKeyInfo privateKeyInfo = new PrivateKeyInfo(new AlgorithmIdentifier(EdECObjectIdentifiers.id_X25519),
                                                           new DEROctetString(privateKey.getAsBytes()));
        PKCS8EncodedKeySpec pkcs8EncodedKeySpec = new PKCS8EncodedKeySpec(privateKeyInfo.getEncoded());
        return keyFactory.generatePrivate(pkcs8EncodedKeySpec);
    }

    private PublicKey toX25519Public(Key publicKey) throws Exception {
        KeyFactory keyFactory = KeyFactory.getInstance("X25519");
        SubjectPublicKeyInfo subjectPublicKeyInfo = new SubjectPublicKeyInfo(
        new AlgorithmIdentifier(EdECObjectIdentifiers.id_X25519), publicKey.getAsBytes());
        X509EncodedKeySpec x509EncodedKeySpec = new X509EncodedKeySpec(subjectPublicKeyInfo.getEncoded());
        return keyFactory.generatePublic(x509EncodedKeySpec);
    }

    public record Encrypted(byte[] cipherText, byte[] iv, byte[] associatedData) {
    }
}
