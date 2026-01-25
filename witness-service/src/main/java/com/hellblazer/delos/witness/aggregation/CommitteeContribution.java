/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.witness.aggregation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Contribution from a single committee to a multi-committee aggregate.
 * Encapsulates per-committee signer bitmap and metadata.
 * <p>
 * Phase 1C-2-B: Multi-committee aggregate data structure.
 *
 * @param epoch        Committee epoch identifier (Fireflies epoch)
 * @param signerBitmap Bitmap of signers within this committee (bit per member)
 * @param signerCount  Number of signers in this committee (cached for quick threshold check)
 * @author hal.hildebrand
 */
public record CommitteeContribution(
    long epoch,
    byte[] signerBitmap,
    int signerCount
) {
    /**
     * Compact constructor with validation and defensive copy.
     */
    public CommitteeContribution {
        Objects.requireNonNull(signerBitmap, "signerBitmap cannot be null");
        if (signerBitmap.length == 0) {
            throw new IllegalArgumentException("signerBitmap must have at least 1 byte");
        }
        if (signerCount <= 0) {
            throw new IllegalArgumentException("signerCount must be positive, got: " + signerCount);
        }
        // Defensive copy
        signerBitmap = signerBitmap.clone();
    }

    /**
     * Get signer bitmap (defensive copy).
     */
    @Override
    public byte[] signerBitmap() {
        return signerBitmap.clone();
    }

    /**
     * Decode signer bitmap to list of indices.
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

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof CommitteeContribution other)) return false;
        return epoch == other.epoch
               && signerCount == other.signerCount
               && Arrays.equals(signerBitmap, other.signerBitmap);
    }

    @Override
    public int hashCode() {
        return Objects.hash(epoch, signerCount, Arrays.hashCode(signerBitmap));
    }
}
