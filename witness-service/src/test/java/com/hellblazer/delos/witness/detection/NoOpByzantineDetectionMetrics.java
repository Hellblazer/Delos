/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import com.codahale.metrics.*;

import java.util.concurrent.TimeUnit;

/**
 * No-op implementation of ByzantineDetectionMetrics for testing.
 *
 * @author hal.hildebrand
 */
public class NoOpByzantineDetectionMetrics implements ByzantineDetectionMetrics {

    @Override
    public void recordAnomalyDetection(DetectorType detectorType, double score) {
        // No-op
    }

    @Override
    public void recordDetectionLatency(DetectorType detectorType, long latencyMicros) {
        // No-op
    }

    @Override
    public void incrementFalsePositive(DetectorType detectorType) {
        // No-op
    }

    @Override
    public Meter anomalyDetectionMeter(DetectorType detectorType) {
        return new Meter();
    }

    @Override
    public Histogram anomalyScoreHistogram(DetectorType detectorType) {
        return new Histogram(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS));
    }

    @Override
    public Timer detectionLatencyTimer(DetectorType detectorType) {
        return new Timer();
    }

    @Override
    public Counter falsePositiveCounter(DetectorType detectorType) {
        return new Counter();
    }

    @Override
    public void recordEnsembleVote(int voteCount) {
        // No-op
    }

    @Override
    public void incrementQuorumReached() {
        // No-op
    }

    @Override
    public void recordQuarantineEvent() {
        // No-op
    }

    @Override
    public void recordQuarantineDuration(long durationMs) {
        // No-op
    }

    @Override
    public void setActiveQuarantines(int count) {
        // No-op
    }

    @Override
    public void recordQuarantineRecovery() {
        // No-op
    }

    @Override
    public void recordEscalationAction(ResponseAction action, long latencyMicros) {
        // No-op
    }

    @Override
    public Histogram ensembleVoteHistogram() {
        return new Histogram(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS));
    }

    @Override
    public Counter quorumReachedCounter() {
        return new Counter();
    }

    @Override
    public Counter quarantineEventsCounter() {
        return new Counter();
    }

    @Override
    public Histogram quarantineDurationHistogram() {
        return new Histogram(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS));
    }

    @Override
    public Gauge<Integer> activeQuarantinesGauge() {
        return () -> 0;
    }

    @Override
    public Counter quarantineRecoveryCounter() {
        return new Counter();
    }

    @Override
    public Counter escalationActionCounter(ResponseAction action) {
        return new Counter();
    }

    @Override
    public Timer escalationLatencyTimer() {
        return new Timer();
    }

    @Override
    public void setMembersExcluded(int count) {
        // No-op
    }

    @Override
    public void setConsensusImpact(double score) {
        // No-op
    }

    @Override
    public void recordFalseAlarmDuration(long durationMs) {
        // No-op
    }

    @Override
    public void recordTimeToClearAnomalies(long durationMs) {
        // No-op
    }

    @Override
    public Gauge<Integer> membersExcludedGauge() {
        return () -> 0;
    }

    @Override
    public Gauge<Double> consensusImpactGauge() {
        return () -> 0.0;
    }

    @Override
    public Histogram falseAlarmDurationHistogram() {
        return new Histogram(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS));
    }

    @Override
    public Histogram timeToClearAnomaliesHistogram() {
        return new Histogram(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS));
    }

    @Override
    public void recordThresholdBreach(DetectorType detectorType) {
        // No-op
    }

    @Override
    public Counter thresholdBreachCounter(DetectorType detectorType) {
        return new Counter();
    }

    @Override
    public void register(MetricRegistry registry) {
        // No-op
    }

    @Override
    public void reset() {
        // No-op
    }
}
