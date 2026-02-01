/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.protocols;

import com.netflix.concurrency.limits.MetricRegistry;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

import java.util.function.Supplier;

/**
 * Micrometer implementation of Netflix concurrency limits MetricRegistry.
 *
 * @author hal.hildebrand
 */
public class MicrometerLimitsRegistry implements MetricRegistry {

    private final MeterRegistry registry;
    private final String        prefix;

    public MicrometerLimitsRegistry(String prefix, MeterRegistry registry) {
        this.prefix = prefix;
        this.registry = registry;
    }

    @Override
    public SampleListener distribution(String id, String... tagNameValuePairs) {
        var summary = DistributionSummary.builder(prefix + "." + id)
                                         .tags(Tags.of(tagNameValuePairs))
                                         .register(registry);
        return value -> summary.record(value.doubleValue());
    }

    @Override
    public void gauge(String id, Supplier<Number> supplier, String... tagNameValuePairs) {
        io.micrometer.core.instrument.Gauge.builder(prefix + "." + id, supplier)
                                           .tags(Tags.of(tagNameValuePairs))
                                           .register(registry);
    }

    @Override
    public Counter counter(String id, String... tagNameValuePairs) {
        var counter = io.micrometer.core.instrument.Counter.builder(prefix + "." + id)
                                                           .tags(Tags.of(tagNameValuePairs))
                                                           .register(registry);
        return counter::increment;
    }
}
