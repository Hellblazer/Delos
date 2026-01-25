/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.validation.graceful;

import com.hellblazer.delos.stereotomy.identifier.Identifier;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Collection of buffered receipt signatures during Byzantine degradation.
 * <p>
 * Buffers signatures from quarantined members while continuing to collect
 * valid signatures. Flushes buffer when threshold met, TTL expired, or
 * Byzantine state changes.
 * </p>
 * <p>
 * Thread-safe: uses concurrent collections for signature tracking.
 * </p>
 *
 * @param <T> Signature type
 * @author hal.hildebrand
 */
public class BufferedReceiptCollection<T> {

    /**
     * Buffered signature entry.
     *
     * @param memberId   Member who provided signature
     * @param signature  Signature data
     * @param bufferedAt Timestamp when signature was buffered
     */
    public record BufferedSignature<T>(
        Identifier memberId,
        T signature,
        Instant bufferedAt
    ) {
        public BufferedSignature {
            Objects.requireNonNull(memberId, "memberId cannot be null");
            Objects.requireNonNull(signature, "signature cannot be null");
            Objects.requireNonNull(bufferedAt, "bufferedAt cannot be null");
        }
    }

    private final List<BufferedSignature<T>> buffer;
    private final Set<Identifier> quarantinedMembers;
    private final GracefulDegradationConfig config;
    private final BufferFlushPolicy flushPolicy;

    private volatile Instant oldestBufferTime;
    private volatile boolean stateChanged;

    public BufferedReceiptCollection(GracefulDegradationConfig config) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.buffer = new CopyOnWriteArrayList<>();
        this.quarantinedMembers = ConcurrentHashMap.newKeySet();
        this.flushPolicy = new BufferFlushPolicy(config);
        this.oldestBufferTime = null;
        this.stateChanged = false;
    }

    /**
     * Add signature to buffer if member is quarantined.
     * <p>
     * If member is not quarantined, signature is not buffered.
     * </p>
     *
     * @param memberId  Member who provided signature
     * @param signature Signature data
     * @return true if signature was buffered
     */
    public boolean bufferIfQuarantined(Identifier memberId, T signature) {
        Objects.requireNonNull(memberId, "memberId cannot be null");
        Objects.requireNonNull(signature, "signature cannot be null");

        // Only buffer if member is quarantined
        if (!quarantinedMembers.contains(memberId)) {
            return false;
        }

        // Check buffer capacity
        if (buffer.size() >= config.maxSignaturesToBuffer()) {
            return false;
        }

        // Buffer signature
        var now = Instant.now();
        buffer.add(new BufferedSignature<>(memberId, signature, now));

        // Update oldest buffer time
        if (oldestBufferTime == null) {
            oldestBufferTime = now;
        }

        return true;
    }

    /**
     * Mark member as quarantined (future signatures will be buffered).
     *
     * @param memberId Member to quarantine
     */
    public void quarantineMember(Identifier memberId) {
        Objects.requireNonNull(memberId, "memberId cannot be null");

        var added = quarantinedMembers.add(memberId);
        if (added) {
            stateChanged = true;
        }
    }

    /**
     * Release member from quarantine (future signatures will not be buffered).
     *
     * @param memberId Member to release
     */
    public void releaseMember(Identifier memberId) {
        Objects.requireNonNull(memberId, "memberId cannot be null");

        var removed = quarantinedMembers.remove(memberId);
        if (removed) {
            stateChanged = true;
        }
    }

    /**
     * Check if buffer should be flushed.
     *
     * @param threshold Validation threshold
     * @return Flush decision
     */
    public BufferFlushPolicy.FlushDecision shouldFlush(int threshold) {
        return flushPolicy.evaluateFlush(buffer.size(), threshold, oldestBufferTime, stateChanged);
    }

    /**
     * Flush buffer and return all buffered signatures.
     * <p>
     * Clears buffer, resets state change flag, and returns all buffered entries.
     * </p>
     *
     * @return List of buffered signatures
     */
    public List<BufferedSignature<T>> flush() {
        var flushed = List.copyOf(buffer);
        buffer.clear();
        oldestBufferTime = null;
        stateChanged = false;
        return flushed;
    }

    /**
     * Clear buffer without returning signatures (discard).
     */
    public void clear() {
        buffer.clear();
        oldestBufferTime = null;
        stateChanged = false;
    }

    /**
     * Get buffered signature count.
     *
     * @return Number of buffered signatures
     */
    public int getBufferedCount() {
        return buffer.size();
    }

    /**
     * Check if buffer is empty.
     *
     * @return true if buffer is empty
     */
    public boolean isEmpty() {
        return buffer.isEmpty();
    }

    /**
     * Get oldest buffer time.
     *
     * @return Timestamp of oldest buffered signature (null if empty)
     */
    public Instant getOldestBufferTime() {
        return oldestBufferTime;
    }

    /**
     * Check if Byzantine state changed since last flush.
     *
     * @return true if state changed
     */
    public boolean hasStateChanges() {
        return stateChanged;
    }

    /**
     * Get quarantined member count.
     *
     * @return Number of quarantined members
     */
    public int getQuarantinedCount() {
        return quarantinedMembers.size();
    }

    /**
     * Check if member is quarantined.
     *
     * @param memberId Member to check
     * @return true if member is quarantined
     */
    public boolean isQuarantined(Identifier memberId) {
        return quarantinedMembers.contains(memberId);
    }
}
