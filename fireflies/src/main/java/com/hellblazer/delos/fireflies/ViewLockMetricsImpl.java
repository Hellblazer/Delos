/*
 * Copyright (c) 2024, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;

/**
 * Implementation of ViewLockMetrics for monitoring lock performance
 *
 * @author hal.hildebrand
 */
public class ViewLockMetricsImpl implements ViewLockMetrics {
    private final Timer readLockAcquireTimer;
    private final Histogram readLockHoldHistogram;
    private final Timer writeLockAcquireTimer;
    private final Histogram writeLockHoldHistogram;
    private final Histogram threadsBlockedHistogram;

    public ViewLockMetricsImpl(MetricRegistry registry) {
        readLockAcquireTimer = registry.timer(MetricRegistry.name("view", "lock", "read", "acquire"));
        readLockHoldHistogram = registry.histogram(MetricRegistry.name("view", "lock", "read", "hold"));
        writeLockAcquireTimer = registry.timer(MetricRegistry.name("view", "lock", "write", "acquire"));
        writeLockHoldHistogram = registry.histogram(MetricRegistry.name("view", "lock", "write", "hold"));
        threadsBlockedHistogram = registry.histogram(MetricRegistry.name("view", "lock", "threads", "blocked"));
    }

    @Override
    public Timer readLockAcquireTime() {
        return readLockAcquireTimer;
    }

    @Override
    public Histogram readLockHoldTime() {
        return readLockHoldHistogram;
    }

    @Override
    public Timer writeLockAcquireTime() {
        return writeLockAcquireTimer;
    }

    @Override
    public Histogram writeLockHoldTime() {
        return writeLockHoldHistogram;
    }

    @Override
    public Histogram threadsBlockedOnLock() {
        return threadsBlockedHistogram;
    }
}
