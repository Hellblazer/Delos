/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.witness.aggregation.recursive.compression;

/**
 * Describes a contiguous segment of epochs with uniform compression strategy.
 * Immutable value object for segment boundaries and compression type.
 *
 * @param type       The compression strategy for this segment
 * @param startIndex The inclusive start index in the epoch chain
 * @param endIndex   The inclusive end index in the epoch chain
 * @author hal.hildebrand
 */
public record Segment(SegmentType type, int startIndex, int endIndex) {

    /**
     * Compact constructor with validation.
     *
     * @throws NullPointerException     if type is null
     * @throws IllegalArgumentException if indices are invalid
     */
    public Segment {
        if (type == null) {
            throw new NullPointerException("type cannot be null");
        }
        if (startIndex < 0) {
            throw new IllegalArgumentException("startIndex must be non-negative: " + startIndex);
        }
        if (endIndex < startIndex) {
            throw new IllegalArgumentException(
                "endIndex must be >= startIndex: " + endIndex + " < " + startIndex);
        }
    }

    /**
     * Calculate the number of epochs in this segment.
     *
     * @return The segment length (inclusive of both endpoints)
     */
    public int length() {
        return endIndex - startIndex + 1;
    }
}
