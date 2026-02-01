/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.archipelago;

import com.hellblazer.delos.archipelago.ServerConnectionCache.ServerConnectionCacheMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Micrometer implementation of ServerConnectionCacheMetrics.
 *
 * @author hal.hildebrand
 */
public class MicrometerServerConnectionCacheMetrics implements ServerConnectionCacheMetrics {

    private final Counter borrowRate;
    private final Timer channelOpenDuration;
    private final Counter closeConnectionRate;
    private final Counter createConnection;
    private final Counter failedConnectionRate;
    private final Counter failedOpenConnection;
    private final AtomicInteger openConnectionsGauge;
    private final Counter releaseRate;

    public MicrometerServerConnectionCacheMetrics(MeterRegistry registry) {
        // Counters
        failedOpenConnection = Counter.builder("client.connection.open.fail")
                                      .description("Failed connection open attempts")
                                      .register(registry);
        createConnection = Counter.builder("client.connection.created")
                                  .description("Connections created")
                                  .register(registry);
        closeConnectionRate = Counter.builder("client.connection.close")
                                     .description("Connections closed")
                                     .register(registry);
        failedConnectionRate = Counter.builder("client.connection.fail")
                                      .description("Failed connection attempts")
                                      .register(registry);
        borrowRate = Counter.builder("client.connection.borrow")
                            .description("Connection borrow operations")
                            .register(registry);
        releaseRate = Counter.builder("client.connection.release")
                             .description("Connection release operations")
                             .register(registry);

        // Timer
        channelOpenDuration = Timer.builder("client.connection.open.duration")
                                   .description("Duration connections remain open")
                                   .register(registry);

        // Gauge (using AtomicInteger)
        openConnectionsGauge = new AtomicInteger(0);
        registry.gauge("client.connection.open", openConnectionsGauge);
    }

    @Override
    public void recordBorrow() {
        borrowRate.increment();
    }

    @Override
    public void recordChannelOpenDuration(long nanos) {
        channelOpenDuration.record(nanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordCloseConnection() {
        closeConnectionRate.increment();
    }

    @Override
    public void incrementCreateConnection() {
        createConnection.increment();
    }

    @Override
    public void recordFailedConnection() {
        failedConnectionRate.increment();
    }

    @Override
    public void incrementFailedOpenConnection() {
        failedOpenConnection.increment();
    }

    @Override
    public void incrementOpenConnections() {
        openConnectionsGauge.incrementAndGet();
    }

    @Override
    public void decrementOpenConnections() {
        openConnectionsGauge.decrementAndGet();
    }

    @Override
    public void recordRelease() {
        releaseRate.increment();
    }
}
