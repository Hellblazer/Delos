/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
/*
 * Portions copyright (c) 2025, Hal Hildebrand.
 * Modifications made under GNU Affero General Public License.
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 */
package com.hellblazer.delos.cryptography;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.cryptography.Verifier.DefaultVerifier;
import com.hellblazer.delos.utils.BbBackedInputStream;
import org.bouncycastle.crypto.params.X448PrivateKeyParameters;
import org.joou.ULong;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.*;
import java.security.interfaces.EdECPrivateKey;
import java.security.interfaces.EdECPublicKey;
import java.security.interfaces.XECPublicKey;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPrivateKeySpec;
import java.security.spec.XECPublicKeySpec;

import static com.hellblazer.delos.cryptography.EncryptionAlgorithm.XDH;
import static org.bouncycastle.jcajce.spec.XDHParameterSpec.X25519;
import static org.bouncycastle.jcajce.spec.XDHParameterSpec.X448;

/**
 * Ye Enumeration of ye olde thyme Signature alorithms.
 *
 * @author hal.hildebrand
 */
public enum SignatureAlgorithm {

    ED_25519 {
        private final EdDSAOperations ops = new EdDSAOperations(this);
        // Curve25519 prime: 2^255 - 19
        private static final BigInteger CURVE25519_PRIME = BigInteger.valueOf(2).pow(255)
                                                                     .subtract(BigInteger.valueOf(19));

        @Override
        public PrivateKey toEncryption(PrivateKey edPrivateKey) {
            try {
                final var edECPrivateKey = (EdECPrivateKey) edPrivateKey;
                // Use SHA-512 per RFC 8032 and libsodium, then clamp bits
                byte[] x25519Scalar = EdDSAOperations.toX25519PrivateKey(edECPrivateKey.getBytes().get());
                NamedParameterSpec paramSpec = new NamedParameterSpec(X25519);
                var kf = KeyFactory.getInstance(XDH);
                var privSpec = new XECPrivateKeySpec(paramSpec, x25519Scalar);
                return kf.generatePrivate(privSpec);
            } catch (Exception e) {
                throw new IllegalStateException("Unable to convert Ed25519 to X25519 private key", e);
            }
        }

        @Override
        public PublicKey toEncryption(PublicKey edPublicKey) {
            try {
                var point = ((EdECPublicKey) edPublicKey).getPoint();
                var y = point.getY();
                var one = BigInteger.ONE;
                // Birational map: u = (1 + y) / (1 - y) mod p
                // Must use modular arithmetic in finite field GF(2^255 - 19)
                var numerator = one.add(y).mod(CURVE25519_PRIME);
                var denominator = one.subtract(y).mod(CURVE25519_PRIME);
                // Handle negative modular result
                if (denominator.compareTo(BigInteger.ZERO) < 0) {
                    denominator = denominator.add(CURVE25519_PRIME);
                }
                var u = numerator.multiply(denominator.modInverse(CURVE25519_PRIME)).mod(CURVE25519_PRIME);

                NamedParameterSpec paramSpec = new NamedParameterSpec(X25519);
                var kf = KeyFactory.getInstance(XDH);
                var pubSpec = new XECPublicKeySpec(paramSpec, u);
                return kf.generatePublic(pubSpec);
            } catch (Exception e) {
                throw new IllegalStateException("Unable to convert Ed25519 to X25519 public key", e);
            }
        }

        @Override
        public String algorithmName() {
            return EDDSA_ALGORITHM_NAME;
        }

        @Override
        public String curveName() {
            return "ed25519";
        }

        @Override
        public byte[] encode(PublicKey publicKey) {
            return ops.encode(publicKey);
        }

        @Override
        public KeyPair generateKeyPair() {
            return ops.generateKeyPair();
        }

        @Override
        public KeyPair generateKeyPair(SecureRandom secureRandom) {
            return ops.generateKeyPair(secureRandom);
        }

        @Override
        public PublicKey publicKey(byte[] bytes) {
            return ops.publicKey(bytes);
        }

        @Override
        public int publicKeyLength() {
            return 32;
        }

        @Override
        public JohnHancock sign(ULong sequenceNumber, PrivateKey[] privateKeys, InputStream is) {
            return ops.sign(privateKeys, is, sequenceNumber);
        }

        @Override
        public JohnHancock signature(ULong sequenceNumber, byte[] signatureBytes) {
            return ops.signature(signatureBytes, sequenceNumber);
        }

        @Override
        public byte signatureCode() {
            return 2;
        }

        @Override
        public String signatureInstanceName() {
            return "ED25519";
        }

        @Override
        public int signatureLength() {
            return 64;
        }

        @Override
        public String toString() {
            return ops.toString();
        }

        @Override
        protected boolean verify(PublicKey publicKey, byte[] bytes, InputStream message) {
            return ops.verify(publicKey, bytes, message);
        }

    },

    ED_448 {
        private final EdDSAOperations ops = new EdDSAOperations(this);

        @Override
        public PrivateKey toEncryption(PrivateKey edPrivateKey) {
            try {
                final var edECPrivateKey = (EdECPrivateKey) edPrivateKey;
                // Use SHAKE256 per RFC 8032, then apply Ed448 scalar pruning
                byte[] x448Scalar = EdDSAOperations.toX448PrivateKey(edECPrivateKey.getBytes().get());
                NamedParameterSpec paramSpec = new NamedParameterSpec(X448);
                var kf = KeyFactory.getInstance(XDH);
                var privSpec = new XECPrivateKeySpec(paramSpec, x448Scalar);
                return kf.generatePrivate(privSpec);
            } catch (Exception e) {
                throw new IllegalStateException("Unable to convert Ed448 to X448 private key", e);
            }
        }

        @Override
        public PublicKey toEncryption(PublicKey edPublicKey) {
            // Note: Ed448→X448 public key conversion requires that we have
            // access to the corresponding private key. Unlike Ed25519→X25519,
            // the direct birational map from Ed448 public to X448 public
            // doesn't work due to curve differences.
            //
            // For Ed448, we must use the toEncryption(KeyPair) method
            // to get a consistent X448 key pair.
            throw new UnsupportedOperationException(
                "Ed448→X448 public key conversion requires the private key. " +
                "Use toEncryption(KeyPair) instead of toEncryption(PublicKey).");
        }

        @Override
        public KeyPair toEncryption(KeyPair edKeyPair) {
            try {
                final var edECPrivateKey = (EdECPrivateKey) edKeyPair.getPrivate();
                // Use SHAKE256 per RFC 8032, then apply Ed448 scalar pruning
                byte[] x448Scalar = EdDSAOperations.toX448PrivateKey(edECPrivateKey.getBytes().get());

                // Use BouncyCastle to derive the corresponding X448 public key
                var x448PrivateParams = new X448PrivateKeyParameters(x448Scalar, 0);
                var x448PublicParams = x448PrivateParams.generatePublicKey();
                byte[] x448PublicRaw = x448PublicParams.getEncoded();

                // Convert to JDK key types
                NamedParameterSpec paramSpec = new NamedParameterSpec(X448);
                var kf = KeyFactory.getInstance(XDH);

                var privSpec = new XECPrivateKeySpec(paramSpec, x448Scalar);
                var privateKey = kf.generatePrivate(privSpec);

                // Convert little-endian X448 public to BigInteger for XECPublicKeySpec
                byte[] reversed = new byte[x448PublicRaw.length + 1];
                reversed[0] = 0; // Ensure positive
                for (int i = 0; i < x448PublicRaw.length; i++) {
                    reversed[x448PublicRaw.length - i] = x448PublicRaw[i];
                }
                var u = new BigInteger(reversed);

                var pubSpec = new XECPublicKeySpec(paramSpec, u);
                var publicKey = kf.generatePublic(pubSpec);

                return new KeyPair(publicKey, privateKey);
            } catch (Exception e) {
                throw new IllegalStateException("Unable to convert Ed448 to X448 key pair", e);
            }
        }

        @Override
        public String algorithmName() {
            return EDDSA_ALGORITHM_NAME;
        }

        @Override
        public String curveName() {
            return "ed448";
        }

        @Override
        public byte[] encode(PublicKey publicKey) {
            return ops.encode(publicKey);
        }

        @Override
        public KeyPair generateKeyPair() {
            return ops.generateKeyPair();
        }

        @Override
        public KeyPair generateKeyPair(SecureRandom secureRandom) {
            return ops.generateKeyPair(secureRandom);
        }

        @Override
        public PublicKey publicKey(byte[] bytes) {
            return ops.publicKey(bytes);
        }

        @Override
        public int publicKeyLength() {
            return 57;
        }

        @Override
        public JohnHancock sign(ULong sequenceNumber, PrivateKey[] privateKeys, InputStream is) {
            return ops.sign(privateKeys, is, sequenceNumber);
        }

        @Override
        public JohnHancock signature(ULong sequenceNumber, byte[] signatureBytes) {
            return ops.signature(signatureBytes, sequenceNumber);
        }

        @Override
        public byte signatureCode() {
            return 3;
        }

        @Override
        public String signatureInstanceName() {
            return "ED448";
        }

        @Override
        public int signatureLength() {
            return 114;
        }

        @Override
        public String toString() {
            return ops.toString();
        }

        @Override
        protected boolean verify(PublicKey publicKey, byte[] bytes, InputStream message) {
            return ops.verify(publicKey, bytes, message);
        }

    }, BLS_12_381 {
        @Override
        public PrivateKey toEncryption(PrivateKey edPrivateKey) {
            throw new UnsupportedOperationException("BLS keys cannot be converted to encryption keys");
        }

        @Override
        public PublicKey toEncryption(PublicKey edPublicKey) {
            throw new UnsupportedOperationException("BLS keys cannot be converted to encryption keys");
        }

        @Override
        public String algorithmName() {
            return "BLS12-381";
        }

        @Override
        public String curveName() {
            return "bls12-381";
        }

        @Override
        public byte[] encode(PublicKey publicKey) {
            throw new UnsupportedOperationException("BLS encoding not supported via this method. Use BLSPublicKey.toPubKey()");
        }

        @Override
        public KeyPair generateKeyPair() {
            throw new UnsupportedOperationException("BLS key generation not supported via this method. Use BLSOperations.generateKeyPair()");
        }

        @Override
        public KeyPair generateKeyPair(SecureRandom secureRandom) {
            throw new UnsupportedOperationException("BLS key generation not supported via this method. Use BLSOperations.generateKeyPair()");
        }

        @Override
        public PublicKey publicKey(byte[] bytes) {
            throw new UnsupportedOperationException("BLS public key deserialization not supported via this method. Use BLSPublicKey.fromPubKey()");
        }

        @Override
        public int publicKeyLength() {
            return 48; // G1 point compressed (minimal-pubkey-size variant)
        }

        @Override
        public JohnHancock sign(ULong sequenceNumber, PrivateKey[] privateKeys, InputStream is) {
            throw new UnsupportedOperationException("BLS signing not supported via this method. Use BLSOperations.sign()");
        }

        @Override
        public JohnHancock signature(ULong sequenceNumber, byte[] signatureBytes) {
            throw new UnsupportedOperationException("BLS signature not supported via this method. Use BLSSignature");
        }

        @Override
        public byte signatureCode() {
            return 4; // BLS uses code 4 to avoid conflict with ED_448 (code 3)
        }

        @Override
        public String signatureInstanceName() {
            return "BLS12-381";
        }

        @Override
        public int signatureLength() {
            return 96; // G2 point compressed (minimal-pubkey-size variant)
        }

        @Override
        protected boolean verify(PublicKey publicKey, byte[] signature, InputStream message) {
            throw new UnsupportedOperationException("BLS verification not supported via this method. Use BLSOperations.verify()");
        }

        /**
         * BLS supports signature aggregation, unlike EdDSA variants.
         *
         * @return true for BLS_12_381
         */
        public boolean supportsAggregation() {
            return true;
        }

    }, NULL_SIGNATURE {
        @Override
        public PrivateKey toEncryption(PrivateKey edPrivateKey) {
            throw new UnsupportedOperationException("Not valid for NULL signature algorithm");
        }

        @Override
        public PublicKey toEncryption(PublicKey edPublicKey) {
            throw new UnsupportedOperationException("Not valid for NULL signature algorithm");
        }

        @Override
        public String algorithmName() {
            return "Null Algorithm";
        }

        @Override
        public String curveName() {
            return "Null";
        }

        @Override
        public byte[] encode(PublicKey publicKey) {
            return new byte[0];
        }

        @Override
        public KeyPair generateKeyPair() {
            return null;
        }

        @Override
        public KeyPair generateKeyPair(SecureRandom secureRandom) {
            return null;
        }

        @Override
        public PublicKey publicKey(byte[] bytes) {
            return null;
        }

        @Override
        public int publicKeyLength() {
            return 0;
        }

        @Override
        public JohnHancock signature(ULong sequenceNumber, byte[] signatureBytes) {
            return new JohnHancock(NULL_SIGNATURE, signatureBytes, sequenceNumber);
        }

        @Override
        public byte signatureCode() {
            return 1;
        }

        @Override
        public String signatureInstanceName() {
            return "Null Algorithm";
        }

        @Override
        public int signatureLength() {
            return 64;
        }

        @Override
        protected boolean verify(PublicKey publicKey, byte[] signature, InputStream message) {
            return false;
        }

        @Override
        JohnHancock sign(ULong sequenceNumber, PrivateKey[] privateKeys, InputStream message) {
            return new JohnHancock(NULL_SIGNATURE, new byte[64], sequenceNumber);
        }

    };

    public static final  SignatureAlgorithm DEFAULT              = ED_25519;
    private static final String             EDDSA_ALGORITHM_NAME = "EdDSA";

    static {
        Security.setProperty("crypto.policy", "unlimited");
    }

    public static SignatureAlgorithm fromSignatureCode(int i) {
        return switch (i) {
            case 0:
                yield NULL_SIGNATURE;
            case 1:
                yield NULL_SIGNATURE;
            case 2:
                yield ED_25519;
            case 3:
                yield ED_448;
            case 4:
                yield BLS_12_381;
            default:
                throw new IllegalArgumentException("Unknown signature code: " + i);
        };
    }

    public static SignatureAlgorithm lookup(PrivateKey privateKey) {
        return switch (privateKey.getAlgorithm()) {
            case "EdDSA" -> lookupEd(((EdECPrivateKey) privateKey).getParams());
            case "Ed25519" -> ED_25519;
            case "Ed448" -> ED_448;
            default -> throw new IllegalArgumentException("Unknown algorithm: " + privateKey.getAlgorithm());
        };
    }

    public static SignatureAlgorithm lookup(PublicKey publicKey) {
        return switch (publicKey.getAlgorithm()) {
            case "EdDSA" -> lookupEd(((EdECPublicKey) publicKey).getParams());
            case "Ed25519" -> ED_25519;
            case "Ed448" -> ED_448;
            default -> throw new IllegalArgumentException("Unknown algorithm: " + publicKey.getAlgorithm());
        };
    }

    private static SignatureAlgorithm lookupEd(NamedParameterSpec params) {
        var curveName = params.getName();
        return switch (curveName.toLowerCase()) {
            case "ed25519" -> ED_25519;
            case "ed448" -> ED_448;
            default -> throw new IllegalArgumentException("Unknown edwards curve: " + curveName);
        };
    }

    abstract public String algorithmName();

    abstract public String curveName();

    abstract public byte[] encode(PublicKey publicKey);

    abstract public KeyPair generateKeyPair();

    abstract public KeyPair generateKeyPair(SecureRandom secureRandom);

    public JohnHancock nullSignature() {
        return JohnHancock.nullSignature(this);
    }

    abstract public PublicKey publicKey(byte[] bytes);

    abstract public int publicKeyLength();

    final public JohnHancock sign(ULong sequenceNumber, PrivateKey privateKey, byte[]... message) {
        return sign(sequenceNumber, new PrivateKey[] { privateKey }, BbBackedInputStream.aggregate(message));
    }

    final public JohnHancock sign(ULong sequenceNumber, PrivateKey privateKey, ByteBuffer... buffers) {
        return sign(sequenceNumber, new PrivateKey[] { privateKey }, BbBackedInputStream.aggregate(buffers));
    }

    final public JohnHancock sign(ULong sequenceNumber, PrivateKey privateKey, ByteString... buffers) {
        return sign(sequenceNumber, new PrivateKey[] { privateKey }, BbBackedInputStream.aggregate(buffers));
    }

    abstract public JohnHancock signature(ULong sequenceNumber, byte[] signatureBytes);

    abstract public byte signatureCode();

    abstract public String signatureInstanceName();

    abstract public int signatureLength();

    /**
     * Indicates whether this signature algorithm supports signature aggregation.
     * <p>
     * Only BLS-12-381 supports aggregation; EdDSA variants do not.
     *
     * @return true if aggregation is supported, false otherwise
     */
    public boolean supportsAggregation() {
        return false; // Default: EdDSA variants don't support aggregation
    }

    /**
     * Convert the Ed* public/private signature keys into the equivalent X* public/private encryption key. See
     * <a href="https://eprint.iacr.org/2021/509.pdf">On using the same key pair for
     * Ed25519 and an X25519 based KEM</a>.
     *
     * @param edKeyPair
     * @return
     */
    public KeyPair toEncryption(KeyPair edKeyPair) {
        return new KeyPair(toEncryption(edKeyPair.getPublic()), toEncryption(edKeyPair.getPrivate()));
    }

    abstract public PrivateKey toEncryption(PrivateKey edPrivateKey);

    abstract public PublicKey toEncryption(PublicKey edPublicKey);

    final public boolean verify(PublicKey publicKey, JohnHancock signature, byte[]... message) {
        return verify(publicKey, signature, BbBackedInputStream.aggregate(message));
    }

    final public boolean verify(PublicKey publicKey, JohnHancock signature, ByteBuffer... message) {
        return verify(publicKey, signature, BbBackedInputStream.aggregate(message));
    }

    final public boolean verify(PublicKey publicKey, JohnHancock signature, ByteString... message) {
        return verify(publicKey, signature, BbBackedInputStream.aggregate(message));
    }

    abstract JohnHancock sign(ULong sequenceNumber, PrivateKey[] privateKeys, InputStream message);

    final boolean verify(PublicKey publicKey, JohnHancock signature, InputStream message) {
        return new DefaultVerifier(new PublicKey[] { publicKey }).verify(SigningThreshold.unweighted(1), signature,
                                                                         message);
    }

    abstract protected boolean verify(PublicKey publicKey, byte[] signature, InputStream message);
}
