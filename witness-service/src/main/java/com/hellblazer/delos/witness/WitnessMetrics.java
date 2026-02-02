/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
/*
 * Portions copyright (c) 2025, Hal Hildebrand.
 * Modifications made under GNU Affero General Public License.
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 */
package com.hellblazer.delos.witness;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dropwizard Metrics instrumentation for KERI witness network operations.
 * <p>
 * Provides 6 instrumented operations:
 * <ul>
 *   <li>receiptCollectionLatency - Time from event signing to threshold achieved (Histogram, ms)</li>
 *   <li>thresholdAchievementRate - Percentage of events reaching threshold (Gauge, 0-1)</li>
 *   <li>viewChangeCoordinationTime - Duration of view change drain period (Timer, ms)</li>
 *   <li>committeeSelectionTime - Latency of bftSubset() call (Timer, μs)</li>
 *   <li>inFlightCollections - Number of active receipt collections (Gauge, count)</li>
 *   <li>receiptGossipLatency - Time from threshold to propagation (Histogram, ms)</li>
 * </ul>
 * <p>
 * Phase 1B-3 BLS Integration Metrics:
 * <ul>
 *   <li>blsKeysRegistered - Number of registered BLS keys (Gauge, count)</li>
 *   <li>blsKeysCoverage - Percentage of members with BLS keys (Gauge, 0-100)</li>
 *   <li>transitionReadiness - Phase transition readiness (Gauge, 0/1)</li>
 *   <li>transitionInProgress - Phase transition active (Gauge, 0/1)</li>
 *   <li>byzantineShunned - Byzantine members shunned (Counter)</li>
 *   <li>blsFailures - BLS validation failures (Counter)</li>
 *   <li>registrationAttempts - BLS key registration attempts (Counter)</li>
 *   <li>registrationSuccesses - BLS key registration successes (Counter)</li>
 * </ul>
 * </p>
 */
public class WitnessMetrics {

    private final MeterRegistry registry;
    private final DistributionSummary receiptCollectionLatency;
    private final DistributionSummary receiptGossipLatency;
    private final Timer viewChangeCoordinationTime;
    private final Timer committeeSelectionTime;
    private final AtomicReference<Double> thresholdAchievementRate = new AtomicReference<>(0.0);
    private final AtomicInteger inFlightCollections = new AtomicInteger(0);

    // Phase 1B-3 BLS Integration metrics
    private final AtomicInteger blsKeysRegistered = new AtomicInteger(0);
    private final AtomicReference<Double> blsKeysCoverage = new AtomicReference<>(0.0);
    private final AtomicInteger transitionReadiness = new AtomicInteger(0);
    private final AtomicInteger transitionInProgress = new AtomicInteger(0);
    private final Counter byzantineShunned;
    private final Counter blsFailures;
    private final Counter blsValidations;
    private final Counter registrationAttempts;
    private final Counter registrationSuccesses;

    /**
     * Create metrics with the provided registry.
     *
     * @param registry Micrometer MeterRegistry
     */
    public WitnessMetrics(MeterRegistry registry) {
        this.registry = registry;

        // Register distribution summaries (for latency histograms)
        this.receiptCollectionLatency = DistributionSummary.builder("witness.receipt.collection.latency")
            .baseUnit("milliseconds")
            .register(registry);
        this.receiptGossipLatency = DistributionSummary.builder("witness.receipt.gossip.latency")
            .baseUnit("milliseconds")
            .register(registry);

        // Register timers
        this.viewChangeCoordinationTime = Timer.builder("witness.view.change.coordination.time").register(registry);
        this.committeeSelectionTime = Timer.builder("witness.committee.selection.time").register(registry);

        // Register gauges
        Gauge.builder("witness.threshold.achievement.rate", thresholdAchievementRate, AtomicReference::get)
            .register(registry);
        Gauge.builder("witness.in.flight.collections", inFlightCollections, AtomicInteger::get)
            .register(registry);

        // Register Phase 1B-3 gauges
        Gauge.builder("witness.bls.keys.registered", blsKeysRegistered, AtomicInteger::get)
            .register(registry);
        Gauge.builder("witness.bls.keys.coverage", blsKeysCoverage, AtomicReference::get)
            .register(registry);
        Gauge.builder("witness.transition.readiness", transitionReadiness, AtomicInteger::get)
            .register(registry);
        Gauge.builder("witness.transition.in_progress", transitionInProgress, AtomicInteger::get)
            .register(registry);

        // Register Phase 1B-3 counters
        this.byzantineShunned = Counter.builder("witness.byzantine.shunned").register(registry);
        this.blsFailures = Counter.builder("witness.byzantine.bls_failures").register(registry);
        this.blsValidations = Counter.builder("witness.bls.validations").register(registry);
        this.registrationAttempts = Counter.builder("witness.registration.attempts").register(registry);
        this.registrationSuccesses = Counter.builder("witness.registration.successes").register(registry);
    }

    /**
     * Get the underlying metric registry.
     */
    public MeterRegistry getRegistry() {
        return registry;
    }

    /**
     * Record receipt collection latency (ms).
     * <p>
     * Measures time from event signing to M-of-N threshold achieved.
     * </p>
     *
     * @param latencyMillis latency in milliseconds
     */
    public void recordReceiptCollectionLatency(long latencyMillis) {
        receiptCollectionLatency.record(latencyMillis);
    }

    /**
     * Record receipt gossip latency (ms).
     * <p>
     * Measures time from threshold achievement to cluster-wide propagation.
     * </p>
     *
     * @param latencyMillis latency in milliseconds
     */
    public void recordReceiptGossipLatency(long latencyMillis) {
        receiptGossipLatency.record(latencyMillis);
    }

    /**
     * Record view change coordination duration (ms).
     * <p>
     * Measures drain period + member set propagation during reconfiguration.
     * </p>
     *
     * @param durationNanos duration in nanoseconds
     */
    public void recordViewChangeCoordinationDuration(long durationNanos) {
        viewChangeCoordinationTime.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Record committee selection duration (μs).
     * <p>
     * Measures latency of bftSubset() deterministic committee selection.
     * </p>
     *
     * @param durationNanos duration in nanoseconds
     */
    public void recordCommitteeSelectionDuration(long durationNanos) {
        committeeSelectionTime.record(durationNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Set threshold achievement rate (0-1).
     * <p>
     * Percentage of events reaching M-of-N threshold vs total events.
     * </p>
     *
     * @param rate Achievement rate (0.0 = 0%, 1.0 = 100%)
     */
    public void setThresholdAchievementRate(double rate) {
        thresholdAchievementRate.set(rate);
    }

    /**
     * Set number of in-flight receipt collections.
     * <p>
     * Active collections waiting for M-of-N threshold.
     * </p>
     *
     * @param count Number of in-flight collections
     */
    public void setInFlightCollections(int count) {
        inFlightCollections.set(count);
    }

    // Phase 1B-3 BLS Integration accessors

    /**
     * Set number of registered BLS keys.
     *
     * @param count Number of members with registered BLS keys
     */
    public void setBlsKeysRegistered(int count) {
        blsKeysRegistered.set(count);
    }

    /**
     * Set BLS key coverage percentage.
     *
     * @param coverage Percentage (0-100) of members with BLS keys
     */
    public void setBlsKeysCoverage(double coverage) {
        blsKeysCoverage.set(coverage);
    }

    /**
     * Set transition readiness status.
     *
     * @param ready 1 if ready for transition, 0 otherwise
     */
    public void setTransitionReadiness(int ready) {
        transitionReadiness.set(ready);
    }

    /**
     * Set transition in progress status.
     *
     * @param inProgress 1 if transition active, 0 otherwise
     */
    public void setTransitionInProgress(int inProgress) {
        transitionInProgress.set(inProgress);
    }

    /**
     * Record a Byzantine member being shunned.
     */
    public void recordByzantineShunned() {
        byzantineShunned.increment();
    }

    /**
     * Record a BLS signature validation failure.
     */
    public void recordBlsFailure() {
        blsFailures.increment();
    }

    /**
     * Record a BLS signature validation.
     */
    public void recordBlsValidation() {
        blsValidations.increment();
    }

    /**
     * Record a BLS key registration attempt.
     */
    public void recordRegistrationAttempt() {
        registrationAttempts.increment();
    }

    /**
     * Record a successful BLS key registration.
     */
    public void recordRegistrationSuccess() {
        registrationSuccesses.increment();
    }

    /**
     * Get count of Byzantine members shunned.
     *
     * @return total count of members shunned
     */
    public long getByzantineShunnedCount() {
        return (long) byzantineShunned.count();
    }

    /**
     * Get count of BLS signature validation failures.
     *
     * @return total count of validation failures
     */
    public long getBlsFailuresCount() {
        return (long) blsFailures.count();
    }

    /**
     * Get count of BLS signature validations.
     *
     * @return total count of validations
     */
    public long getBlsValidationsCount() {
        return (long) blsValidations.count();
    }

    /**
     * Get count of BLS key registration attempts.
     *
     * @return total count of attempts
     */
    public long getRegistrationAttemptsCount() {
        return (long) registrationAttempts.count();
    }

    /**
     * Get count of successful BLS key registrations.
     *
     * @return total count of successful registrations
     */
    public long getRegistrationSuccessesCount() {
        return (long) registrationSuccesses.count();
    }
}
