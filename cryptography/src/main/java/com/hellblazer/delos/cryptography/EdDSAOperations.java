/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.cryptography;

import com.codevasp.lazysodium.LazySodium;
import com.codevasp.lazysodium.LazySodiumJava;
import com.codevasp.lazysodium.SodiumJava;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.edec.EdECObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.math.ec.rfc7748.X25519;
import org.bouncycastle.math.ec.rfc7748.X25519Field;
import org.bouncycastle.math.raw.Nat256;
import org.joou.ULong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.NamedParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

/**
 * common operations and state per algorithm.
 *
 * @author hal.hildebrand
 */
public class EdDSAOperations {

    public static final String EDDSA_ALGORITHM_NAME = "EdDSA";

    private static final ThreadLocal<Signature> SIGNATURE_CACHE = new ThreadLocal<>() {

        @Override
        protected Signature initialValue() {
            try {
                return Signature.getInstance(EDDSA_ALGORITHM_NAME);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("Unable to retrieve sig algo: " + EDDSA_ALGORITHM_NAME, e);
            }
        }
    };
    private static final Logger                 log             = LoggerFactory.getLogger(EdDSAOperations.class);
    private static final int                    POINT_BYTES     = 32;
    private static final int[]                  P               = new int[] { 0xFFFFFFED, 0xFFFFFFFF, 0xFFFFFFFF,
                                                                              0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF,
                                                                              0xFFFFFFFF, 0x7FFFFFFF };
    private static final int[]                  C_d             = new int[] { 0x035978A3, 0x02D37284, 0x018AB75E,
                                                                              0x026A0A0E, 0x0000E014, 0x0379E898,
                                                                              0x01D01E5D, 0x01E738CC, 0x03715B7F,
                                                                              0x00A406D9 };
    private final        ASN1ObjectIdentifier   curveId;
    private final        KeyFactory             keyFactory;
    private final        KeyPairGenerator       keyPairGenerator;
    private final        NamedParameterSpec     parameterSpec;
    private final        SignatureAlgorithm     signatureAlgorithm;

    public EdDSAOperations(SignatureAlgorithm signatureAlgorithm) {
        try {
            this.signatureAlgorithm = signatureAlgorithm;

            var curveName = signatureAlgorithm.curveName().toLowerCase();
            parameterSpec = switch (curveName) {
                case "ed25519" -> NamedParameterSpec.ED25519;
                case "ed448" -> NamedParameterSpec.ED448;
                default -> throw new RuntimeException("Unknown Edwards curve: " + curveName);
            };
            curveId = switch (curveName) {
                case "ed25519" -> EdECObjectIdentifiers.id_Ed25519;
                case "ed448" -> EdECObjectIdentifiers.id_Ed448;
                default -> throw new RuntimeException("Unknown Edwards curve: " + signatureAlgorithm);
            };

            keyPairGenerator = KeyPairGenerator.getInstance(EDDSA_ALGORITHM_NAME);
            keyPairGenerator.initialize(parameterSpec);
            keyFactory = KeyFactory.getInstance(EDDSA_ALGORITHM_NAME);
        } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException e) {
            throw new IllegalStateException("Unable to initialize", e);
        }
    }

    public static void reverse(byte[] arr) {
        var i = 0;
        var j = arr.length - 1;

        while (i < j) {
            swap(arr, i, j);
            i++;
            j--;
        }
    }

    private static Digest createPrehash() {
        return new SHA512Digest();
    }

    public static byte[] toX25519PrivateKey(byte[] ed25519PrivateKey) {
        Digest d = createPrehash();
        byte[] h = new byte[d.getDigestSize()];

        d.update(ed25519PrivateKey, 0, ed25519PrivateKey.length);
        d.doFinal(h, 0);

        byte[] s = new byte[X25519.SCALAR_SIZE];

        System.arraycopy(h, 0, s, 0, X25519.SCALAR_SIZE);
        s[0] &= 0xF8;
        s[X25519.SCALAR_SIZE - 1] &= 0x7F;
        s[X25519.SCALAR_SIZE - 1] |= 0x40;

        return s;
    }

    private static void decode32(byte[] bs, int bsOff, int[] n, int nOff, int nLen) {
        for (int i = 0; i < nLen; ++i) {
            n[nOff + i] = decode32(bs, bsOff + i * 4);
        }
    }

    private static int decode32(byte[] bs, int off) {
        int n = bs[off] & 0xFF;
        n |= (bs[++off] & 0xFF) << 8;
        n |= (bs[++off] & 0xFF) << 16;
        n |= bs[++off] << 24;
        return n;
    }

    private static boolean checkPointVar(byte[] p) {
        int[] t = new int[8];
        decode32(p, 0, t, 0, 8);
        t[7] &= 0x7FFFFFFF;
        return !Nat256.gte(t, P);
    }

    private static boolean decodePointVar(byte[] p, int pOff, boolean negate, PointAffine r) {
        byte[] py = org.bouncycastle.util.Arrays.copyOfRange(p, pOff, pOff + POINT_BYTES);
        if (!checkPointVar(py)) {
            return false;
        }

        int x_0 = (py[POINT_BYTES - 1] & 0x80) >>> 7;
        py[POINT_BYTES - 1] &= 0x7F;

        X25519Field.decode(py, 0, r.y);

        int[] u = X25519Field.create();
        int[] v = X25519Field.create();

        X25519Field.sqr(r.y, u);
        X25519Field.mul(C_d, u, v);
        X25519Field.subOne(u);
        X25519Field.addOne(v);

        if (!X25519Field.sqrtRatioVar(u, v, r.x)) {
            return false;
        }

        X25519Field.normalize(r.x);
        if (x_0 == 1 && X25519Field.isZeroVar(r.x)) {
            return false;
        }

        if (negate ^ (x_0 != (r.x[0] & 1))) {
            X25519Field.negate(r.x, r.x);
        }

        return true;
    }

    private static int[] obtainYFromPublicKey(byte[] ed25519PublicKey) {
        PointAffine pA = new PointAffine();

        boolean result = decodePointVar(ed25519PublicKey, 0, true, pA);
        if (!result)
            return null;

        return pA.y;
    }

    public static PublicKey ed25519ToX25519(PublicKey ed25519PublicKey)
    throws NoSuchAlgorithmException, NoSuchProviderException, InvalidKeySpecException {
        byte[] x25519PublicKeyBytes = new byte[32];
        LazySodium lazySodium = new LazySodiumJava(new SodiumJava());
        byte[] ed25519PublicKeyBytes = new byte[32];
        System.arraycopy(ed25519PublicKey.getEncoded(), 12, ed25519PublicKeyBytes, 0, 32);
        boolean conversion_success = lazySodium.convertPublicKeyEd25519ToCurve25519(x25519PublicKeyBytes,
                                                                                    ed25519PublicKeyBytes);
        if (!conversion_success) {
            System.out.println("Conversion failed!");
            System.exit(1);
        }

        byte[] x509Header = new byte[] { 0x30, 0x2a, // SEQUENCE, length 42
                                         0x30, 0x05, // SEQUENCE, length 5
                                         0x06, 0x03, 0x2b, 0x65, 0x6e, // OID for X25519
                                         0x03, 0x21, 0x00 // BIT STRING, length 33
        };
        // Combine the header and the key bytes
        byte[] x509EncodedKey = new byte[x509Header.length + x25519PublicKeyBytes.length];
        System.arraycopy(x509Header, 0, x509EncodedKey, 0, x509Header.length);
        System.arraycopy(x25519PublicKeyBytes, 0, x509EncodedKey, x509Header.length, x25519PublicKeyBytes.length);

        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(x509EncodedKey);
        KeyFactory keyFactory = KeyFactory.getInstance("X25519");
        PublicKey x25519PublicKey = keyFactory.generatePublic(keySpec);

        return x25519PublicKey;
    }

    public static byte[] toX25519PublicKey(byte[] ed25519PublicKey) {
        int[] one = new int[X25519Field.SIZE];
        X25519Field.one(one);

        int[] y = obtainYFromPublicKey(ed25519PublicKey);
        if (y == null)
            return null;

        int[] oneMinusY = new int[X25519Field.SIZE];
        X25519Field.sub(one, y, oneMinusY);

        int[] onePlusY = new int[X25519Field.SIZE];
        X25519Field.add(one, y, onePlusY);

        int[] oneMinusYInverted = new int[X25519Field.SIZE];
        X25519Field.inv(oneMinusY, oneMinusYInverted);

        int[] u = new int[X25519Field.SIZE];
        X25519Field.mul(onePlusY, oneMinusYInverted, u);

        X25519Field.normalize(u);

        byte[] x25519PublicKey = new byte[X25519.SCALAR_SIZE];
        X25519Field.encode(u, x25519PublicKey, 0);

        return x25519PublicKey;
    }

    public static void swap(byte[] arr, int i, int j) {
        var tmp = arr[i];
        arr[i] = arr[j];
        arr[j] = tmp;
    }

    public static X25519PrivateKeyParameters ToX25519PrivateKey(Ed25519PrivateKeyParameters key) {
        byte[] data = key.getEncoded();
        data[0] &= 248;
        data[31] &= 127;
        data[31] |= 64;
        return new X25519PrivateKeyParameters(data);
    }

    public byte[] encode(PublicKey publicKey) {
        var point = ((EdECPublicKey) publicKey).getPoint();
        var encodedPoint = point.getY().toByteArray();

        reverse(encodedPoint);
        encodedPoint = Arrays.copyOf(encodedPoint, publicKeyLength());
        var msb = (byte) (point.isXOdd() ? 0x80 : 0);
        encodedPoint[encodedPoint.length - 1] |= msb;

        return encodedPoint;
    }

    public KeyPair generateKeyPair() {
        return keyPairGenerator.generateKeyPair();
    }

    public KeyPair generateKeyPair(SecureRandom secureRandom) {
        try {
            var kpg = KeyPairGenerator.getInstance(EDDSA_ALGORITHM_NAME);
            kpg.initialize(parameterSpec, secureRandom);
            return kpg.generateKeyPair();
        } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException e) {
            throw new IllegalArgumentException("Cannot generate key pair", e);
        }
    }

    public BigInteger getY(PublicKey publicKey) {
        if (publicKey instanceof ECPublicKey ecPublicKey) {
            return ecPublicKey.getW().getAffineY();
        }
        throw new IllegalArgumentException("Public key must be ECPublicKey");
    }

    public PublicKey publicKey(byte[] bytes) {
        var pubKeyInfo = new SubjectPublicKeyInfo(new AlgorithmIdentifier(curveId), bytes);
        X509EncodedKeySpec x509KeySpec;
        try {
            x509KeySpec = new X509EncodedKeySpec(pubKeyInfo.getEncoded());
        } catch (IOException e1) {
            throw new IllegalArgumentException(e1);
        }

        try {
            return keyFactory.generatePublic(x509KeySpec);
        } catch (InvalidKeySpecException e1) {
            throw new IllegalArgumentException(e1);
        }
    }

    public JohnHancock sign(PrivateKey[] privateKeys, InputStream is, ULong sequenceNumber) {
        byte[][] signatures = new byte[privateKeys.length][];
        try {
            int i = 0;
            var sig = SIGNATURE_CACHE.get();
            for (PrivateKey privateKey : privateKeys) {
                sig.initSign(privateKey);
                byte[] buf = new byte[1024];
                try {
                    for (int read = is.read(buf); read > 0; read = is.read(buf)) {
                        sig.update(buf, 0, read);
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("Io error", e);
                }
                signatures[i] = sig.sign();
                i++;
            }
            return new JohnHancock(signatureAlgorithm, signatures, sequenceNumber);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Cannot sign", e);
        }
    }

    public JohnHancock signature(byte[] signatureBytes, ULong sequenceNumber) {
        return new JohnHancock(signatureAlgorithm, signatureBytes, sequenceNumber);
    }

    public boolean verify(PublicKey publicKey, byte[] bytes, InputStream is) {
        try {
            var sig = SIGNATURE_CACHE.get();
            sig.initVerify(publicKey);
            byte[] buf = new byte[1024];
            try {
                for (int read = is.read(buf); read > 0; read = is.read(buf)) {
                    sig.update(buf, 0, read);
                }
            } catch (IOException e) {
                log.error("Unexpected error", e);
                throw new IllegalStateException("Io error", e);
            }
            return sig.verify(bytes);
        } catch (GeneralSecurityException e) {
            log.error("Unexpected error", e);
            throw new RuntimeException(e);
        } catch (Throwable t) {
            log.error("Unexpected error", t);
            throw new RuntimeException(t);
        }
    }

    private int publicKeyLength() {
        return signatureAlgorithm.publicKeyLength();
    }

    private static class PointAffine {
        int[] x = X25519Field.create();
        int[] y = X25519Field.create();
    }

}
