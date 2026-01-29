/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.witness.aggregation.recursive.compression;

/**
 * Represents a single entry in the wire format segment table.
 * Contains metadata for one compressed segment: type, epoch count, and data length.
 * <p>
 * Immutable value object used during wire format encoding/decoding.
 *
 * @param type       The compression strategy type for this segment
 * @param epochCount The number of epochs in this segment
 * @param dataLength The byte length of the compressed segment data
 * @author hal.hildebrand
 * @since Phase 3.3
 */
public record SegmentTableEntry(
    SegmentType type,
    int epochCount,
    int dataLength
) {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException     if type is null
     * @throws IllegalArgumentException if counts are invalid
     */
    public SegmentTableEntry {
        if (type == null) {
            throw new NullPointerException("type cannot be null");
        }
        if (epochCount < 0) {
            throw new IllegalArgumentException("epochCount must be non-negative: " + epochCount);
        }
        if (dataLength < 0) {
            throw new IllegalArgumentException("dataLength must be non-negative: " + dataLength);
        }
    }
}
