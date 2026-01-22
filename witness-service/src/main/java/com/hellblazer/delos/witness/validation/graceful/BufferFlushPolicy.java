/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.validation.graceful;

import java.time.Instant;
import java.util.Objects;

/**
 * Policy for flushing buffered signatures.
 * <p>
 * Flush policies:
 * - THRESHOLD_MET: Buffer has collected enough signatures to meet validation threshold
 * - TTL_EXPIRED: Oldest buffered signature has exceeded TTL
 * - STATE_CHANGE: Byzantine member state changed (recovery or new detection)
 * - MANUAL: Explicit flush requested
 * </p>
 *
 * @author hal.hildebrand
 */
public class BufferFlushPolicy {

    /**
     * Flush decision result.
     *
     * @param shouldFlush Whether buffer should be flushed
     * @param reason      Reason for flush decision
     */
    public record FlushDecision(boolean shouldFlush, FlushReason reason) {
        public FlushDecision {
            Objects.requireNonNull(reason, "reason cannot be null");
        }

        public static FlushDecision noFlush() {
            return new FlushDecision(false, FlushReason.NOT_NEEDED);
        }

        public static FlushDecision flush(FlushReason reason) {
            return new FlushDecision(true, reason);
        }
    }

    /**
     * Reason for flush decision.
     */
    public enum FlushReason {
        /**
         * Not needed (buffer still accumulating).
         */
        NOT_NEEDED,

        /**
         * Threshold met (enough signatures collected).
         */
        THRESHOLD_MET,

        /**
         * TTL expired (oldest signature too old).
         */
        TTL_EXPIRED,

        /**
         * State change (Byzantine member recovery or new detection).
         */
        STATE_CHANGE,

        /**
         * Manual flush requested.
         */
        MANUAL
    }

    private final GracefulDegradationConfig config;

    public BufferFlushPolicy(GracefulDegradationConfig config) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
    }

    /**
     * Evaluate whether buffer should be flushed.
     *
     * @param bufferedCount       Number of buffered signatures
     * @param threshold           Validation threshold
     * @param oldestBufferTime    Timestamp of oldest buffered signature (null if empty)
     * @param stateChangeOccurred Whether Byzantine state changed
     * @return Flush decision
     */
    public FlushDecision evaluateFlush(
        int bufferedCount,
        int threshold,
        Instant oldestBufferTime,
        boolean stateChangeOccurred
    ) {
        if (bufferedCount < 0) {
            throw new IllegalArgumentException("bufferedCount must be >= 0, got: " + bufferedCount);
        }
        if (threshold <= 0) {
            throw new IllegalArgumentException("threshold must be > 0, got: " + threshold);
        }

        // Empty buffer: no flush
        if (bufferedCount == 0 || oldestBufferTime == null) {
            return FlushDecision.noFlush();
        }

        // State change: flush immediately
        if (stateChangeOccurred) {
            return FlushDecision.flush(FlushReason.STATE_CHANGE);
        }

        // Threshold met: flush
        if (bufferedCount >= threshold) {
            return FlushDecision.flush(FlushReason.THRESHOLD_MET);
        }

        // TTL expired: flush
        var bufferAgeMs = java.time.Duration.between(oldestBufferTime, Instant.now()).toMillis();
        if (bufferAgeMs >= config.signatureBufferTTLMs()) {
            return FlushDecision.flush(FlushReason.TTL_EXPIRED);
        }

        // Otherwise, keep buffering
        return FlushDecision.noFlush();
    }

    /**
     * Check if threshold is met.
     *
     * @param bufferedCount Number of buffered signatures
     * @param threshold     Validation threshold
     * @return true if threshold met
     */
    public boolean hasReachedThreshold(int bufferedCount, int threshold) {
        return bufferedCount >= threshold;
    }

    /**
     * Check if TTL expired.
     *
     * @param oldestBufferTime Timestamp of oldest buffered signature
     * @return true if TTL expired
     */
    public boolean isExpired(Instant oldestBufferTime) {
        if (oldestBufferTime == null) {
            return false;
        }

        var bufferAgeMs = java.time.Duration.between(oldestBufferTime, Instant.now()).toMillis();
        return bufferAgeMs >= config.signatureBufferTTLMs();
    }
}
