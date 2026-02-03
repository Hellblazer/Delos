/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.ethereal.memberships.comm.EtherealMetrics;
import com.hellblazer.delos.ethereal.memberships.comm.MicrometerEtherealMetrics;
import com.hellblazer.delos.membership.messaging.beg.MicrometerBegMetrics;
import com.hellblazer.delos.membership.messaging.beg.BegMetrics;
import com.hellblazer.delos.protocols.MicrometerEndpointMetrics;
import com.hellblazer.delos.protocols.MicrometerLimitsRegistry;
import com.netflix.concurrency.limits.MetricRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Micrometer implementation of ChoamMetrics.
 *
 * @author hal.hildebrand
 */
public class MicrometerChoamMetrics extends MicrometerEndpointMetrics implements ChoamMetrics {

    private final BegMetrics                combineMetrics;
    private final Counter                   cancelledTransactions;
    private final Counter                   completedTransactions;
    private final Counter                   droppedReassemblies;
    private final Counter                   droppedTransactions;
    private final Counter                   droppedValidations;
    private final Counter                   failedTransactions;
    private final EtherealMetrics           genesisMetrics;
    private final EtherealMetrics           producerMetrics;
    private final DistributionSummary       publishedBytes;
    private final Counter                   publishedJoins;
    private final Counter                   publishedTransactions;
    private final Counter                   publishedValidations;
    private final MeterRegistry             registry;
    private final Timer                     transactionLatency;
    private final Counter                   transactionSubmitFailed;
    private final Counter                   transactionSubmitRetry;
    private final Counter                   transactionSubmitSuccess;
    private final Counter                   transactionSubmittedBufferFull;
    private final Counter                   transactionSubmittedInvalidCommittee;
    private final Counter                   transactionTimeout;
    private final Counter                   transactionSubmittedUnavailable;
    private final Counter                   transactionSubmissionError;
    private final Counter                   transactionSubmittedInvalidResult;
    private final Counter                   transactionSubmitRetriesExhausted;
    private final Counter                   transactionSubmitRateLimited;
    private final Counter                   transactionCancelled;
    private final MetricRegistry            limits;

    public MicrometerChoamMetrics(Digest context, MeterRegistry registry) {
        super(registry, "choam");
        this.registry = registry;

        var contextTag = context.shortString();

        // Create nested metrics
        combineMetrics = new MicrometerBegMetrics(registry);
        producerMetrics = new MicrometerEtherealMetrics(context, "producer", registry);
        genesisMetrics = new MicrometerEtherealMetrics(context, "genesis", registry);

        // Dropped counters
        droppedTransactions = Counter.builder("choam.transactions.dropped")
                                     .description("Number of dropped transactions")
                                     .tags("context", contextTag)
                                     .register(registry);

        droppedReassemblies = Counter.builder("choam.reassemblies.dropped")
                                     .description("Number of dropped reassemblies")
                                     .tags("context", contextTag)
                                     .register(registry);

        droppedValidations = Counter.builder("choam.validations.dropped")
                                    .description("Number of dropped validations")
                                    .tags("context", contextTag)
                                    .register(registry);

        // Transaction lifecycle counters
        cancelledTransactions = Counter.builder("choam.transactions.cancelled")
                                       .description("Number of cancelled transactions")
                                       .tags("context", contextTag)
                                       .register(registry);

        completedTransactions = Counter.builder("choam.transactions.completed")
                                       .description("Number of completed transactions")
                                       .tags("context", contextTag)
                                       .register(registry);

        failedTransactions = Counter.builder("choam.transactions.failed")
                                    .description("Number of failed transactions")
                                    .tags("context", contextTag)
                                    .register(registry);

        transactionTimeout = Counter.builder("choam.transaction.timeout")
                                    .description("Number of transaction timeouts")
                                    .tags("context", contextTag)
                                    .register(registry);

        transactionCancelled = Counter.builder("choam.transaction.submit.cancelled")
                                      .description("Number of cancelled transaction submissions")
                                      .tags("context", contextTag)
                                      .register(registry);

        // Published metrics
        publishedTransactions = Counter.builder("choam.transactions.published")
                                       .description("Number of published transactions")
                                       .tags("context", contextTag)
                                       .register(registry);

        publishedBytes = DistributionSummary.builder("choam.unit.bytes")
                                           .description("Distribution of published batch sizes")
                                           .baseUnit("bytes")
                                           .tags("context", contextTag)
                                           .register(registry);

        publishedJoins = Counter.builder("choam.joins.published")
                                .description("Number of published joins")
                                .tags("context", contextTag)
                                .register(registry);

        publishedValidations = Counter.builder("choam.validations.published")
                                      .description("Number of published validations")
                                      .tags("context", contextTag)
                                      .register(registry);

        // Transaction latency timer
        transactionLatency = Timer.builder("choam.transaction.latency")
                                  .description("Transaction processing latency")
                                  .tags("context", contextTag)
                                  .register(registry);

        // Transaction submit counters
        transactionSubmitRetry = Counter.builder("choam.transaction.submit.retry")
                                        .description("Number of transaction submission retries")
                                        .tags("context", contextTag)
                                        .register(registry);

        transactionSubmitFailed = Counter.builder("choam.transaction.submit.failed")
                                         .description("Number of failed transaction submissions")
                                         .tags("context", contextTag)
                                         .register(registry);

        transactionSubmitSuccess = Counter.builder("choam.transaction.submit.success")
                                          .description("Number of successful transaction submissions")
                                          .tags("context", contextTag)
                                          .register(registry);

        transactionSubmittedBufferFull = Counter.builder("choam.transaction.submit.buffer.full")
                                                .description("Number of submissions rejected due to full buffer")
                                                .tags("context", contextTag)
                                                .register(registry);

        transactionSubmittedInvalidCommittee = Counter.builder("choam.transaction.submit.invalid.committee")
                                                      .description("Number of submissions with invalid committee")
                                                      .tags("context", contextTag)
                                                      .register(registry);

        transactionSubmittedUnavailable = Counter.builder("choam.transaction.submit.unavailable")
                                                 .description("Number of submissions when service unavailable")
                                                 .tags("context", contextTag)
                                                 .register(registry);

        transactionSubmissionError = Counter.builder("choam.transaction.submit.error")
                                            .description("Number of transaction submission errors")
                                            .tags("context", contextTag)
                                            .register(registry);

        transactionSubmittedInvalidResult = Counter.builder("choam.transaction.submit.invalid.result")
                                                   .description("Number of submissions with invalid result")
                                                   .tags("context", contextTag)
                                                   .register(registry);

        transactionSubmitRetriesExhausted = Counter.builder("choam.transaction.submit.retries.exhausted")
                                                   .description("Number of submissions with retries exhausted")
                                                   .tags("context", contextTag)
                                                   .register(registry);

        transactionSubmitRateLimited = Counter.builder("choam.transaction.submit.rate.limited")
                                              .description("Number of rate-limited transaction submissions")
                                              .tags("context", contextTag)
                                              .register(registry);

        limits = new MicrometerLimitsRegistry("choam", registry);
    }

    @Override
    public void dropped(int transactions, int validations, int reassemblies) {
        droppedTransactions.increment(transactions);
        droppedValidations.increment(validations);
        droppedReassemblies.increment(reassemblies);
    }

    @Override
    public BegMetrics getCombineMetrics() {
        return combineMetrics;
    }

    @Override
    public EtherealMetrics getGensisMetrics() {
        return genesisMetrics;
    }

    @Override
    public MetricRegistry getMetricRegistry(String prefix) {
        return new MicrometerLimitsRegistry(prefix, registry);
    }

    @Override
    public EtherealMetrics getProducerMetrics() {
        return producerMetrics;
    }

    @Override
    public void publishedBatch(int transactions, int byteSize, int validations, int joins) {
        publishedTransactions.increment(transactions);
        publishedBytes.record(byteSize);
        publishedValidations.increment(validations);
        publishedJoins.increment(joins);
    }

    @Override
    public void transactionCancelled() {
        transactionCancelled.increment();
    }

    @Override
    public void transactionComplete(Throwable t) {
        if (t != null) {
            if (t instanceof TimeoutException) {
                transactionTimeout.increment();
            } else if (t instanceof CancellationException) {
                cancelledTransactions.increment();
            } else {
                failedTransactions.increment();
            }
        } else {
            completedTransactions.increment();
        }
    }

    @Override
    public void recordTransactionLatencyDuration(long nanos) {
        transactionLatency.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void transactionSubmissionError() {
        transactionSubmissionError.increment();
    }

    @Override
    public void transactionSubmitRateLimited() {
        transactionSubmitRateLimited.increment();
    }

    @Override
    public void transactionSubmitRetriesExhausted() {
        transactionSubmitRetriesExhausted.increment();
    }

    @Override
    public void transactionSubmitRetry() {
        transactionSubmitRetry.increment();
    }

    @Override
    public void transactionSubmittedBufferFull() {
        transactionSubmittedBufferFull.increment();
    }

    @Override
    public void transactionSubmittedFail() {
        transactionSubmitFailed.increment();
    }

    @Override
    public void transactionSubmittedInvalidCommittee() {
        transactionSubmittedInvalidCommittee.increment();
    }

    @Override
    public void transactionSubmittedInvalidResult() {
        transactionSubmittedInvalidResult.increment();
    }

    @Override
    public void transactionSubmittedSuccess() {
        transactionSubmitSuccess.increment();
    }

    @Override
    public void transactionSubmittedUnavailable() {
        transactionSubmittedUnavailable.increment();
    }

    @Override
    public void transactionTimeout() {
        transactionTimeout.increment();
    }
}
