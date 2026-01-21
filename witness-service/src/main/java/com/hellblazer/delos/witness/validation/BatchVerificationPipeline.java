/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.validation;

import com.hellblazer.delos.cryptography.bls.BLSAggregate;
import com.hellblazer.delos.cryptography.bls.BLSProvider;
import com.hellblazer.delos.witness.aggregation.ValidationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Batch verification pipeline for witness receipts.
 * <p>
 * Leverages BLSProvider.batchVerifyAggregates() for 2-4x speedup on batch sizes of 10+.
 * <p>
 * Thread-safe: All methods are reentrant and use atomic counters.
 * <p>
 * Phase 1C-1-C implementation.
 * <p>
 * Usage:
 * <pre>{@code
 * var pipeline = new BatchVerificationPipeline(BLSProvider.getDefault());
 *
 * var committeeKeys = witnessContext.getCommitteeBLSKeys();
 * var results = pipeline.verifyBatch(committeeKeys, receipts, messages);
 *
 * for (var result : results) {
 *     switch (result) {
 *         case Valid(var agg) -> processValidAggregate(agg);
 *         case ValidationFailed(var reason) -> log.warn("Validation failed: {}", reason);
 *         default -> handleOtherResult(result);
 *     }
 * }
 * }</pre>
 */
public class BatchVerificationPipeline {

    private final BLSProvider provider;
    private final AtomicLong totalBatches = new AtomicLong();
    private final AtomicLong totalVerifications = new AtomicLong();
    private final AtomicLong totalLatencyMicros = new AtomicLong();

    /**
     * Create batch verification pipeline with specified BLS provider.
     *
     * @param provider BLS cryptographic provider for verification operations
     * @throws NullPointerException if provider is null
     */
    public BatchVerificationPipeline(BLSProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider cannot be null");
    }

    /**
     * Verify batch of receipts using BLS batch verification.
     * <p>
     * If batch verification succeeds, all receipts are valid.
     * If it fails, falls back to individual verification to identify failures.
     *
     * @param committeeKeys List of committee public keys (same for all receipts)
     * @param receipts      List of aggregates to verify
     * @param messages      List of messages (parallel to receipts)
     * @return List of validation results (parallel to receipts)
     * @throws NullPointerException     if any parameter is null
     * @throws IllegalArgumentException if receipts and messages have different sizes
     */
    public List<ValidationResult> verifyBatch(
        List<byte[]> committeeKeys,
        List<BLSAggregate> receipts,
        List<byte[]> messages
    ) {
        Objects.requireNonNull(committeeKeys, "committeeKeys cannot be null");
        Objects.requireNonNull(receipts, "receipts cannot be null");
        Objects.requireNonNull(messages, "messages cannot be null");

        if (receipts.size() != messages.size()) {
            throw new IllegalArgumentException(
                "receipts and messages must have same size: " +
                receipts.size() + " vs " + messages.size()
            );
        }

        if (receipts.isEmpty()) {
            return List.of();
        }

        var startTime = System.nanoTime();
        var batchSize = receipts.size();

        // Prepare batch: same committee keys for all receipts
        var publicKeysPerAggregate = new ArrayList<List<byte[]>>(batchSize);
        for (int i = 0; i < batchSize; i++) {
            publicKeysPerAggregate.add(committeeKeys);
        }

        // Attempt batch verification
        boolean allValid = provider.batchVerifyAggregates(
            publicKeysPerAggregate,
            messages,
            receipts
        );

        var latencyMicros = (System.nanoTime() - startTime) / 1000;
        totalBatches.incrementAndGet();
        totalVerifications.addAndGet(batchSize);
        totalLatencyMicros.addAndGet(latencyMicros);

        // Map results
        if (allValid) {
            // Fast path: all verified
            var results = new ArrayList<ValidationResult>(batchSize);
            for (var receipt : receipts) {
                results.add(new ValidationResult.Valid(receipt));
            }
            return results;
        } else {
            // Slow path: identify failures via individual verification
            return identifyFailures(committeeKeys, receipts, messages);
        }
    }

    /**
     * Identify which receipts failed by verifying individually.
     * <p>
     * Called when batch verification returns false.
     * Allows us to provide per-receipt validation results.
     *
     * @param committeeKeys Committee public keys
     * @param receipts      List of aggregates
     * @param messages      List of messages (parallel to receipts)
     * @return List of validation results (parallel to receipts)
     */
    private List<ValidationResult> identifyFailures(
        List<byte[]> committeeKeys,
        List<BLSAggregate> receipts,
        List<byte[]> messages
    ) {
        var results = new ArrayList<ValidationResult>(receipts.size());

        for (int i = 0; i < receipts.size(); i++) {
            var receipt = receipts.get(i);
            var message = messages.get(i);

            boolean valid = provider.verifyAggregateWithBitmap(committeeKeys, message, receipt);
            results.add(valid
                ? new ValidationResult.Valid(receipt)
                : new ValidationResult.ValidationFailed("BLS signature verification failed")
            );
        }

        return results;
    }

    /**
     * Get total number of batches processed.
     *
     * @return Number of batches verified
     */
    public long getTotalBatches() {
        return totalBatches.get();
    }

    /**
     * Get total number of individual verifications performed.
     *
     * @return Sum of all batch sizes
     */
    public long getTotalVerifications() {
        return totalVerifications.get();
    }

    /**
     * Get average latency per batch in microseconds.
     *
     * @return Average batch verification time in µs, or 0.0 if no batches processed
     */
    public double getAverageLatencyMicros() {
        long batches = totalBatches.get();
        return batches > 0 ? (double) totalLatencyMicros.get() / batches : 0.0;
    }

    /**
     * Get average verifications per batch.
     *
     * @return Average batch size, or 0.0 if no batches processed
     */
    public double getAverageVerificationsPerBatch() {
        long batches = totalBatches.get();
        return batches > 0 ? (double) totalVerifications.get() / batches : 0.0;
    }

    /**
     * Reset all metrics (for testing).
     */
    public void resetMetrics() {
        totalBatches.set(0);
        totalVerifications.set(0);
        totalLatencyMicros.set(0);
    }
}
