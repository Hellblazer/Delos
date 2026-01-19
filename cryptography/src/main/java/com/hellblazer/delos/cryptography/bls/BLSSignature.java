/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.delos.cryptography.bls;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.cryptography.proto.Sig;

import java.util.Arrays;
import java.util.Objects;

/**
 * BLS-12-381 signature (G1 point, 48 bytes compressed).
 * Immutable wrapper over compressed BLS signature bytes.
 * <p>
 * Uses defensive copying to ensure immutability of byte arrays.
 *
 * @param compressedBytes The G1 point in compressed form (48 bytes)
 * @author hal.hildebrand
 */
public record BLSSignature(byte[] compressedBytes) {
    /**
     * Size of a compressed BLS signature (G1 point)
     */
    public static final int COMPRESSED_SIZE = 48;

    /**
     * Signature code for BLS-12-381 in proto Sig messages.
     * Follows convention: ED25519=1, ECDSA=2, BLS_12_381=3
     */
    private static final int BLS_SIGNATURE_CODE = 3;

    /**
     * Compact constructor with validation and defensive copy.
     *
     * @throws NullPointerException     if compressedBytes is null
     * @throws IllegalArgumentException if compressedBytes is not COMPRESSED_SIZE bytes
     */
    public BLSSignature {
        Objects.requireNonNull(compressedBytes, "compressedBytes cannot be null");
        if (compressedBytes.length != COMPRESSED_SIZE) {
            throw new IllegalArgumentException(
                "Signature must be " + COMPRESSED_SIZE + " bytes, got " + compressedBytes.length);
        }
        // Defensive copy in compact constructor
        compressedBytes = compressedBytes.clone();
    }

    /**
     * Static factory method for creating a signature from bytes.
     *
     * @param bytes The compressed signature bytes (48 bytes)
     * @return A new BLSSignature instance
     * @throws NullPointerException     if bytes is null
     * @throws IllegalArgumentException if bytes is not 48 bytes
     */
    public static BLSSignature fromBytes(byte[] bytes) {
        return new BLSSignature(bytes);
    }

    /**
     * Deserialize a signature from a protobuf Sig message.
     *
     * @param sig The protobuf Sig message
     * @return A new BLSSignature instance
     * @throws IllegalArgumentException if sig has wrong code or invalid bytes
     */
    public static BLSSignature fromSig(Sig sig) {
        Objects.requireNonNull(sig, "sig cannot be null");
        if (sig.getCode() != BLS_SIGNATURE_CODE) {
            throw new IllegalArgumentException(
                "Expected BLS signature code " + BLS_SIGNATURE_CODE + ", got " + sig.getCode());
        }
        if (sig.getSignaturesCount() == 0) {
            throw new IllegalArgumentException("Sig message has no signature bytes");
        }
        return new BLSSignature(sig.getSignatures(0).toByteArray());
    }

    /**
     * Get the compressed signature bytes (defensive copy).
     *
     * @return A copy of the signature bytes
     */
    @Override
    public byte[] compressedBytes() {
        return compressedBytes.clone();
    }

    /**
     * Get the signature bytes (alias for compressedBytes for consistency).
     *
     * @return A copy of the signature bytes
     */
    public byte[] toBytes() {
        return compressedBytes();
    }

    /**
     * Serialize this signature to a protobuf Sig message.
     *
     * @return A protobuf Sig with BLS_12_381 code and signature bytes
     */
    public Sig toSig() {
        return Sig.newBuilder()
                  .setCode(BLS_SIGNATURE_CODE)
                  .addSignatures(ByteString.copyFrom(compressedBytes))
                  .build();
    }

    /**
     * Equality based on signature bytes (via record's automatic implementation).
     * Uses Arrays.equals for byte array comparison.
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof BLSSignature other)) return false;
        return Arrays.equals(compressedBytes, other.compressedBytes);
    }

    /**
     * Hash code based on signature bytes (via record's automatic implementation).
     * Uses Arrays.hashCode for byte array hashing.
     */
    @Override
    public int hashCode() {
        return Arrays.hashCode(compressedBytes);
    }

    /**
     * String representation for debugging.
     */
    @Override
    public String toString() {
        return "BLSSignature[" + compressedBytes.length + " bytes]";
    }
}
