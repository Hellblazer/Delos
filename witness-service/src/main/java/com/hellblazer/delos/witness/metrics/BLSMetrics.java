/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.metrics;

/**
 * Metrics interface for BLS signature operations in witness service.
 * Tracks receipt latency, rejection reasons, and accumulator state.
 *
 * @author hal.hildebrand
 */
public interface BLSMetrics {

    /**
     * Record signature receipt latency in microseconds.
     * Measures time from signature arrival to accumulation completion.
     *
     * @param latencyMicros Latency in microseconds
     */
    void recordReceiptLatency(long latencyMicros);

    /**
     * Increment counter for signatures rejected due to epoch mismatch.
     */
    void incrementRejectedEpoch();

    /**
     * Increment counter for signatures rejected due to viewRef mismatch.
     */
    void incrementRejectedViewRef();

    /**
     * Increment counter for late signatures (arrived after threshold met).
     */
    void incrementRejectedLate();

    /**
     * Increment counter for duplicate signatures from same member.
     */
    void incrementRejectedDuplicate();

    /**
     * Set current number of active accumulators.
     *
     * @param count Active accumulator count
     */
    void setActiveAccumulators(int count);

    /**
     * Record completion of an accumulation (threshold met).
     */
    void recordCompletedAccumulation();

    /**
     * Increment counter for accumulators created.
     */
    void incrementAccumulatorCreated();

    /**
     * Increment counter for accumulators discarded before threshold.
     */
    void incrementAccumulatorDiscarded();

    /**
     * Record time to reach threshold (microseconds).
     *
     * @param durationMicros Time from first signature to threshold
     */
    void recordTimeToThreshold(long durationMicros);

    /**
     * Record threshold achievement percentage (0.0-1.0).
     *
     * @param percentage Achievement percentage
     */
    void recordThresholdPercentage(double percentage);

    /**
     * Set current number of buffered signatures.
     *
     * @param count Buffered signature count
     */
    void setBufferedSignatures(int count);

    /**
     * Record buffer drain latency (microseconds).
     *
     * @param latencyMicros Drain operation latency
     */
    void recordBufferDrainLatency(long latencyMicros);

    /**
     * Increment counter for aggregations performed.
     */
    void incrementAggregationsPerformed();

    /**
     * Record aggregation batch size (number of signatures).
     *
     * @param batchSize Batch size
     */
    void recordAggregationBatchSize(int batchSize);

    /**
     * Record aggregate size in bytes.
     *
     * @param sizeBytes Aggregate size
     */
    void recordAggregateSize(int sizeBytes);

    /**
     * Record compression ratio (individual bytes / aggregate bytes).
     *
     * @param ratio Compression ratio
     */
    void recordCompressionRatio(double ratio);

    /**
     * Increment counter for aggregation errors.
     */
    void incrementAggregationErrors();

    /**
     * Record committee participation (signer count).
     *
     * @param signerCount Number of signers
     */
    void recordCommitteeParticipation(int signerCount);

    /**
     * Record signer bitmap overhead in bytes.
     *
     * @param bitmapBytes Bitmap size
     */
    void recordSignerBitmapOverhead(int bitmapBytes);

    /**
     * Record empty accumulator cleanup.
     */
    void recordEmptyAccumulatorCleanup();
}
