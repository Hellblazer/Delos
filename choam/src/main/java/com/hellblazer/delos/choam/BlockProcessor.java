/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.choam.support.HashedCertifiedBlock;
import org.joou.ULong;

/**
 * Manages block processing, validation, and consumption for the CHOAM consensus protocol.
 * <p>
 * BlockProcessor handles the core operation of accepting and consuming blocks from the
 * consensus stream. It manages the linear block consumer thread and coordinates with
 * the FSM for proper state transitions.
 * <p>
 * This component is responsible for:
 * - Accepting blocks from the consensus protocol
 * - Managing the block consumer thread (start/stop/interrupt)
 * - Tracking the current block height
 * - Handling block recovery from synchronization
 * <p>
 * Thread Safety: BlockProcessor manages its own thread lifecycle and uses locking
 * to protect shared state. All methods are thread-safe for concurrent access from
 * the FSM and external components.
 * <p>
 * Lock Ownership: BlockProcessor owns the headLock (ReadWriteLock) that protects
 * block state. It does NOT acquire other locks (e.g., viewStateLock) - lock
 * coordination across components is handled by the CHOAM coordinator.
 *
 * @author hal.hildebrand
 */
public interface BlockProcessor {

    /**
     * Consumes a block into the linear thread queue for processing.
     * <p>
     * This method enqueues the block for the consumer thread to process. The actual
     * block consumption happens asynchronously in the consumer thread context.
     *
     * @param block the block to consume
     */
    void consume(HashedCertifiedBlock block);

    /**
     * Returns the current block (head of the chain).
     *
     * @return the current block, or null if no block has been processed yet
     */
    HashedCertifiedBlock currentBlock();

    /**
     * Returns the height of the current block.
     *
     * @return the current block height
     */
    ULong currentHeight();

    /**
     * Recovers block state from a synchronization anchor.
     * <p>
     * This is called during synchronization to establish a known good block
     * state from which to continue processing.
     *
     * @param anchor the block to use as recovery anchor
     */
    void recover(HashedCertifiedBlock anchor);

    /**
     * Starts the block consumer thread.
     * <p>
     * This creates and starts the linear thread that consumes blocks from the
     * pending queue. Must be called during CHOAM initialization.
     * <p>
     * Thread Safety: This method is thread-safe but should only be called once
     * during initialization. Calling multiple times may result in multiple
     * consumer threads.
     */
    void start();

    /**
     * Stops the block consumer thread.
     * <p>
     * This gracefully shuts down the linear thread by:
     * 1. Interrupting the thread
     * 2. Waiting up to 5 seconds for it to terminate
     * <p>
     * If the thread doesn't terminate within the timeout, the method returns
     * but the thread may still be running.
     */
    void stop();
}
