/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.delos.cryptography.bls;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.cryptography.proto.PubKey;

import java.util.Arrays;
import java.util.Objects;

/**
 * BLS-12-381 public key (G2 point, 96 bytes) + mandatory Proof of Possession.
 * <p>
 * BLS public keys must always include a Proof of Possession (PoP) to prevent
 * rogue key attacks in signature aggregation scenarios.
 * <p>
 * The public key is a point on the G2 curve of BLS12-381, represented as
 * 96 bytes in compressed form. The PoP is a signature over the public key itself.
 *
 * @param g2Compressed       The G2 point in compressed form (96 bytes)
 * @param proofOfPossession The proof of possession (48 bytes)
 * @author hal.hildebrand
 */
public record BLSPublicKey(byte[] g2Compressed, ProofOfPossession proofOfPossession) {
    /**
     * Size of a compressed BLS public key (G2 point)
     */
    public static final int COMPRESSED_SIZE = 96;

    /**
     * Public key code for BLS-12-381 in proto PubKey messages.
     * Follows convention: ED25519=1, ECDSA=2, BLS_SIG=3, BLS_PUBKEY=4
     */
    private static final int BLS_PUBLIC_KEY_CODE = 4;

    /**
     * Compact constructor with validation and defensive copy.
     *
     * @throws NullPointerException     if g2Compressed or proofOfPossession is null
     * @throws IllegalArgumentException if g2Compressed is not COMPRESSED_SIZE bytes
     */
    public BLSPublicKey {
        Objects.requireNonNull(g2Compressed, "g2Compressed cannot be null");
        Objects.requireNonNull(proofOfPossession, "proofOfPossession cannot be null");
        if (g2Compressed.length != COMPRESSED_SIZE) {
            throw new IllegalArgumentException(
                "Public key must be " + COMPRESSED_SIZE + " bytes, got " + g2Compressed.length);
        }
        // Defensive copy
        g2Compressed = g2Compressed.clone();
    }

    /**
     * Static factory method for creating a public key from a protobuf message.
     *
     * @param pubKey   The protobuf PubKey message
     * @param provider BLS provider for PoP verification
     * @return A new BLSPublicKey instance
     * @throws IllegalArgumentException if pubKey has wrong code or invalid encoding
     */
    public static BLSPublicKey fromPubKey(PubKey pubKey, BLSProvider provider) {
        Objects.requireNonNull(pubKey, "pubKey cannot be null");
        Objects.requireNonNull(provider, "provider cannot be null");
        if (pubKey.getCode() != BLS_PUBLIC_KEY_CODE) {
            throw new IllegalArgumentException(
                "Expected BLS public key code " + BLS_PUBLIC_KEY_CODE + ", got " + pubKey.getCode());
        }

        var encoded = pubKey.getEncoded().toByteArray();
        if (encoded.length != COMPRESSED_SIZE + ProofOfPossession.COMPRESSED_SIZE) {
            throw new IllegalArgumentException(
                "Expected " + (COMPRESSED_SIZE + ProofOfPossession.COMPRESSED_SIZE) +
                " bytes (96 G2 + 48 PoP), got " + encoded.length);
        }

        // Extract G2 point (first 96 bytes) and PoP (last 48 bytes)
        var g2 = Arrays.copyOfRange(encoded, 0, COMPRESSED_SIZE);
        var popBytes = Arrays.copyOfRange(encoded, COMPRESSED_SIZE, encoded.length);
        var pop = new ProofOfPossession(popBytes);

        return new BLSPublicKey(g2, pop);
    }

    /**
     * Get the compressed G2 bytes (defensive copy).
     *
     * @return A copy of the G2 point bytes
     */
    @Override
    public byte[] g2Compressed() {
        return g2Compressed.clone();
    }

    /**
     * Get the compressed public key bytes (alias for g2Compressed).
     *
     * @return A copy of the public key bytes
     */
    public byte[] toBytesCompressed() {
        return g2Compressed();
    }

    /**
     * Verify the Proof of Possession for this public key.
     *
     * @param provider BLS provider for verification
     * @return true if PoP is valid, false otherwise
     */
    public boolean verifyPoP(BLSProvider provider) {
        return proofOfPossession.verify(g2Compressed, provider);
    }

    /**
     * Serialize this public key to a protobuf PubKey message.
     * <p>
     * The encoding concatenates G2 (96 bytes) + PoP (48 bytes) = 144 bytes total.
     *
     * @return A protobuf PubKey with BLS_PUBKEY code
     */
    public PubKey toPubKey() {
        // Concatenate G2 and PoP
        var encoded = new byte[COMPRESSED_SIZE + ProofOfPossession.COMPRESSED_SIZE];
        System.arraycopy(g2Compressed, 0, encoded, 0, COMPRESSED_SIZE);
        System.arraycopy(proofOfPossession.compressedSignature(), 0, encoded, COMPRESSED_SIZE,
                         ProofOfPossession.COMPRESSED_SIZE);

        return PubKey.newBuilder()
                     .setCode(BLS_PUBLIC_KEY_CODE)
                     .setEncoded(ByteString.copyFrom(encoded))
                     .build();
    }

    /**
     * Equality based on G2 point only (PoP is not considered).
     * Two public keys with the same G2 point are equal even if they have different PoPs
     * (which should never happen in practice, but simplifies equality semantics).
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof BLSPublicKey other)) return false;
        return Arrays.equals(g2Compressed, other.g2Compressed);
    }

    /**
     * Hash code based on G2 point only (consistent with equals).
     */
    @Override
    public int hashCode() {
        return Arrays.hashCode(g2Compressed);
    }

    /**
     * String representation for debugging.
     */
    @Override
    public String toString() {
        return "BLSPublicKey[" + g2Compressed.length + " bytes G2]";
    }
}

