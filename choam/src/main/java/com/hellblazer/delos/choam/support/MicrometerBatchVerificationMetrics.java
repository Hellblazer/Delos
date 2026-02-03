/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

import io.micrometer.core.instrument.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Micrometer implementation of BatchVerificationMetrics.
 * <p>
 * Provides detailed metrics for BLS batch signature verification in CHOAM.
 *
 * @author hal.hildebrand
 */
public class MicrometerBatchVerificationMetrics implements BatchVerificationMetrics {

    // Health thresholds
    private static final double MAX_FAILURE_RATE = 0.01;  // 1%
    private static final double MAX_LATENCY_MS = 10.0;     // 10ms

    private final Counter batchVerificationsTotal;
    private final Counter batchVerificationsSuccess;
    private final Counter batchVerificationsFailed;
    private final Counter individualVerificationsTotal;
    private final Counter individualVerificationsSuccess;
    private final Counter individualVerificationsFailed;
    private final Counter batchFallbacksTotal;
    private final DistributionSummary batchSize;
    private final Timer batchLatency;
    private final Timer individualLatency;

    // Tracking for health calculations
    private final LongAdder totalOperations = new LongAdder();
    private final LongAdder totalFailures = new LongAdder();
    private final AtomicLong latencySum = new AtomicLong();
    private final LongAdder latencyCount = new LongAdder();

    public MicrometerBatchVerificationMetrics(MeterRegistry registry, String contextTag) {
        // Batch verification counters
        batchVerificationsTotal = Counter.builder("choam.batch.verification.total")
                                         .description("Total batch verifications attempted")
                                         .tags("context", contextTag)
                                         .register(registry);

        batchVerificationsSuccess = Counter.builder("choam.batch.verification.success")
                                           .description("Successful batch verifications")
                                           .tags("context", contextTag)
                                           .register(registry);

        batchVerificationsFailed = Counter.builder("choam.batch.verification.failures")
                                          .description("Failed batch verifications")
                                          .tags("context", contextTag)
                                          .register(registry);

        // Individual verification counters
        individualVerificationsTotal = Counter.builder("choam.individual.verification.total")
                                              .description("Total individual verifications")
                                              .tags("context", contextTag)
                                              .register(registry);

        individualVerificationsSuccess = Counter.builder("choam.individual.verification.success")
                                                .description("Successful individual verifications")
                                                .tags("context", contextTag)
                                                .register(registry);

        individualVerificationsFailed = Counter.builder("choam.individual.verification.failures")
                                               .description("Failed individual verifications")
                                               .tags("context", contextTag)
                                               .register(registry);

        // Fallback counter
        batchFallbacksTotal = Counter.builder("choam.batch.verification.fallback")
                                     .description("Number of times batch verification fell back to individual")
                                     .tags("context", contextTag)
                                     .register(registry);

        // Batch size distribution
        batchSize = DistributionSummary.builder("choam.batch.verification.size")
                                       .description("Distribution of batch sizes")
                                       .tags("context", contextTag)
                                       .register(registry);

        // Latency timers
        batchLatency = Timer.builder("choam.batch.verification.latency")
                            .description("Batch verification latency")
                            .tags("context", contextTag)
                            .register(registry);

        individualLatency = Timer.builder("choam.individual.verification.latency")
                                 .description("Individual verification latency")
                                 .tags("context", contextTag)
                                 .register(registry);

        // Register health gauge
        Gauge.builder("choam.batch.verification.health", this, m -> m.isHealthy() ? 1.0 : 0.0)
             .description("Batch verification health (1=healthy, 0=unhealthy)")
             .tags("context", contextTag)
             .register(registry);

        // Register failure rate gauge
        Gauge.builder("choam.batch.verification.failure.rate", this, BatchVerificationMetrics::getFailureRate)
             .description("Batch verification failure rate")
             .tags("context", contextTag)
             .register(registry);
    }

    @Override
    public void recordBatchVerification(int size, long latencyNanos, boolean success) {
        batchVerificationsTotal.increment();
        batchSize.record(size);
        batchLatency.record(latencyNanos, TimeUnit.NANOSECONDS);

        totalOperations.increment();
        latencySum.addAndGet(latencyNanos);
        latencyCount.increment();

        if (success) {
            batchVerificationsSuccess.increment();
        } else {
            batchVerificationsFailed.increment();
            totalFailures.increment();
        }
    }

    @Override
    public void recordIndividualVerification(long latencyNanos, boolean success) {
        individualVerificationsTotal.increment();
        individualLatency.record(latencyNanos, TimeUnit.NANOSECONDS);

        totalOperations.increment();
        latencySum.addAndGet(latencyNanos);
        latencyCount.increment();

        if (success) {
            individualVerificationsSuccess.increment();
        } else {
            individualVerificationsFailed.increment();
            totalFailures.increment();
        }
    }

    @Override
    public void recordBatchFallback(int size, String reason) {
        batchFallbacksTotal.increment();
    }

    @Override
    public long getBatchVerifications() {
        return (long) batchVerificationsTotal.count();
    }

    @Override
    public long getIndividualVerifications() {
        return (long) individualVerificationsTotal.count();
    }

    @Override
    public long getBatchFailures() {
        return (long) batchVerificationsFailed.count();
    }

    @Override
    public double getAverageLatencyMs() {
        long count = latencyCount.sum();
        if (count == 0) {
            return 0.0;
        }
        return (latencySum.get() / (double) count) / 1_000_000.0;  // nanos to ms
    }

    @Override
    public double getFailureRate() {
        long total = totalOperations.sum();
        if (total == 0) {
            return 0.0;
        }
        return totalFailures.sum() / (double) total;
    }

    @Override
    public boolean isHealthy() {
        return getFailureRate() < MAX_FAILURE_RATE && getAverageLatencyMs() < MAX_LATENCY_MS;
    }
}
