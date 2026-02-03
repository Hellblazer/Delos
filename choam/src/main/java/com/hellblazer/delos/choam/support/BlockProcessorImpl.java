/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.BlockProcessor;
import com.hellblazer.delos.choam.Parameters;
import org.joou.ULong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Default implementation of BlockProcessor for CHOAM consensus.
 * <p>
 * This implementation manages block acceptance and consumption using:
 * - An atomic reference to track the current block head
 * - A read-write lock to protect concurrent access to block state
 * - A virtual thread for asynchronous block consumption from the pending queue
 * <p>
 * The linear block consumer thread continuously polls the pending queue, validates
 * blocks, and updates the head reference with proper locking.
 *
 * @author hal.hildebrand
 */
public class BlockProcessorImpl implements BlockProcessor {
    private static final Logger log = LoggerFactory.getLogger(BlockProcessorImpl.class);
    private static final int MAX_EMPTY_POLLS = 10; // ~5 seconds at 500ms poll + 100ms sleep

    private final AtomicReference<HashedCertifiedBlock> head;
    private final Consumer<HashedCertifiedBlock> blockConsumer;
    private final BoundedPriorityBlockingQueue<HashedCertifiedBlock> pending;
    private final AtomicBoolean started;
    private final AtomicInteger emptyPolls = new AtomicInteger(0);
    private final Parameters params;
    private volatile Thread linear;
    private volatile Consumer<StallDetectedEvent> stallListener;

    public BlockProcessorImpl(BoundedPriorityBlockingQueue<HashedCertifiedBlock> pending,
                             AtomicBoolean started, Parameters params,
                             AtomicReference<HashedCertifiedBlock> head,
                             Consumer<HashedCertifiedBlock> blockConsumer) {
        this.pending = pending;
        this.started = started;
        this.params = params;
        this.head = head;
        this.blockConsumer = blockConsumer;
    }

    /**
     * Register a listener for stall detection events.
     * <p>
     * The listener will be invoked when MAX_EMPTY_POLLS consecutive empty polls
     * are detected, indicating a potential network partition, consensus slowdown,
     * or Byzantine behavior.
     *
     * @param listener Consumer to handle stall events, or null to unregister
     */
    public void setStallListener(Consumer<StallDetectedEvent> listener) {
        this.stallListener = listener;
    }

    @Override
    public void consume(HashedCertifiedBlock block) {
        if (!pending.offer(block)) {
            log.warn("Rejected pending block: {} hash: {} height: {} on: {}", block.block.getBodyCase(),
                     block.hash, block.height(), params.member().getId());
        }
    }

    @Override
    public HashedCertifiedBlock currentBlock() {
        return head.get();
    }

    @Override
    public ULong currentHeight() {
        return head.get().height();
    }

    @Override
    public void recover(HashedCertifiedBlock anchor) {
        head.set(anchor);
        log.info("Recovered block state to anchor: {} height: {} on: {}", anchor.hash, anchor.height(),
                 params.member().getId());
    }

    @Override
    public void start() {
        if (linear == null) {
            linear = Thread.ofVirtual()
                           .name("Linear[%s on: %s]".formatted(params.context().getId(), params.member().getId()))
                           .start(this::consumer);
        }
    }

    @Override
    public void stop() {
        if (linear != null) {
            linear.interrupt();
            try {
                linear.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted waiting for consumer thread shutdown on: {}", params.member().getId());
            }
        }
    }

    /**
     * Consumer thread loop that processes blocks from the pending queue.
     * <p>
     * This method runs in a dedicated virtual thread and continuously:
     * 1. Polls the pending queue for new blocks (500ms timeout)
     * 2. Detects consumer stall (> 5 seconds of empty polls)
     * 3. Consumes valid blocks with proper locking
     * 4. Handles interruption gracefully during shutdown
     */
    private void consumer() {
        try {
            while (started.get()) {
                HashedCertifiedBlock next = null;
                try {
                    next = pending.poll(500, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (!started.get()) {
                        log.debug("Consumer thread interrupted during shutdown on: {}", params.member().getId());
                    } else {
                        log.warn("Consumer thread interrupted unexpectedly on: {}", params.member().getId(), e);
                    }
                    return;
                }
                if (!started.get()) {
                    return;
                }
                if (next == null) {
                    int count = emptyPolls.incrementAndGet();
                    if (count == MAX_EMPTY_POLLS) {
                        log.warn("Consumer stall detected: {} empty polls (~5 seconds) on: {}", count,
                                 params.member().getId());
                        // Emit stall event to listener (if registered)
                        var listener = stallListener; // Local copy for thread safety
                        if (listener != null) {
                            try {
                                var event = new StallDetectedEvent(head.get().height(),
                                                                   Duration.ofMillis(count * 600L), // poll + sleep time
                                                                   count, params.context());
                                listener.accept(event);
                            } catch (Throwable t) {
                                log.error("Error in stall listener callback on: {}", params.member().getId(), t);
                            }
                        }
                    } else if (count > MAX_EMPTY_POLLS && count % 5 == 0) {
                        log.warn("Consumer still stalled: {} empty polls on: {}", count, params.member().getId());
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        if (!started.get()) {
                            log.debug("Consumer thread interrupted during sleep on: {}", params.member().getId());
                        } else {
                            log.warn("Consumer thread interrupted during sleep on: {}", params.member().getId(), e);
                        }
                        return;
                    }
                    continue;
                }
                emptyPolls.set(0);
                try {
                    processBlock(next);
                } catch (Throwable t) {
                    log.error("Error consuming block: {} hash: {} height: {} on: {}", next.block.getBodyCase(),
                              next.hash, next.height(), params.member().getId(), t);
                }
            }
        } finally {
            log.debug("Consumer thread exiting on: {}", params.member().getId());
        }
    }

    /**
     * Process a single block by delegating to CHOAM's consume logic.
     * <p>
     * This method is called from the consumer thread and delegates block
     * validation and acceptance to the CHOAM instance via the blockConsumer callback.
     *
     * @param next the block to process
     */
    private void processBlock(HashedCertifiedBlock next) {
        try {
            blockConsumer.accept(next);
        } catch (Throwable t) {
            log.error("Error in block consumer callback for block: {} hash: {} height: {} on: {}",
                      next.block.getBodyCase(), next.hash, next.height(), params.member().getId(), t);
        }
    }
}
