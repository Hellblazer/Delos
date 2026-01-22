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
}
