/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.validation.graceful;

import java.util.Objects;

/**
 * Orchestrates signature buffering during Byzantine degradation.
 * <p>
 * Determines when to enable buffering and when to flush buffered signatures.
 * </p>
 * <p>
 * Buffering is enabled when:
 * - Byzantine members detected (byzantineCount > 0)
 * - Buffer capacity not exceeded (maxSignaturesToBuffer)
 * </p>
 * <p>
 * Buffer is flushed when:
 * - Threshold reached (enough signatures to validate)
 * - TTL expired (signatureBufferTTLMs elapsed)
 * - State change (Byzantine member recovered or new Byzantine detected)
 * </p>
 *
 * @author hal.hildebrand
 */
public class SignatureBufferingOrchestrator {

    private final GracefulDegradationConfig config;

    public SignatureBufferingOrchestrator(GracefulDegradationConfig config) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
    }

    /**
     * Determine if signature buffering should be enabled.
     * <p>
     * Buffering is enabled when Byzantine members are present to allow
     * collecting enough valid signatures despite quarantined members.
     * </p>
     *
     * @param byzantineCount Number of quarantined/Byzantine members
     * @param totalMembers   Total member count in view
     * @return true if buffering should be enabled
     */
    public boolean shouldEnableBuffering(int byzantineCount, int totalMembers) {
        if (byzantineCount < 0) {
            throw new IllegalArgumentException("byzantineCount must be >= 0, got: " + byzantineCount);
        }
        if (totalMembers <= 0) {
            throw new IllegalArgumentException("totalMembers must be > 0, got: " + totalMembers);
        }

        // Enable buffering when Byzantine members present
        return byzantineCount > 0;
    }

    /**
     * Determine if buffered signatures should be flushed.
     * <p>
     * Flush conditions:
     * - Buffer has reached validation threshold
     * - Buffer TTL has expired
     * - Byzantine member state change (recovery or new detection)
     * </p>
     *
     * @param bufferedCount     Number of buffered signatures
     * @param threshold         Validation threshold
     * @param bufferAgeMs       Age of oldest buffered signature (milliseconds)
     * @param stateChangeOccurred Whether Byzantine state changed since buffering started
     * @return true if buffer should be flushed
     */
    public boolean shouldFlushBuffer(
        int bufferedCount,
        int threshold,
        long bufferAgeMs,
        boolean stateChangeOccurred
    ) {
        if (bufferedCount < 0) {
            throw new IllegalArgumentException("bufferedCount must be >= 0, got: " + bufferedCount);
        }
        if (threshold <= 0) {
            throw new IllegalArgumentException("threshold must be > 0, got: " + threshold);
        }
        if (bufferAgeMs < 0) {
            throw new IllegalArgumentException("bufferAgeMs must be >= 0, got: " + bufferAgeMs);
        }

        // Flush if threshold reached
        if (bufferedCount >= threshold) {
            return true;
        }

        // Flush if TTL expired
        if (bufferAgeMs >= config.signatureBufferTTLMs()) {
            return true;
        }

        // Flush if Byzantine state changed
        if (stateChangeOccurred) {
            return true;
        }

        // Otherwise, keep buffering
        return false;
    }

    /**
     * Check if buffer capacity would be exceeded.
     *
     * @param bufferedCount Current buffer count
     * @return true if capacity would be exceeded
     */
    public boolean isBufferCapacityExceeded(int bufferedCount) {
        return bufferedCount >= config.maxSignaturesToBuffer();
    }

    /**
     * Get maximum buffer capacity from config.
     *
     * @return Maximum signatures to buffer
     */
    public int getMaxBufferCapacity() {
        return config.maxSignaturesToBuffer();
    }

    /**
     * Get buffer TTL from config.
     *
     * @return Signature buffer TTL in milliseconds
     */
    public long getBufferTTLMs() {
        return config.signatureBufferTTLMs();
    }
}
