/*
 * Copyright (c) 2026, Hellblazer, Inc. All rights reserved.
 */
package org.h2.util;

/**
 * Immutable representation of deterministic block time in a replicated state machine.
 * <p>
 * Time is represented as (block height, transaction index within block).
 * This is semantically correct for block-based deterministic time, unlike Instant
 * which incorrectly represents height as seconds and transaction index as nanoseconds.
 * <p>
 * Instances are immutable and thread-safe.
 *
 * @param height Block height (0-based)
 * @param txn    Transaction index within the block
 * @author hal.hildebrand
 */
public record BlockTime(long height, long txn) implements Comparable<BlockTime> {

    /**
     * Create a new BlockTime.
     *
     * @param height Block height (must be >= 0)
     * @param txn    Transaction index (must be >= 0)
     */
    public BlockTime {
        if (height < 0) {
            throw new IllegalArgumentException("height must be >= 0, got: " + height);
        }
        if (txn < 0) {
            throw new IllegalArgumentException("txn must be >= 0, got: " + txn);
        }
    }

    /**
     * Compare two BlockTime instances.
     * Earlier blocks come first; within the same block, earlier transactions come first.
     *
     * @param other the other BlockTime to compare to
     * @return negative if this < other, zero if equal, positive if this > other
     */
    @Override
    public int compareTo(BlockTime other) {
        int heightCmp = Long.compare(this.height, other.height);
        if (heightCmp != 0) {
            return heightCmp;
        }
        return Long.compare(this.txn, other.txn);
    }

    /**
     * @return true if this BlockTime is before the other
     */
    public boolean isBefore(BlockTime other) {
        return this.compareTo(other) < 0;
    }

    /**
     * @return true if this BlockTime is after the other
     */
    public boolean isAfter(BlockTime other) {
        return this.compareTo(other) > 0;
    }

    @Override
    public String toString() {
        return String.format("BlockTime[height=%d, txn=%d]", height, txn);
    }
}
