/*
 * Copyright 2016 Ben Manes. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hellblazer.delos.stereotomy.caching;

import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.github.benmanes.caffeine.cache.stats.StatsCounter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.checkerframework.checker.index.qual.NonNegative;

import java.util.EnumMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import static java.util.Objects.requireNonNull;

/**
 * A {@link StatsCounter} instrumented with Micrometer Metrics.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 * @author John Karp
 * @author hal.hildebrand (converted to Micrometer)
 */
public final class MetricsStatsCounter implements StatsCounter {
    private final Counter                                  hitCount;
    private final Counter                                  missCount;
    private final Timer                                    loadSuccess;
    private final Timer                                    loadFailure;
    private final DistributionSummary                      evictions;
    private final Counter                                  evictionWeight;
    private final EnumMap<RemovalCause, DistributionSummary> evictionsWithCause;

    // for implementing snapshot()
    private final LongAdder totalLoadTime = new LongAdder();

    /**
     * Constructs an instance for use by a single cache.
     *
     * @param registry      the registry of metric instances
     * @param metricsPrefix the prefix name for the metrics
     */
    public MetricsStatsCounter(MeterRegistry registry, String metricsPrefix) {
        requireNonNull(metricsPrefix);
        hitCount = Counter.builder(metricsPrefix + ".hits")
                          .description("Cache hits")
                          .register(registry);
        missCount = Counter.builder(metricsPrefix + ".misses")
                           .description("Cache misses")
                           .register(registry);
        loadSuccess = Timer.builder(metricsPrefix + ".loads.success")
                           .description("Successful cache loads")
                           .register(registry);
        loadFailure = Timer.builder(metricsPrefix + ".loads.failure")
                           .description("Failed cache loads")
                           .register(registry);
        evictions = DistributionSummary.builder(metricsPrefix + ".evictions")
                                       .description("Cache evictions")
                                       .register(registry);
        evictionWeight = Counter.builder(metricsPrefix + ".evictions.weight")
                                .description("Total weight of evicted entries")
                                .register(registry);

        evictionsWithCause = new EnumMap<>(RemovalCause.class);
        for (RemovalCause cause : RemovalCause.values()) {
            evictionsWithCause.put(cause,
                                   DistributionSummary.builder(metricsPrefix + ".evictions." + cause.name())
                                                      .description("Evictions due to " + cause.name())
                                                      .register(registry));
        }
    }

    @Override
    public void recordEviction(@NonNegative int weight, RemovalCause cause) {
        evictionsWithCause.get(cause).record(weight);
        evictionWeight.increment(weight);
        evictions.record(1);
    }

    @Override
    public void recordHits(int count) {
        hitCount.increment(count);
    }

    @Override
    public void recordLoadFailure(long loadTime) {
        loadFailure.record(loadTime, TimeUnit.NANOSECONDS);
        totalLoadTime.add(loadTime);
    }

    @Override
    public void recordLoadSuccess(long loadTime) {
        loadSuccess.record(loadTime, TimeUnit.NANOSECONDS);
        totalLoadTime.add(loadTime);
    }

    @Override
    public void recordMisses(int count) {
        missCount.increment(count);
    }

    @Override
    public CacheStats snapshot() {
        return CacheStats.of((long) hitCount.count(), (long) missCount.count(), loadSuccess.count(), loadFailure.count(),
                             totalLoadTime.sum(), (long) evictions.count(), (long) evictionWeight.count());
    }

    @Override
    public String toString() {
        return snapshot().toString();
    }
}
