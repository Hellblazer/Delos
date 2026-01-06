/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
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
 * Thread-safe: all operations use atomic updates.
 *
 * @author hal.hildebrand
 */
public class BlockClock extends Clock {
    private static final long TXN_INCREMENT = (1L << 31) - 1;

    private final AtomicLong height = new AtomicLong(0);
    private final AtomicLong txn    = new AtomicLong(TXN_INCREMENT);

    private final ZoneId zoneId;

    public BlockClock() {
        this(ZoneOffset.UTC);
    }

    public BlockClock(ZoneId zoneId) {
        this.zoneId = zoneId;
    }

    @Override
    public ZoneId getZone() {
        return zoneId;
    }

    /**
     * Advance to the next block height and reset transaction counter.
     */
    public void incrementHeight() {
        height.incrementAndGet();
        txn.set(TXN_INCREMENT);
    }

    /**
     * Advance to the next transaction within the current block.
     */
    public void incrementTxn() {
        txn.addAndGet(TXN_INCREMENT);
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochSecond(height.get(), txn.get());
    }

    /**
     * @return the current block height
     */
    public long getHeight() {
        return height.get();
    }

    /**
     * @return the current transaction index
     */
    public long getTxn() {
        return txn.get();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        if (zone == null || zoneId.equals(zone)) {
            return this;
        }
        return new BlockClock(zone);
    }
}
