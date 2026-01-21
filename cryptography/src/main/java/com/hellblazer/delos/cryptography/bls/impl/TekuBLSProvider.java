/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.cryptography.bls.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.hellblazer.delos.cryptography.bls.BLSProvider;
import com.hellblazer.delos.cryptography.bls.ParsedBLSKey;
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
import java.util.concurrent.TimeUnit;

/**
 * BLS12-381 provider implementation using tech.pegasys.teku:bls library.
 * <p>
 * This implementation wraps the Teku BLS library for all cryptographic operations.
 * All operations are thread-safe and validate inputs defensively.
 * <p>
 * Features:
 * - Singleton pattern to reduce instantiation overhead
 * - LRU cache for parsed BLS public keys to avoid repeated parsing overhead
 * <p>
 * Phase 4 implementation - wrappers around teku:bls JNI library.
 */
public class TekuBLSProvider implements BLSProvider {

    /**
     * Singleton instance of TekuBLSProvider.
     * <p>
     * Initialized once at class loading time and reused for all operations.
     * This eliminates provider instantiation overhead in hot paths.
     */
    private static final TekuBLSProvider INSTANCE = new TekuBLSProvider();

    /**
     * Get the singleton instance of TekuBLSProvider.
     *
     * @return Singleton provider instance
     */
    public static TekuBLSProvider getInstance() {
        return INSTANCE;
    }

    /**
     * LRU cache for parsed BLS public keys.
     * <p>
     * Public key parsing from byte[] to BLSPublicKey is expensive (~50-100µs).
     * Cache up to 10,000 keys with 5-minute TTL to handle committee rotations.
     * <p>
     * Thread-safe via Caffeine's concurrent implementation.
     */
    private final Cache<Bytes48, BLSPublicKey> publicKeyCache;

    /**
     * Package-private constructor to enforce singleton pattern via getInstance().
     * <p>
     * Initialize Caffeine cache with:
     * - maximumSize: 10,000 entries
     * - expireAfterWrite: 5 minutes
     * - recordStats: true for monitoring
     * <p>
     * Note: Package-private to support legacy code that instantiates TekuBLSProvider directly.
     * Use getInstance() for singleton access in new code.
     */
    TekuBLSProvider() {
        this.publicKeyCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .recordStats()
            .build();
    }

    /**
     * Parse BLS public key from compressed bytes with caching.
     * <p>
     * Uses LRU cache to avoid repeated parsing overhead for frequently-used keys.
     * Cache hit: ~1µs, Cache miss: ~50-100µs (parse + store)
     *
     * @param publicKeyBytes 48-byte compressed public key
     * @return Parsed BLSPublicKey
     * @throws IllegalArgumentException if bytes are invalid
     */
    private BLSPublicKey parsePublicKey(byte[] publicKeyBytes) {
        if (publicKeyBytes == null) {
            throw new NullPointerException("publicKeyBytes cannot be null");
        }
        if (publicKeyBytes.length != 48) {
            throw new IllegalArgumentException("Public key must be 48 bytes, got: " + publicKeyBytes.length);
        }

        // Use Bytes48 as cache key for proper equality semantics
        var key = Bytes48.wrap(publicKeyBytes);

        return publicKeyCache.get(key, k -> {
            // Cache miss: parse and store
            return BLSPublicKey.fromBytesCompressed(k);
        });
    }

    /**
     * Get public key cache statistics for monitoring.
     * <p>
     * Returns metrics: hit rate, miss rate, size, evictions.
     *
     * @return Cache statistics snapshot
     */
    public CacheStats getCacheStats() {
        return publicKeyCache.stats();
    }

    /**
     * Clear the public key cache (for testing).
     * <p>
     * Should not be called in production.
     */
    void clearCache() {
        publicKeyCache.invalidateAll();
    }

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
            var blsPublicKey = parsePublicKey(publicKey);
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
                blsPublicKeys.add(parsePublicKey(pubKey));
            }

            var messageBytes = Bytes.wrap(message);
            var blsSignature = BLSSignature.fromBytesCompressed(Bytes.wrap(aggregateSignature));
            return BLS.fastAggregateVerify(blsPublicKeys, messageBytes, blsSignature);
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
                blsPublicKeyLists.add(List.of(parsePublicKey(pubKey)));
                blsMessages.add(Bytes.wrap(message));
                blsSignatures.add(BLSSignature.fromBytesCompressed(Bytes.wrap(signature)));
            }

            return BLS.batchVerify(blsPublicKeyLists, blsMessages, blsSignatures);
        } catch (Exception e) {
            // Invalid format or verification failure
            return false;
        }
    }

    // ===== Phase 1C-1-C: Batch Aggregate Verification =====

    @Override
    public boolean batchVerifyAggregatesImpl(
        List<List<byte[]>> filteredKeyLists,
        List<byte[]> messages,
        List<byte[]> signatures
    ) {
        if (filteredKeyLists == null || messages == null || signatures == null) {
            throw new NullPointerException("Parameters cannot be null");
        }

        if (filteredKeyLists.isEmpty()) {
            return true; // No aggregates to verify
        }

        if (filteredKeyLists.size() != messages.size() || messages.size() != signatures.size()) {
            throw new IllegalArgumentException(
                "List sizes must match: keyLists=" + filteredKeyLists.size() +
                ", messages=" + messages.size() +
                ", signatures=" + signatures.size()
            );
        }

        try {
            var blsPublicKeyLists = new ArrayList<List<BLSPublicKey>>();
            var blsMessages = new ArrayList<Bytes>();
            var blsSignatures = new ArrayList<BLSSignature>();

            for (int i = 0; i < signatures.size(); i++) {
                var keyBytes = filteredKeyLists.get(i);
                var message = messages.get(i);
                var signature = signatures.get(i);

                if (keyBytes == null || message == null || signature == null) {
                    throw new NullPointerException("List elements cannot be null at index " + i);
                }

                if (keyBytes.isEmpty()) {
                    throw new IllegalArgumentException("keyBytes at index " + i + " cannot be empty");
                }

                if (signature.length != 96) {
                    throw new IllegalArgumentException("Signature at index " + i + " must be 96 bytes, got: " + signature.length);
                }

                // Parse public keys for this aggregate (multiple keys per entry for aggregates)
                var blsKeys = new ArrayList<BLSPublicKey>();
                for (var keyByte : keyBytes) {
                    if (keyByte == null) {
                        throw new NullPointerException("Public key in list at index " + i + " cannot be null");
                    }
                    if (keyByte.length != 48) {
                        throw new IllegalArgumentException("Public key must be 48 bytes, got: " + keyByte.length);
                    }
                    blsKeys.add(parsePublicKey(keyByte));
                }

                blsPublicKeyLists.add(blsKeys);                                         // Multiple keys per aggregate
                blsMessages.add(Bytes.wrap(message));
                blsSignatures.add(BLSSignature.fromBytesCompressed(Bytes.wrap(signature)));
            }

            // Native Teku batch verification with aggregate support
            return BLS.batchVerify(blsPublicKeyLists, blsMessages, blsSignatures);

        } catch (Exception e) {
            // Invalid format or verification failure
            return false;
        }
    }

    @Override
    public ParsedBLSKey parse(byte[] publicKey) {
        if (publicKey == null) {
            throw new NullPointerException("publicKey cannot be null");
        }
        if (publicKey.length != 48) {
            throw new IllegalArgumentException(
                "Public key must be 48 bytes, got: " + publicKey.length
            );
        }

        try {
            // Parse the compressed public key to Teku BLSPublicKey
            // This internally uses the publicKeyCache to avoid duplicate parsing
            var parsedKey = parsePublicKey(publicKey);

            // Wrap in opaque ParsedBLSKey record
            return new ParsedBLSKey(parsedKey);

        } catch (Exception e) {
            throw new IllegalArgumentException(
                "Failed to parse public key: " + e.getMessage(),
                e
            );
        }
    }
}
