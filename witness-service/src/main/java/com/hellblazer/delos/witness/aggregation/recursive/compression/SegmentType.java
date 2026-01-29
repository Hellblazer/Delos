/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.witness.aggregation.recursive.compression;

/**
 * Type of compression strategy for a segment of epoch chain.
 *
 * @author hal.hildebrand
 */
public enum SegmentType {
    /**
     * Single epochs with no compression benefit.
     * Stored as raw proto bytes.
     */
    LITERAL,

    /**
     * 2+ consecutive Unchanged epochs.
     * Compressed using run-length encoding.
     */
    RUN_LENGTH,

    /**
     * 2+ consecutive Changed epochs.
     * Compressed using delta-bitmap encoding.
     */
    DELTA_BITMAP
}
