package com.hellblazer.delos.cryptography.bls.impl;

import com.hellblazer.delos.cryptography.bls.BLSProvider;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.Bytes48;
import tech.pegasys.teku.bls.BLS;
import tech.pegasys.teku.bls.BLSKeyPair;
import tech.pegasys.teku.bls.BLSPublicKey;
import tech.pegasys.teku.bls.BLSSecretKey;
import tech.pegasys.teku.bls.BLSSignature;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * BLS12-381 provider implementation using tech.pegasys.teku:bls library.
 * <p>
 * This implementation wraps the Teku BLS library for all cryptographic operations.
 * All operations are thread-safe and validate inputs defensively.
 * <p>
 * Phase 4 implementation - wrappers around teku:bls JNI library.
 */
public class TekuBLSProvider implements BLSProvider {

    // ===== Task 4.2: Key Generation (GREEN phase) =====

    @Override
    public KeyPair generateKeyPair(Random random) {
        if (random == null) {
            throw new NullPointerException("random cannot be null");
        }

        // Wrap Random in SecureRandom adapter for compatibility with teku BLSKeyPair.random()
        var secureRandom = new SecureRandomAdapter(random);
        var tekuKeyPair = BLSKeyPair.random(secureRandom);

        // Extract byte representations
        var secretKeyOut = tekuKeyPair.getSecretKey().toBytes().toArrayUnsafe();
        var publicKeyOut = tekuKeyPair.getPublicKey().toBytesCompressed().toArrayUnsafe();

        return new KeyPair(secretKeyOut, publicKeyOut);
    }

    /**
     * Adapter to wrap java.util.Random as java.security.SecureRandom for teku library compatibility.
     * This maintains determinism when using seeded Random for testing.
     */
    private static class SecureRandomAdapter extends SecureRandom {
        private final Random delegate;

        SecureRandomAdapter(Random delegate) {
            this.delegate = delegate;
        }

        @Override
        public void nextBytes(byte[] bytes) {
            delegate.nextBytes(bytes);
        }

        @Override
        public int nextInt() {
            return delegate.nextInt();
        }

        @Override
        public long nextLong() {
            return delegate.nextLong();
        }
    }

    // ===== Task 4.4: Signing and Verification (GREEN phase) =====

    @Override
    public byte[] sign(byte[] secretKey, byte[] message) {
        if (secretKey == null) {
            throw new NullPointerException("secretKey cannot be null");
        }
        if (message == null) {
            throw new NullPointerException("message cannot be null");
        }
        if (secretKey.length != 32) {
            throw new IllegalArgumentException("Secret key must be 32 bytes, got: " + secretKey.length);
        }

        var blsSecretKey = BLSSecretKey.fromBytes(Bytes32.wrap(secretKey));
        var blsSignature = BLS.sign(blsSecretKey, Bytes.wrap(message));

        return blsSignature.toBytesCompressed().toArrayUnsafe();
    }

    @Override
    public boolean verify(byte[] publicKey, byte[] message, byte[] signature) {
        if (publicKey == null) {
            throw new NullPointerException("publicKey cannot be null");
        }
        if (message == null) {
            throw new NullPointerException("message cannot be null");
        }
        if (signature == null) {
            throw new NullPointerException("signature cannot be null");
        }
        if (publicKey.length != 48) {
            throw new IllegalArgumentException("Public key must be 48 bytes, got: " + publicKey.length);
        }
        if (signature.length != 96) {
            throw new IllegalArgumentException("Signature must be 96 bytes, got: " + signature.length);
        }

        try {
            var blsPublicKey = BLSPublicKey.fromBytesCompressed(Bytes48.wrap(publicKey));
            var blsSignature = BLSSignature.fromBytesCompressed(Bytes.wrap(signature));
            return BLS.verify(blsPublicKey, Bytes.wrap(message), blsSignature);
        } catch (Exception e) {
            // Invalid public key or signature format
            return false;
        }
    }

    // ===== Task 4.6: Aggregation (GREEN phase) =====

    @Override
    public byte[] aggregateSignatures(List<byte[]> signatures) {
        if (signatures == null) {
            throw new NullPointerException("signatures cannot be null");
        }
        if (signatures.isEmpty()) {
            throw new IllegalArgumentException("signatures list cannot be empty");
        }

        var blsSignatures = new ArrayList<BLSSignature>();
        for (var sig : signatures) {
            if (sig == null) {
                throw new NullPointerException("signature in list cannot be null");
            }
            if (sig.length != 96) {
                throw new IllegalArgumentException("Each signature must be 96 bytes, got: " + sig.length);
            }
            blsSignatures.add(BLSSignature.fromBytesCompressed(Bytes.wrap(sig)));
        }

        var aggregate = BLS.aggregate(blsSignatures);
        return aggregate.toBytesCompressed().toArrayUnsafe();
    }

    @Override
    public boolean verifyAggregate(List<byte[]> publicKeys, byte[] message, byte[] aggregateSignature) {
        if (publicKeys == null) {
            throw new NullPointerException("publicKeys cannot be null");
        }
        if (message == null) {
            throw new NullPointerException("message cannot be null");
        }
        if (aggregateSignature == null) {
            throw new NullPointerException("aggregateSignature cannot be null");
        }
        if (publicKeys.isEmpty()) {
            throw new IllegalArgumentException("publicKeys list cannot be empty");
        }
        if (aggregateSignature.length != 96) {
            throw new IllegalArgumentException("Aggregate signature must be 96 bytes, got: " + aggregateSignature.length);
        }

        try {
            var blsPublicKeys = new ArrayList<BLSPublicKey>();
            for (var pubKey : publicKeys) {
                if (pubKey == null) {
                    throw new NullPointerException("public key in list cannot be null");
                }
                if (pubKey.length != 48) {
                    throw new IllegalArgumentException("Each public key must be 48 bytes, got: " + pubKey.length);
                }
                blsPublicKeys.add(BLSPublicKey.fromBytesCompressed(Bytes48.wrap(pubKey)));
            }

            var blsSignature = BLSSignature.fromBytesCompressed(Bytes.wrap(aggregateSignature));
            return BLS.fastAggregateVerify(blsPublicKeys, Bytes.wrap(message), blsSignature);
        } catch (Exception e) {
            // Invalid public key or signature format
            return false;
        }
    }

    // ===== Task 4.10: Batch Verification (GREEN phase) =====

    @Override
    public boolean batchVerify(List<byte[]> publicKeys, List<byte[]> messages, List<byte[]> signatures) {
        if (publicKeys == null) {
            throw new NullPointerException("publicKeys cannot be null");
        }
        if (messages == null) {
            throw new NullPointerException("messages cannot be null");
        }
        if (signatures == null) {
            throw new NullPointerException("signatures cannot be null");
        }
        if (publicKeys.size() != messages.size() || messages.size() != signatures.size()) {
            throw new IllegalArgumentException(
                "List sizes must match: publicKeys=" + publicKeys.size() +
                ", messages=" + messages.size() +
                ", signatures=" + signatures.size()
            );
        }

        try {
            var blsPublicKeyLists = new ArrayList<List<BLSPublicKey>>();
            var blsMessages = new ArrayList<Bytes>();
            var blsSignatures = new ArrayList<BLSSignature>();

            for (int i = 0; i < publicKeys.size(); i++) {
                var pubKey = publicKeys.get(i);
                var message = messages.get(i);
                var signature = signatures.get(i);

                if (pubKey == null || message == null || signature == null) {
                    throw new NullPointerException("List elements cannot be null");
                }
                if (pubKey.length != 48) {
                    throw new IllegalArgumentException("Public key at index " + i + " must be 48 bytes");
                }
                if (signature.length != 96) {
                    throw new IllegalArgumentException("Signature at index " + i + " must be 96 bytes");
                }

                // Each signature corresponds to a single public key, so wrap in a singleton list
                blsPublicKeyLists.add(List.of(BLSPublicKey.fromBytesCompressed(Bytes48.wrap(pubKey))));
                blsMessages.add(Bytes.wrap(message));
                blsSignatures.add(BLSSignature.fromBytesCompressed(Bytes.wrap(signature)));
            }

            return BLS.batchVerify(blsPublicKeyLists, blsMessages, blsSignatures);
        } catch (Exception e) {
            // Invalid format or verification failure
            return false;
        }
    }
}
