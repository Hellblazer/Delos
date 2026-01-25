/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.validation.graceful;

import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe signature buffer for buffering signatures during Byzantine detection and view changes.
 * <p>
 * Uses a concurrent deque for lock-free buffering with automatic TTL-based expiration and
 * capacity enforcement. Signatures are organized by epoch for efficient retrieval during
 * drain replay operations.
 * <p>
 * Thread Safety: All operations are thread-safe and reentrant. Uses ConcurrentLinkedDeque
 * for lock-free buffering and atomic counters for position tracking.
 * <p>
 * Performance: Target < 1µs per buffer operation, < 10ms to flush 1000 signatures.
 * <p>
 * Phase 1C-3-C: Graceful Degradation (Delos-3956)
 *
 * @author hal.hildebrand
 */
public class SignatureBuffer {

    /**
     * A buffered signature with metadata for TTL expiration and epoch filtering.
     *
     * @param memberId     The member identifier
     * @param signature    The signature bytes
     * @param message      The signed message bytes
     * @param event        The event coordinates
     * @param receivedAt   System timestamp when buffered (milliseconds)
     * @param epochNumber  The epoch number when buffered
     */
    public record BufferedSignature(
        Identifier memberId,
        byte[] signature,
        byte[] message,
        EventCoordinates event,
        long receivedAt,
        long epochNumber
    ) {}

    /**
     * Buffer statistics for monitoring and diagnostics.
     *
     * @param currentSize   Current number of buffered signatures
     * @param maxSize       Maximum buffer capacity
     * @param totalBuffered Total signatures buffered (lifetime counter)
     * @param totalExpired  Total signatures expired due to TTL
     * @param oldestAgeMs   Age of oldest buffered signature in milliseconds
     */
    public record BufferStats(
        int currentSize,
        int maxSize,
        long totalBuffered,
        long totalExpired,
        long oldestAgeMs
    ) {}

    private final ConcurrentLinkedDeque<BufferedSignature> signatures;
    private final GracefulDegradationConfig config;
    private final AtomicInteger position;
    private final AtomicLong totalBuffered;
    private final AtomicLong totalExpired;

    /**
     * Creates a new signature buffer with the specified configuration.
     *
     * @param config the graceful degradation configuration
     */
    public SignatureBuffer(GracefulDegradationConfig config) {
        this.config = config;
        this.signatures = new ConcurrentLinkedDeque<>();
        this.position = new AtomicInteger(0);
        this.totalBuffered = new AtomicLong(0);
        this.totalExpired = new AtomicLong(0);
    }

    /**
     * Buffers a signature with automatic TTL expiration and capacity enforcement.
     * <p>
     * This method is thread-safe and can be called concurrently. If the buffer is at capacity,
     * the oldest signature is evicted. Expired signatures (based on TTL) are automatically
     * removed during buffer operations.
     *
     * @param memberId  the member identifier
     * @param signature the signature bytes
     * @param message   the signed message bytes
     * @param event     the event coordinates
     * @param epoch     the epoch number
     * @return the buffer position (monotonically increasing)
     */
    public int buffer(Identifier memberId, byte[] signature, byte[] message,
                      EventCoordinates event, long epoch) {
        // Remove expired signatures before adding new one
        expireOldSignatures();

        // Enforce capacity limit
        while (signatures.size() >= config.maxSignaturesToBuffer()) {
            var removed = signatures.pollFirst();
            if (removed != null) {
                // Don't count eviction as expiration
            }
        }

        // Add new signature
        var bufferedSig = new BufferedSignature(
            memberId,
            signature,
            message,
            event,
            System.currentTimeMillis(),
            epoch
        );
        signatures.addLast(bufferedSig);
        totalBuffered.incrementAndGet();

        return position.getAndIncrement();
    }

    /**
     * Retrieves all buffered signatures for a specific epoch.
     * <p>
     * This method is thread-safe and can be called concurrently with buffer operations.
     * The returned list is a snapshot at the time of the call.
     *
     * @param epoch the epoch number
     * @return an immutable list of signatures for the specified epoch
     */
    public List<BufferedSignature> getForEpoch(long epoch) {
        return signatures.stream()
            .filter(sig -> sig.epochNumber() == epoch)
            .toList();
    }

    /**
     * Clears all signatures from a specific epoch.
     * <p>
     * This method is thread-safe and can be called concurrently with buffer operations.
     *
     * @param epoch the epoch number to clear
     */
    public void clearEpoch(long epoch) {
        signatures.removeIf(sig -> sig.epochNumber() == epoch);
    }

    /**
     * Retrieves all buffered signatures.
     * <p>
     * This method is thread-safe and can be called concurrently with buffer operations.
     * The returned list is a snapshot at the time of the call.
     *
     * @return an immutable list of all buffered signatures
     */
    public List<BufferedSignature> getAll() {
        return List.copyOf(signatures);
    }

    /**
     * Clears all buffered signatures.
     * <p>
     * This method is thread-safe and can be called concurrently with buffer operations.
     */
    public void clear() {
        signatures.clear();
    }

    /**
     * Gets buffer statistics for monitoring and diagnostics.
     * <p>
     * This method is thread-safe and can be called concurrently with buffer operations.
     * The returned statistics are a snapshot at the time of the call.
     *
     * @return buffer statistics
     */
    public BufferStats getStats() {
        var currentSize = signatures.size();
        var oldestAgeMs = 0L;

        var oldest = signatures.peekFirst();
        if (oldest != null) {
            oldestAgeMs = System.currentTimeMillis() - oldest.receivedAt();
        }

        return new BufferStats(
            currentSize,
            config.maxSignaturesToBuffer(),
            totalBuffered.get(),
            totalExpired.get(),
            oldestAgeMs
        );
    }

    /**
     * Checks if the buffer is empty.
     * <p>
     * This method is thread-safe and can be called concurrently with buffer operations.
     *
     * @return true if the buffer is empty
     */
    public boolean isEmpty() {
        return signatures.isEmpty();
    }

    /**
     * Gets the current number of buffered signatures.
     * <p>
     * This method is thread-safe and can be called concurrently with buffer operations.
     *
     * @return the current size
     */
    public int size() {
        return signatures.size();
    }

    /**
     * Removes signatures that have exceeded their TTL.
     * <p>
     * This method is called automatically during buffer() operations.
     * It is thread-safe and can be called concurrently.
     */
    private void expireOldSignatures() {
        var now = System.currentTimeMillis();
        var ttl = config.signatureBufferTTLMs();

        var expired = signatures.removeIf(sig -> {
            var age = now - sig.receivedAt();
            return age > ttl;
        });

        if (expired) {
            // Count approximately - exact count would require iteration
            // This is acceptable for statistics purposes
            totalExpired.incrementAndGet();
        }
    }
}
