/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.archipelago;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.hellblazer.delos.archipelago.ServerConnectionCache.ServerConnectionCacheMetrics;

/**
 * @author hal.hildebrand
 *
 */
public class ServerConnectionCacheMetricsImpl implements ServerConnectionCacheMetrics {
    private final Meter   borrowRate;
    private final Timer   channelOpenDuration;
    private final Meter   closeConnectionRate;
    private final Counter createConnection;
    private final Meter   failedConnectionRate;
    private final Counter failedOpenConnection;
    private final Counter openConnections;
    private final Meter   releaseRate;

    public ServerConnectionCacheMetricsImpl(MetricRegistry registry) {
        failedOpenConnection = registry.counter("client.connection.open.fail");
        createConnection = registry.counter("client.connection.created");
        openConnections = registry.counter("client.connection.open");
        closeConnectionRate = registry.meter("client.connection.close");
        failedConnectionRate = registry.meter("client.connection.fail");
        borrowRate = registry.meter("client.connection.borrow");
        releaseRate = registry.meter("client.connection.release");
        channelOpenDuration = registry.timer("client.connection.open.duration");
    }

    @Override
    public void recordBorrow() {
        borrowRate.mark();
    }

    @Override
    public void recordChannelOpenDuration(long nanos) {
        channelOpenDuration.update(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordCloseConnection() {
        closeConnectionRate.mark();
    }

    @Override
    public void incrementCreateConnection() {
        createConnection.inc();
    }

    @Override
    public void recordFailedConnection() {
        failedConnectionRate.mark();
    }

    @Override
    public void incrementFailedOpenConnection() {
        failedOpenConnection.inc();
    }

    @Override
    public void incrementOpenConnections() {
        openConnections.inc();
    }

    @Override
    public void decrementOpenConnections() {
        openConnections.dec();
    }

    @Override
    public void recordRelease() {
        releaseRate.mark();
    }
}
