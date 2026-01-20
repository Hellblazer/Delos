/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package org.h2.util;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A deterministic clock for block-based time in replicated state machines.
 * <p>
 * Time is represented as (block height, transaction index within block).
 * Height advances with each block, and transaction index advances within a block.
 * <p>
 * Thread-safe: all operations use atomic updates via packed AtomicLong.
 * <p>
 * <strong>Atomicity Fix (Delos-96xs):</strong>
 * Previous implementation used two separate AtomicLong fields (height, txn), which created
 * a race condition in incrementHeight():
 * <pre>
 *   height.incrementAndGet();  // Step 1
 *   txn.set(TXN_INCREMENT);    // Step 2
 * </pre>
 * Between steps 1 and 2, another thread calling instant() could read the new height but
 * old txn value, returning inconsistent state (newHeight, oldTxn).
 * <p>
 * <strong>Solution:</strong> Pack both height and txn into a single AtomicLong:
 * <ul>
 *   <li>Upper 32 bits [63..32]: height (supports 4 billion blocks)</li>
 *   <li>Lower 32 bits [31..0]: txn (supports 4 billion transactions per block)</li>
 * </ul>
 * All updates use atomic read-modify-write operations (updateAndGet, addAndGet),
 * ensuring instant() always observes consistent (height, txn) pairs.
 *
 * @author hal.hildebrand
 */
public class BlockClock extends Clock {
    private static final long TXN_INCREMENT = (1L << 31) - 1;
    private static final long TXN_MASK      = 0xFFFFFFFFL;  // Lower 32 bits

    /**
     * Packed state: [63..32] = height (32 bits), [31..0] = txn (32 bits)
     */
    private final AtomicLong state;

    private final ZoneId zoneId;

    public BlockClock() {
        this(ZoneOffset.UTC);
    }

    public BlockClock(ZoneId zoneId) {
        this.zoneId = zoneId;
        // Initial state: height=0, txn=TXN_INCREMENT
        this.state = new AtomicLong(TXN_INCREMENT);
    }

    @Override
    public ZoneId getZone() {
        return zoneId;
    }

    /**
     * Advance to the next block height and reset transaction counter.
     * <p>
     * This operation is atomic: height increment and txn reset happen as a single
     * indivisible update, preventing race conditions.
     */
    public void incrementHeight() {
        state.updateAndGet(s -> {
            long height = (s >>> 32) + 1;  // Unsigned right shift: extract upper 32 bits, increment
            return (height << 32) | TXN_INCREMENT;  // Pack: height in upper 32, txn reset to TXN_INCREMENT
        });
    }

    /**
     * Advance to the next transaction within the current block.
     * <p>
     * This operation atomically adds TXN_INCREMENT to the lower 32 bits (txn),
     * leaving the upper 32 bits (height) unchanged.
     */
    public void incrementTxn() {
        state.updateAndGet(s -> {
            long height = s >>> 32;                   // Unsigned right shift: extract height
            long txn = (s & TXN_MASK) + TXN_INCREMENT;  // Extract and increment txn
            return (height << 32) | (txn & TXN_MASK);   // Pack: preserve height, update txn
        });
    }

    /**
     * Get the current block time as a semantically correct BlockTime record.
     * <p>
     * This method performs a single atomic read of the packed state, guaranteeing
     * that height and txn are consistent (from the same snapshot).
     *
     * @return immutable BlockTime with current (height, txn)
     */
    public BlockTime blockTime() {
        long s = state.get();  // Single atomic read
        long height = s >>> 32;         // Unsigned right shift: extract upper 32 bits
        long txn = s & TXN_MASK;        // Extract lower 32 bits
        return new BlockTime(height, txn);
    }

    /**
     * Get the current block time as an Instant (for Clock interface compatibility).
     * <p>
     * <strong>WARNING:</strong> This method is semantically incorrect as it represents
     * height as seconds and txn as nanoseconds. Use {@link #blockTime()} instead for
     * semantically correct block time representation.
     * <p>
     * Kept for backward compatibility with java.time.Clock API.
     *
     * @return Instant with height as epochSecond and txn as nano
     * @deprecated Use {@link #blockTime()} for semantically correct representation
     */
    @Override
    @Deprecated
    public Instant instant() {
        long s = state.get();  // Single atomic read
        long height = s >>> 32;  // Unsigned right shift: extract height
        long txn = s & TXN_MASK;
        return Instant.ofEpochSecond(height, txn);
    }

    /**
     * @return the current block height
     */
    public long getHeight() {
        return state.get() >>> 32;  // Unsigned right shift: extract upper 32 bits
    }

    /**
     * @return the current transaction index
     */
    public long getTxn() {
        return state.get() & TXN_MASK;  // Extract lower 32 bits
    }

    @Override
    public Clock withZone(ZoneId zone) {
        if (zone == null || zoneId.equals(zone)) {
            return this;
        }
        return new BlockClock(zone);
    }
}
