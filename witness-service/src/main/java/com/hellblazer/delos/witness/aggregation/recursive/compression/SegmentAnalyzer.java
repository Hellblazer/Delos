/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.witness.aggregation.recursive.compression;

import com.hellblazer.delos.witness.aggregation.recursive.EpochLink;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Analyzes epoch chains to detect optimal compression patterns.
 * Partitions chains into segments, each with the most effective compression strategy:
 * - RUN_LENGTH: 2+ consecutive Unchanged epochs
 * - DELTA_BITMAP: 2+ consecutive Changed epochs
 * - LITERAL: Single epochs with no compression benefit
 * <p>
 * Algorithm: O(n) linear scan with greedy segment assignment.
 * Thread-safe: Stateless analyzer, all methods are static.
 *
 * @author hal.hildebrand
 */
public final class SegmentAnalyzer {

    private SegmentAnalyzer() {
        // Static utility class
    }

    /**
     * Analyze epoch chain and partition into optimal compression segments.
     *
     * @param epochChain The list of epochs to analyze
     * @return Immutable list of segments with compression strategy assignments
     * @throws NullPointerException if epochChain is null
     */
    public static List<Segment> analyze(List<EpochLink> epochChain) {
        Objects.requireNonNull(epochChain, "epochChain cannot be null");

        if (epochChain.isEmpty()) {
            return List.of();
        }

        var segments = new ArrayList<Segment>();
        var i = 0;

        while (i < epochChain.size()) {
            var link = epochChain.get(i);

            if (link instanceof EpochLink.Unchanged) {
                var runLength = countConsecutiveUnchanged(epochChain, i);
                if (runLength >= 2) {
                    segments.add(new Segment(SegmentType.RUN_LENGTH, i, i + runLength - 1));
                    i += runLength;
                } else {
                    segments.add(new Segment(SegmentType.LITERAL, i, i));
                    i++;
                }
            } else { // Changed
                var changedCount = countConsecutiveChanged(epochChain, i);
                if (changedCount >= 2) {
                    segments.add(new Segment(SegmentType.DELTA_BITMAP, i, i + changedCount - 1));
                    i += changedCount;
                } else {
                    segments.add(new Segment(SegmentType.LITERAL, i, i));
                    i++;
                }
            }
        }

        return List.copyOf(segments);
    }

    /**
     * Count consecutive Unchanged epochs starting from index.
     *
     * @param epochChain The epoch chain
     * @param startIndex The starting index
     * @return Count of consecutive Unchanged epochs
     */
    private static int countConsecutiveUnchanged(List<EpochLink> epochChain, int startIndex) {
        var count = 0;
        for (int i = startIndex; i < epochChain.size(); i++) {
            if (epochChain.get(i) instanceof EpochLink.Unchanged) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    /**
     * Count consecutive Changed epochs starting from index.
     *
     * @param epochChain The epoch chain
     * @param startIndex The starting index
     * @return Count of consecutive Changed epochs
     */
    private static int countConsecutiveChanged(List<EpochLink> epochChain, int startIndex) {
        var count = 0;
        for (int i = startIndex; i < epochChain.size(); i++) {
            if (epochChain.get(i) instanceof EpochLink.Changed) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }
}
