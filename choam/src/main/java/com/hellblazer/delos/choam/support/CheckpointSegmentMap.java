/*
 * Copyright (c) 2020, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam.support;

import java.util.function.Function;

/**
 * Abstraction for checkpoint segment storage operations. Provides a minimal interface
 * for storing and retrieving checkpoint segments by index, enabling alternative storage
 * implementations beyond H2 MVStore (e.g., RocksDB, in-memory).
 * <p>
 * This interface decouples CheckpointAssembler from MVMap-specific operations while
 * preserving the essential checkpoint segment storage contract.
 *
 * @author hal.hildebrand
 */
public interface CheckpointSegmentMap {

    /**
     * Returns true if this map contains a segment for the specified index.
     *
     * @param index the segment index
     * @return true if a segment exists at the index
     */
    boolean containsKey(int index);

    /**
     * If the specified index is not already associated with a value, attempts to compute
     * its value using the given supplier function and enters it into this map.
     *
     * @param index the segment index
     * @param supplier the function to compute a segment value
     * @return the current (existing or computed) value associated with the index
     */
    byte[] computeIfAbsent(int index, Function<Integer, byte[]> supplier);

    /**
     * Returns the number of segments stored in this map.
     *
     * @return the number of stored segments
     */
    int size();
}
