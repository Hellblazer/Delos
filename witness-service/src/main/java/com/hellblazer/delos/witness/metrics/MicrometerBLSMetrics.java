/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.metrics;

import io.micrometer.core.instrument.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Micrometer implementation of BLSMetrics for tracking BLS signature operations.
 *
 * @author hal.hildebrand
 */
public class MicrometerBLSMetrics implements BLSMetrics {

    private final Timer                  receiptLatency;
    private final Counter                rejectedEpoch;
    private final Counter                rejectedViewRef;
    private final Counter                rejectedLate;
    private final Counter                rejectedDuplicate;
    private final Counter                rejectedInvalidSignature;
    private final Counter                rejectedNotInCommittee;
    private final Counter                rejectedByzantine;
    private final AtomicInteger          activeAccumulators;
    private final Counter                completedAccumulation;
    private final Counter                accumulatorCreated;
    private final Counter                accumulatorDiscarded;
    private final Timer                  timeToThreshold;
    private final DistributionSummary    thresholdPercentage;
    private final AtomicInteger          bufferedSignatures;
    private final Timer                  bufferDrainLatency;
    private final Counter                aggregationsPerformed;
    private final DistributionSummary    aggregationBatchSize;
    private final DistributionSummary    aggregateSize;
    private final DistributionSummary    compressionRatio;
    private final Counter                aggregationErrors;
    private final DistributionSummary    committeeParticipation;
    private final DistributionSummary    signerBitmapOverhead;
    private final Counter                emptyAccumulatorCleanup;

    /**
     * Create MicrometerBLSMetrics with the given registry.
     *
     * @param registry Micrometer MeterRegistry for metric registration
     */
    public MicrometerBLSMetrics(MeterRegistry registry) {
        this.receiptLatency = Timer.builder("bls.receipt.latency")
                                   .description("Signature receipt latency (microseconds)")
                                   .register(registry);

        this.rejectedEpoch = Counter.builder("bls.rejected.epoch")
                                    .description("Signatures rejected due to epoch mismatch")
                                    .register(registry);

        this.rejectedViewRef = Counter.builder("bls.rejected.viewref")
                                      .description("Signatures rejected due to viewRef mismatch")
                                      .register(registry);

        this.rejectedLate = Counter.builder("bls.rejected.late")
                                   .description("Late signatures (after threshold)")
                                   .register(registry);

        this.rejectedDuplicate = Counter.builder("bls.rejected.duplicate")
                                        .description("Duplicate signatures from same member")
                                        .register(registry);

        this.rejectedInvalidSignature = Counter.builder("bls.rejected.invalid_signature")
                                               .description("Invalid signature verification failures")
                                               .register(registry);

        this.rejectedNotInCommittee = Counter.builder("bls.rejected.not_in_committee")
                                             .description("Signatures from non-committee members")
                                             .register(registry);

        this.rejectedByzantine = Counter.builder("bls.rejected.byzantine")
                                        .description("Signatures from Byzantine members")
                                        .register(registry);

        this.activeAccumulators = new AtomicInteger(0);
        Gauge.builder("bls.accumulators.active", activeAccumulators, AtomicInteger::get)
             .description("Current number of active accumulators")
             .register(registry);

        this.completedAccumulation = Counter.builder("bls.accumulation.completed")
                                            .description("Completed accumulations (threshold met)")
                                            .register(registry);

        this.accumulatorCreated = Counter.builder("bls.accumulator.created")
                                         .description("Accumulators created")
                                         .register(registry);

        this.accumulatorDiscarded = Counter.builder("bls.accumulator.discarded")
                                           .description("Accumulators discarded before threshold")
                                           .register(registry);

        this.timeToThreshold = Timer.builder("bls.threshold.time")
                                    .description("Time to reach threshold (microseconds)")
                                    .register(registry);

        this.thresholdPercentage = DistributionSummary.builder("bls.threshold.percentage")
                                                      .description("Threshold achievement percentage (0-100)")
                                                      .register(registry);

        this.bufferedSignatures = new AtomicInteger(0);
        Gauge.builder("bls.buffer.signatures", bufferedSignatures, AtomicInteger::get)
             .description("Current number of buffered signatures")
             .register(registry);

        this.bufferDrainLatency = Timer.builder("bls.buffer.drain.latency")
                                       .description("Buffer drain operation latency (microseconds)")
                                       .register(registry);

        this.aggregationsPerformed = Counter.builder("bls.aggregation.performed")
                                            .description("Aggregations performed")
                                            .register(registry);

        this.aggregationBatchSize = DistributionSummary.builder("bls.aggregation.batch.size")
                                                       .description("Aggregation batch size (signatures)")
                                                       .register(registry);

        this.aggregateSize = DistributionSummary.builder("bls.aggregate.size")
                                                .description("Aggregate size (bytes)")
                                                .register(registry);

        this.compressionRatio = DistributionSummary.builder("bls.compression.ratio")
                                                   .description("Compression ratio")
                                                   .register(registry);

        this.aggregationErrors = Counter.builder("bls.aggregation.errors")
                                        .description("Aggregation errors")
                                        .register(registry);

        this.committeeParticipation = DistributionSummary.builder("bls.committee.participation")
                                                         .description("Committee participation (signer count)")
                                                         .register(registry);

        this.signerBitmapOverhead = DistributionSummary.builder("bls.signer.bitmap.overhead")
                                                       .description("Signer bitmap overhead (bytes)")
                                                       .register(registry);

        this.emptyAccumulatorCleanup = Counter.builder("bls.accumulator.cleanup.empty")
                                              .description("Empty accumulator cleanups")
                                              .register(registry);
    }

    @Override
    public void recordReceiptLatency(long latencyMicros) {
        receiptLatency.record(latencyMicros, TimeUnit.MICROSECONDS);
    }

    @Override
    public void incrementRejectedEpoch() {
        rejectedEpoch.increment();
    }

    @Override
    public void incrementRejectedViewRef() {
        rejectedViewRef.increment();
    }

    @Override
    public void incrementRejectedLate() {
        rejectedLate.increment();
    }

    @Override
    public void incrementRejectedDuplicate() {
        rejectedDuplicate.increment();
    }

    @Override
    public void incrementRejectedInvalidSignature() {
        rejectedInvalidSignature.increment();
    }

    @Override
    public void incrementRejectedNotInCommittee() {
        rejectedNotInCommittee.increment();
    }

    @Override
    public void incrementRejectedByzantine() {
        rejectedByzantine.increment();
    }

    @Override
    public void setActiveAccumulators(int count) {
        activeAccumulators.set(count);
    }

    @Override
    public void recordCompletedAccumulation() {
        completedAccumulation.increment();
    }

    @Override
    public void incrementAccumulatorCreated() {
        accumulatorCreated.increment();
    }

    @Override
    public void incrementAccumulatorDiscarded() {
        accumulatorDiscarded.increment();
    }

    @Override
    public void recordTimeToThreshold(long durationMicros) {
        timeToThreshold.record(durationMicros, TimeUnit.MICROSECONDS);
    }

    @Override
    public void recordThresholdPercentage(double percentage) {
        thresholdPercentage.record(percentage * 100.0); // Convert to percentage scale
    }

    @Override
    public void setBufferedSignatures(int count) {
        bufferedSignatures.set(count);
    }

    @Override
    public void recordBufferDrainLatency(long latencyMicros) {
        bufferDrainLatency.record(latencyMicros, TimeUnit.MICROSECONDS);
    }

    @Override
    public void incrementAggregationsPerformed() {
        aggregationsPerformed.increment();
    }

    @Override
    public void recordAggregationBatchSize(int batchSize) {
        aggregationBatchSize.record(batchSize);
    }

    @Override
    public void recordAggregateSize(int sizeBytes) {
        aggregateSize.record(sizeBytes);
    }

    @Override
    public void recordCompressionRatio(double ratio) {
        compressionRatio.record(ratio);
    }

    @Override
    public void incrementAggregationErrors() {
        aggregationErrors.increment();
    }

    @Override
    public void recordCommitteeParticipation(int signerCount) {
        committeeParticipation.record(signerCount);
    }

    @Override
    public void recordSignerBitmapOverhead(int bitmapBytes) {
        signerBitmapOverhead.record(bitmapBytes);
    }

    @Override
    public void recordEmptyAccumulatorCleanup() {
        emptyAccumulatorCleanup.increment();
    }
}
