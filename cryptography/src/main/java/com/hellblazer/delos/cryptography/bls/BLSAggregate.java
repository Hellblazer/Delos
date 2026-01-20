/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.cryptography.bls;

import com.hellblazer.delos.cryptography.bls.impl.TekuBLSProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;

/**
 * BLS signature aggregate with signer bitmap.
 * <p>
 * Represents an aggregated BLS signature from multiple signers with a bitmap
 * indicating which committee members signed. Enables compact representation:
 * 48 bytes (signature) + ceil(n/8) bytes (bitmap) vs 48*n bytes for individual signatures.
 * <p>
 * Immutable with defensive copies of byte arrays.
 *
 * @param aggregatedSignature The aggregated BLS signature (48 bytes)
 * @param signerBitmap        Bitmap of signer positions (at least 1 byte)
 * @author hal.hildebrand
 */
public record BLSAggregate(BLSSignature aggregatedSignature, byte[] signerBitmap) {

    /**
     * Compact constructor with validation and defensive copy.
     *
     * @throws NullPointerException     if any parameter is null
     * @throws IllegalArgumentException if signerBitmap is empty
     */
    public BLSAggregate {
        Objects.requireNonNull(aggregatedSignature, "aggregatedSignature cannot be null");
        Objects.requireNonNull(signerBitmap, "signerBitmap cannot be null");
        if (signerBitmap.length == 0) {
            throw new IllegalArgumentException("signerBitmap must have at least 1 byte");
        }
        // Defensive copy
        signerBitmap = signerBitmap.clone();
    }

    /**
     * Static factory method to aggregate signatures with signer indices.
     * <p>
     * Creates a BLSAggregate by combining multiple signatures and encoding
     * the signer positions in a bitmap.
     *
     * @param signatures    List of BLS signatures to aggregate (must not be empty)
     * @param signerIndices List of signer positions (must match signatures length)
     * @return A new BLSAggregate instance
     * @throws NullPointerException     if any parameter is null
     * @throws IllegalArgumentException if lists are empty or have mismatched lengths
     */
    public static BLSAggregate aggregate(List<BLSSignature> signatures, List<Integer> signerIndices) {
        Objects.requireNonNull(signatures, "signatures cannot be null");
        Objects.requireNonNull(signerIndices, "signerIndices cannot be null");

        if (signatures.isEmpty()) {
            throw new IllegalArgumentException("signatures list cannot be empty");
        }
        if (signatures.size() != signerIndices.size()) {
            throw new IllegalArgumentException(
                "signatures and signerIndices must have same length, got signatures="
                + signatures.size() + ", indices=" + signerIndices.size()
            );
        }

        // Aggregate signatures using BLS provider
        BLSSignature aggregated;
        if (signatures.size() == 1) {
            aggregated = signatures.get(0);
        } else {
            // Use the provider to aggregate multiple signatures
            var provider = new TekuBLSProvider();
            var sigBytes = signatures.stream()
                                     .map(BLSSignature::toBytes)
                                     .toList();
            var aggregatedBytes = provider.aggregateSignatures(sigBytes);
            aggregated = new BLSSignature(aggregatedBytes);
        }

        // Build bitmap from signer indices
        var bitmap = createBitmap(signerIndices);

        return new BLSAggregate(aggregated, bitmap);
    }

    /**
     * Create a bitmap byte array from signer indices.
     * <p>
     * The bitmap is sized to accommodate the highest index with minimal padding.
     * Each bit represents a signer position (0-indexed).
     *
     * @param signerIndices List of signer positions
     * @return Byte array bitmap with set bits at signer positions
     */
    private static byte[] createBitmap(List<Integer> signerIndices) {
        if (signerIndices.isEmpty()) {
            return new byte[1]; // Minimum 1 byte
        }

        // Find max index to determine bitmap size
        var maxIndex = signerIndices.stream().mapToInt(Integer::intValue).max().orElse(0);
        var bitmapSizeBytes = (maxIndex / 8) + 1;

        var bitmap = new byte[bitmapSizeBytes];

        // Set bits for each signer
        for (var index : signerIndices) {
            var byteIndex = index / 8;
            var bitIndex = index % 8;
            bitmap[byteIndex] |= (1 << bitIndex);
        }

        return bitmap;
    }

    /**
     * Get the signer bitmap (defensive copy).
     *
     * @return A copy of the signer bitmap bytes
     */
    @Override
    public byte[] signerBitmap() {
        return signerBitmap.clone();
    }

    /**
     * Get the size of the signer bitmap in bytes.
     *
     * @return Number of bytes in the bitmap
     */
    public int getSignerBitmapSize() {
        return signerBitmap.length;
    }

    /**
     * Decode the signer bitmap to get list of signer indices.
     * <p>
     * Returns the positions (0-indexed) where bits are set in the bitmap.
     *
     * @return List of signer indices in ascending order
     */
    public List<Integer> getSignerIndices() {
        var indices = new ArrayList<Integer>();

        for (int byteIndex = 0; byteIndex < signerBitmap.length; byteIndex++) {
            var b = signerBitmap[byteIndex];
            for (int bitIndex = 0; bitIndex < 8; bitIndex++) {
                if ((b & (1 << bitIndex)) != 0) {
                    indices.add(byteIndex * 8 + bitIndex);
                }
            }
        }

        return indices;
    }

    /**
     * Equality based on aggregated signature and bitmap.
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof BLSAggregate other)) return false;
        return Objects.equals(aggregatedSignature, other.aggregatedSignature)
               && Arrays.equals(signerBitmap, other.signerBitmap);
    }

    /**
     * Hash code based on aggregated signature and bitmap.
     */
    @Override
    public int hashCode() {
        return Objects.hash(aggregatedSignature, Arrays.hashCode(signerBitmap));
    }

    /**
     * String representation for debugging.
     */
    @Override
    public String toString() {
        return "BLSAggregate[" + getSignerIndices().size() + " signers, " +
               signerBitmap.length + " bitmap bytes]";
    }
}
