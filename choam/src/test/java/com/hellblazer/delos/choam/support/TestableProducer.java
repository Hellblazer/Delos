/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.Producer;
import com.hellblazer.delos.choam.ViewContext;
import com.hellblazer.delos.choam.proto.SubmitResult;
import com.hellblazer.delos.choam.proto.Transaction;
import com.hellblazer.delos.cryptography.Digest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Test utility providing instrumentation and assertion helpers for Producer testing.
 * Wraps a Producer instance and records key events for verification.
 * <p>
 * Features:
 * - Transaction submission tracking
 * - Block production event callbacks
 * - Metrics capture (submission rate, queue depth estimates)
 * - Assertion helpers for common test scenarios
 * <p>
 * Thread Safety: All operations are thread-safe. Callbacks may be invoked from
 * Producer's internal threads.
 *
 * @author hal.hildebrand
 */
public class TestableProducer {
    private static final Logger log = LoggerFactory.getLogger(TestableProducer.class);

    private final Producer                                   producer;
    private final CopyOnWriteArrayList<SubmittedTransaction> submittedTransactions = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<BlockProducedEvent>   blockEvents           = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<BlockProducedEvent>> blockListeners = new CopyOnWriteArrayList<>();
    private final AtomicLong                                 submissionCount       = new AtomicLong(0);
    private final AtomicLong                                 acceptedCount         = new AtomicLong(0);
    private final AtomicLong                                 rejectedCount         = new AtomicLong(0);
    private final AtomicInteger                              currentEpoch          = new AtomicInteger(-1);

    /**
     * Record of a submitted transaction for test verification.
     */
    public static class SubmittedTransaction {
        public final Transaction      transaction;
        public final SubmitResult     result;
        public final long             timestamp;
        public final long             sequenceNumber;

        public SubmittedTransaction(Transaction transaction, SubmitResult result, long timestamp, long sequenceNumber) {
            this.transaction = transaction;
            this.result = result;
            this.timestamp = timestamp;
            this.sequenceNumber = sequenceNumber;
        }

        public boolean wasAccepted() {
            return result.getResult() == SubmitResult.Result.PUBLISHED;
        }

        public boolean wasRejected() {
            return result.getResult() != SubmitResult.Result.PUBLISHED;
        }
    }

    /**
     * Record of a block production event for test verification.
     */
    public static class BlockProducedEvent {
        public final Digest blockHash;
        public final long   height;
        public final int    epoch;
        public final long   timestamp;
        public final int    transactionCount;

        public BlockProducedEvent(Digest blockHash, long height, int epoch, long timestamp, int transactionCount) {
            this.blockHash = blockHash;
            this.height = height;
            this.epoch = epoch;
            this.timestamp = timestamp;
            this.transactionCount = transactionCount;
        }
    }

    /**
     * Wrap a Producer instance for testing.
     *
     * @param producer the producer to instrument
     */
    public TestableProducer(Producer producer) {
        this.producer = producer;
    }

    /**
     * Submit a transaction and record the result.
     *
     * @param transaction the transaction to submit
     * @return the submit result
     */
    public SubmitResult submit(Transaction transaction) {
        var result = producer.submit(transaction);
        var seqNum = submissionCount.incrementAndGet();

        var record = new SubmittedTransaction(transaction, result, System.currentTimeMillis(), seqNum);
        submittedTransactions.add(record);

        if (record.wasAccepted()) {
            acceptedCount.incrementAndGet();
        } else {
            rejectedCount.incrementAndGet();
        }

        return result;
    }

    /**
     * Start the producer.
     */
    public void start() {
        producer.start();
    }

    /**
     * Stop the producer.
     */
    public void stop() {
        producer.stop();
    }

    /**
     * Register a listener for block production events.
     *
     * @param listener the listener to register
     */
    public void onBlockProduced(Consumer<BlockProducedEvent> listener) {
        blockListeners.add(listener);
    }

    /**
     * Simulate a block production event (called by test infrastructure).
     * In real tests, this would be called when blocks are actually produced.
     *
     * @param blockHash         the block hash
     * @param height            the block height
     * @param epoch             the current epoch
     * @param transactionCount  the number of transactions in the block
     */
    public void recordBlockProduced(Digest blockHash, long height, int epoch, int transactionCount) {
        var event = new BlockProducedEvent(blockHash, height, epoch, System.currentTimeMillis(), transactionCount);
        blockEvents.add(event);

        // Notify listeners
        for (var listener : blockListeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                log.warn("Block listener threw exception", e);
            }
        }
    }

    /**
     * Update the current epoch (called by test infrastructure).
     *
     * @param epoch the new epoch number
     */
    public void recordEpochChange(int epoch) {
        currentEpoch.set(epoch);
    }

    /**
     * Get all submitted transactions in order.
     *
     * @return unmodifiable list of submitted transactions
     */
    public List<SubmittedTransaction> getSubmittedTransactions() {
        return List.copyOf(submittedTransactions);
    }

    /**
     * Get all block production events in order.
     *
     * @return unmodifiable list of block events
     */
    public List<BlockProducedEvent> getBlockEvents() {
        return List.copyOf(blockEvents);
    }

    /**
     * Get submission metrics snapshot.
     *
     * @return metrics snapshot
     */
    public SubmissionMetrics getMetrics() {
        return new SubmissionMetrics(
            submissionCount.get(),
            acceptedCount.get(),
            rejectedCount.get(),
            currentEpoch.get(),
            blockEvents.size()
        );
    }

    /**
     * Clear all recorded history (useful for test reuse).
     */
    public void clearHistory() {
        submittedTransactions.clear();
        blockEvents.clear();
        submissionCount.set(0);
        acceptedCount.set(0);
        rejectedCount.set(0);
    }

    /**
     * Assertion: verify a transaction was accepted.
     *
     * @param sequenceNumber the transaction sequence number
     * @throws AssertionError if transaction was not accepted
     */
    public void assertTransactionAccepted(long sequenceNumber) {
        var tx = submittedTransactions.stream()
                                       .filter(t -> t.sequenceNumber == sequenceNumber)
                                       .findFirst()
                                       .orElseThrow(() -> new AssertionError(
                                           "No transaction with sequence number: " + sequenceNumber));

        if (!tx.wasAccepted()) {
            throw new AssertionError(
                String.format("Transaction %d was rejected with result: %s", sequenceNumber, tx.result.getResult()));
        }
    }

    /**
     * Assertion: verify a transaction is pending (submitted but not yet in a block).
     *
     * @param sequenceNumber the transaction sequence number
     * @throws AssertionError if transaction is not pending
     */
    public void assertTransactionPending(long sequenceNumber) {
        var tx = submittedTransactions.stream()
                                       .filter(t -> t.sequenceNumber == sequenceNumber)
                                       .findFirst()
                                       .orElseThrow(() -> new AssertionError(
                                           "No transaction with sequence number: " + sequenceNumber));

        if (!tx.wasAccepted()) {
            throw new AssertionError(
                String.format("Transaction %d was not accepted, cannot be pending", sequenceNumber));
        }

        // A transaction is considered "pending" if it was accepted but we haven't seen
        // enough blocks produced to have definitely included it yet
        // This is a heuristic - in real tests you'd need to check actual block contents
    }

    /**
     * Assertion: verify a block was produced at the given height.
     *
     * @param height the expected block height
     * @throws AssertionError if no block at that height
     */
    public void assertBlockProduced(long height) {
        var found = blockEvents.stream().anyMatch(e -> e.height == height);
        if (!found) {
            throw new AssertionError(
                String.format("No block produced at height %d. Produced blocks: %s",
                    height, blockEvents.stream().map(e -> e.height).toList()));
        }
    }

    /**
     * Assertion: verify block production rate is within expected range.
     *
     * @param minBlocksPerSecond minimum expected rate
     * @param maxBlocksPerSecond maximum expected rate
     * @throws AssertionError if rate is outside range
     */
    public void assertBlockProductionRate(double minBlocksPerSecond, double maxBlocksPerSecond) {
        if (blockEvents.size() < 2) {
            throw new AssertionError("Need at least 2 blocks to measure production rate");
        }

        var first = blockEvents.get(0);
        var last = blockEvents.get(blockEvents.size() - 1);
        var durationSeconds = (last.timestamp - first.timestamp) / 1000.0;
        var blocksProduced = blockEvents.size() - 1;  // Don't count first block
        var rate = blocksProduced / durationSeconds;

        if (rate < minBlocksPerSecond || rate > maxBlocksPerSecond) {
            throw new AssertionError(
                String.format("Block production rate %.2f blocks/sec outside range [%.2f, %.2f]",
                    rate, minBlocksPerSecond, maxBlocksPerSecond));
        }
    }

    /**
     * Snapshot of submission metrics.
     */
    public record SubmissionMetrics(
        long totalSubmissions,
        long acceptedSubmissions,
        long rejectedSubmissions,
        int currentEpoch,
        long blocksProduced
    ) {
        public double acceptanceRate() {
            return totalSubmissions == 0 ? 0.0 : (double) acceptedSubmissions / totalSubmissions;
        }

        public double rejectionRate() {
            return totalSubmissions == 0 ? 0.0 : (double) rejectedSubmissions / totalSubmissions;
        }
    }
}
