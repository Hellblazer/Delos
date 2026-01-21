/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation;

import com.hellblazer.delos.cryptography.bls.BLSAggregate;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Factory for managing SignatureAccumulators across multiple events.
 * <p>
 * Features:
 * - Lazy creation of accumulators per event coordinate
 * - Automatic cleanup of expired accumulators (>10 minutes old)
 * - Thread-safe concurrent access
 * - Metrics tracking
 * <p>
 * Design:
 * - Lock-free using ConcurrentHashMap
 * - Virtual thread compatible (no synchronized blocks)
 * - Immutable aggregates once created
 * <p>
 * Lifecycle: Create factory once, accumulate across events, cleanup expired entries.
 *
 * @author hal.hildebrand
 */
public final class BLSReceiptAggregator {

    /**
     * Metrics for factory health and activity monitoring.
     *
     * @param activeAccumulators   Current number of active accumulators
     * @param totalAccumulated     Total signatures accumulated (lifetime)
     * @param aggregationsCreated  Total aggregations created (lifetime)
     * @param cleanupCount         Number of cleanup operations performed
     */
    public record AggregatorMetrics(
        int activeAccumulators,
        long totalAccumulated,
        long aggregationsCreated,
        long cleanupCount
    ) {}

    /**
     * Default expiration for idle accumulators (10 minutes).
     */
    private static final Duration DEFAULT_EXPIRATION = Duration.ofMinutes(10);

    // Thread-safe state
    private final ConcurrentHashMap<EventCoordinates, SignatureAccumulator> accumulators = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<EventCoordinates, BLSAggregate> aggregates = new ConcurrentHashMap<>();

    // Metrics (lock-free counters)
    private final AtomicLong totalAccumulated = new AtomicLong(0);
    private final AtomicLong aggregationsCreated = new AtomicLong(0);
    private final AtomicLong cleanupCount = new AtomicLong(0);

    // Configuration
    private final Duration expirationDuration;

    /**
     * Create aggregator factory with default expiration (10 minutes).
     */
    public BLSReceiptAggregator() {
        this(DEFAULT_EXPIRATION);
    }

    /**
     * Create aggregator factory with custom expiration duration.
     *
     * @param expirationDuration Duration after which idle accumulators are cleaned up
     */
    public BLSReceiptAggregator(Duration expirationDuration) {
        this.expirationDuration = Objects.requireNonNull(expirationDuration, "expirationDuration cannot be null");
    }

    /**
     * Accumulate a BLS signature for an event.
     * <p>
     * Lazily creates accumulator if needed, then delegates to SignatureAccumulator.
     * Thread-safe and idempotent per (event, member) pair.
     *
     * @param event          Event coordinates being witnessed
     * @param member         Committee member identifier
     * @param committeeIndex Member's index in committee (for bitmap)
     * @param signature      BLS signature from member
     * @param threshold      Required signature count (M)
     * @param epoch          Fireflies epoch
     * @return AccumulationResult indicating outcome
     * @throws NullPointerException     if any parameter is null
     * @throws IllegalArgumentException if committeeIndex < 0, threshold < 1, or epoch < 0
     */
    public AccumulationResult accumulate(EventCoordinates event, Identifier member, int committeeIndex,
                                         BLSSignature signature, int threshold, long epoch) {
        Objects.requireNonNull(event, "event cannot be null");
        Objects.requireNonNull(member, "member cannot be null");
        Objects.requireNonNull(signature, "signature cannot be null");
        if (committeeIndex < 0) {
            throw new IllegalArgumentException("committeeIndex must be >= 0, got: " + committeeIndex);
        }
        if (threshold < 1) {
            throw new IllegalArgumentException("threshold must be >= 1, got: " + threshold);
        }
        if (epoch < 0) {
            throw new IllegalArgumentException("epoch must be >= 0, got: " + epoch);
        }

        // Lazy accumulator creation (thread-safe via computeIfAbsent)
        var accumulator = accumulators.computeIfAbsent(event,
                                                       k -> new SignatureAccumulator(event, threshold, epoch, null));

        // Delegate to accumulator (passing epoch and null viewRef for validation)
        var result = accumulator.accumulate(member, committeeIndex, signature, epoch, null);

        // Update metrics on successful accumulation
        if (result.isSuccess()) {
            totalAccumulated.incrementAndGet();
        }

        // If threshold met, cache the aggregate
        if (result instanceof AccumulationResult.ThresholdMet thresholdMet) {
            var snapshot = thresholdMet.snapshot();
            var aggregate = createAggregate(snapshot);
            aggregates.put(event, aggregate);
        }

        return result;
    }

    /**
     * Aggregate signatures for an event if threshold met.
     * <p>
     * Returns successful aggregation if threshold reached, insufficient if below threshold,
     * or failed if no accumulator exists.
     *
     * @param event Event coordinates to aggregate
     * @return AggregationResult indicating outcome
     * @throws NullPointerException if event is null
     */
    public AggregationResult aggregate(EventCoordinates event) {
        Objects.requireNonNull(event, "event cannot be null");

        // Check if aggregate already created
        var existingAggregate = aggregates.get(event);
        if (existingAggregate != null) {
            aggregationsCreated.incrementAndGet();
            return new AggregationResult.Aggregated(existingAggregate.aggregatedSignature(),
                                                    existingAggregate.signerBitmap());
        }

        // Get accumulator
        var accumulator = accumulators.get(event);
        if (accumulator == null) {
            return new AggregationResult.AggregationFailed("No accumulator found for event: " + event);
        }

        // Check if threshold met
        var snapshot = accumulator.snapshot();
        if (!snapshot.thresholdMet()) {
            return new AggregationResult.InsufficientSignatures(snapshot.signerCount(), snapshot.threshold());
        }

        // Create aggregate
        var aggregate = createAggregate(snapshot);
        aggregates.put(event, aggregate);
        aggregationsCreated.incrementAndGet();

        return new AggregationResult.Aggregated(aggregate.aggregatedSignature(), aggregate.signerBitmap());
    }

    /**
     * Get aggregate for an event if available.
     * <p>
     * Returns empty if threshold not yet met or event unknown.
     *
     * @param event Event coordinates
     * @return Optional containing aggregate if available
     * @throws NullPointerException if event is null
     */
    public Optional<BLSAggregate> getAggregate(EventCoordinates event) {
        Objects.requireNonNull(event, "event cannot be null");
        return Optional.ofNullable(aggregates.get(event));
    }

    /**
     * Get current factory metrics.
     *
     * @return Snapshot of current metrics
     */
    public AggregatorMetrics metrics() {
        return new AggregatorMetrics(
            accumulators.size(),
            totalAccumulated.get(),
            aggregationsCreated.get(),
            cleanupCount.get()
        );
    }

    /**
     * Clean up expired accumulators (>expirationDuration old).
     * <p>
     * Safe to call concurrently. Removes accumulators that haven't been accessed recently.
     *
     * @return Number of accumulators cleaned up
     */
    public int cleanup() {
        var now = Instant.now();
        var threshold = now.minus(expirationDuration);

        var removed = 0;
        var iterator = accumulators.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var accumulator = entry.getValue();
            var snapshot = accumulator.snapshot();

            // Remove if created before expiration threshold
            if (snapshot.createdAt().isBefore(threshold)) {
                iterator.remove();
                aggregates.remove(entry.getKey()); // Also remove any aggregate
                removed++;
            }
        }

        if (removed > 0) {
            cleanupCount.incrementAndGet();
        }

        return removed;
    }

    /**
     * Clear all accumulators and aggregates.
     * <p>
     * For testing and reset scenarios.
     */
    public void clear() {
        accumulators.clear();
        aggregates.clear();
    }

    // ========== Private Helpers ==========

    /**
     * Create BLS aggregate from accumulator snapshot.
     */
    private BLSAggregate createAggregate(SignatureAccumulator.Snapshot snapshot) {
        var signatures = snapshot.signatureList();
        var indices = snapshot.signerIndices();

        if (signatures.isEmpty()) {
            throw new IllegalStateException("Cannot aggregate empty signature list");
        }

        return BLSAggregate.aggregate(signatures, indices);
    }
}
