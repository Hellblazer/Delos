/*
 * Copyright (c) 2024, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
/*
 * Portions copyright (c) 2025, Hal Hildebrand.
 * Modifications made under GNU Affero General Public License.
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 */
package com.hellblazer.delos.witness;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Timer;
import com.codahale.metrics.Counter;
import com.codahale.metrics.MetricRegistry;

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

    private final MetricRegistry registry;
    private final Histogram receiptCollectionLatency;
    private final Histogram receiptGossipLatency;
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
     * @param registry Dropwizard MetricRegistry
     */
    public WitnessMetrics(MetricRegistry registry) {
        this.registry = registry;

        // Register histograms
        this.receiptCollectionLatency = registry.histogram("witness.receipt.collection.latency");
        this.receiptGossipLatency = registry.histogram("witness.receipt.gossip.latency");

        // Register timers
        this.viewChangeCoordinationTime = registry.timer("witness.view.change.coordination.time");
        this.committeeSelectionTime = registry.timer("witness.committee.selection.time");

        // Register gauges
        registry.register("witness.threshold.achievement.rate",
            (Gauge<Double>) thresholdAchievementRate::get);
        registry.register("witness.in.flight.collections",
            (Gauge<Integer>) inFlightCollections::get);

        // Register Phase 1B-3 gauges
        registry.register("witness.bls.keys.registered",
            (Gauge<Integer>) blsKeysRegistered::get);
        registry.register("witness.bls.keys.coverage",
            (Gauge<Double>) blsKeysCoverage::get);
        registry.register("witness.transition.readiness",
            (Gauge<Integer>) transitionReadiness::get);
        registry.register("witness.transition.in_progress",
            (Gauge<Integer>) transitionInProgress::get);

        // Register Phase 1B-3 counters
        this.byzantineShunned = registry.counter("witness.byzantine.shunned");
        this.blsFailures = registry.counter("witness.byzantine.bls_failures");
        this.blsValidations = registry.counter("witness.bls.validations");
        this.registrationAttempts = registry.counter("witness.registration.attempts");
        this.registrationSuccesses = registry.counter("witness.registration.successes");
    }

    /**
     * Get the underlying metric registry.
     */
    public MetricRegistry getRegistry() {
        return registry;
    }

    /**
     * Get histogram for receipt collection latency (ms).
     * <p>
     * Measures time from event signing to M-of-N threshold achieved.
     * </p>
     */
    public Histogram receiptCollectionLatency() {
        return receiptCollectionLatency;
    }

    /**
     * Get histogram for receipt gossip latency (ms).
     * <p>
     * Measures time from threshold achievement to cluster-wide propagation.
     * </p>
     */
    public Histogram receiptGossipLatency() {
        return receiptGossipLatency;
    }

    /**
     * Get timer for view change coordination (ms).
     * <p>
     * Measures drain period + member set propagation during reconfiguration.
     * </p>
     */
    public Timer viewChangeCoordinationTime() {
        return viewChangeCoordinationTime;
    }

    /**
     * Get timer for committee selection (μs).
     * <p>
     * Measures latency of bftSubset() deterministic committee selection.
     * </p>
     */
    public Timer committeeSelectionTime() {
        return committeeSelectionTime;
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
     * Get Byzantine shunned counter.
     *
     * @return counter for members shunned due to Byzantine behavior
     */
    public Counter getByzantineShunnedCounter() {
        return byzantineShunned;
    }

    /**
     * Get BLS failures counter.
     *
     * @return counter for BLS signature validation failures
     */
    public Counter getBlsFailuresCounter() {
        return blsFailures;
    }

    /**
     * Get BLS validations counter.
     *
     * @return counter for total BLS signature validations
     */
    public Counter getBlsValidationsCounter() {
        return blsValidations;
    }

    /**
     * Get registration attempts counter.
     *
     * @return counter for BLS key registration attempts
     */
    public Counter getRegistrationAttemptsCounter() {
        return registrationAttempts;
    }

    /**
     * Get registration successes counter.
     *
     * @return counter for successful BLS key registrations
     */
    public Counter getRegistrationSuccessesCounter() {
        return registrationSuccesses;
    }
}
