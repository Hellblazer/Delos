/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.thoth.metrics.KerlDhtMetrics;
import org.h2.jdbcx.JdbcConnectionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Collects and exposes DHT health metrics and connection pool monitoring.
 * <p>
 * Extracted from KerlDHT to separate health/monitoring concerns from core DHT operations.
 * Manages periodic connection pool sampling and provides health check APIs compatible
 * with the WitnessAdapter health pattern.
 * </p>
 *
 * @author hal.hildebrand
 */
class DhtMetricsCollector {

    private static final Logger log = LoggerFactory.getLogger(DhtMetricsCollector.class);

    private final KerlDhtMetrics            dhtMetrics;
    private final JdbcConnectionPool        connectionPool;
    private final ScheduledExecutorService  scheduler;
    private final Duration                  operationsFrequency;
    private final Digest                    memberId;
    private final Supplier<Boolean>         isStarted;
    private volatile ScheduledFuture<?>     poolMonitoringTask;

    DhtMetricsCollector(KerlDhtMetrics dhtMetrics, JdbcConnectionPool connectionPool,
                        ScheduledExecutorService scheduler, Duration operationsFrequency,
                        Digest memberId, Supplier<Boolean> isStarted) {
        this.dhtMetrics = dhtMetrics;
        this.connectionPool = connectionPool;
        this.scheduler = scheduler;
        this.operationsFrequency = operationsFrequency;
        this.memberId = memberId;
        this.isStarted = isStarted;
    }

    /**
     * Check if DHT is healthy based on current metrics.
     * <p>
     * Health criteria:
     * - Validation success rate >= 95%
     * - Connection pool utilization < 90%
     * - Circuit breaker closed
     * </p>
     *
     * @return true if DHT is operating within SLA thresholds
     */
    boolean isHealthy() {
        return dhtMetrics.getSnapshot().isHealthy();
    }

    /**
     * Get current health snapshot for monitoring dashboards.
     *
     * @return Current health snapshot
     */
    KerlDhtMetrics.Snapshot getHealthSnapshot() {
        return dhtMetrics.getSnapshot();
    }

    /**
     * Check if connection pool is exhausted (>=90% utilization).
     * Returns false if DHT is stopped or pool is disposed.
     *
     * @return true if pool utilization >= 90%
     */
    boolean isPoolExhausted() {
        if (!isStarted.get()) {
            return false;
        }
        try {
            var active = connectionPool.getActiveConnections();
            var max = connectionPool.getMaxConnections();
            return active >= (max * 0.9);
        } catch (Exception e) {
            log.trace("Unable to check pool exhaustion on: {}", memberId, e);
            return false;
        }
    }

    /**
     * Start periodic connection pool monitoring.
     * Scheduled task samples pool state and records metrics at operationsFrequency interval.
     */
    void startPoolMonitoring() {
        poolMonitoringTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                var active = connectionPool.getActiveConnections();
                var max = connectionPool.getMaxConnections();
                var idle = max - active;

                dhtMetrics.recordConnectionPoolActive(active);
                dhtMetrics.recordConnectionPoolIdle(idle);

                log.trace("Pool state on {}: active={}, idle={}, max={}", memberId, active, idle, max);
            } catch (Exception e) {
                log.trace("Error sampling connection pool on: {}", memberId, e);
            }
        }, 0, operationsFrequency.toMillis(), TimeUnit.MILLISECONDS);

        log.debug("Started connection pool monitoring on: {}", memberId);
    }

    /**
     * Stop periodic connection pool monitoring.
     * Cancels the scheduled monitoring task.
     */
    void stopPoolMonitoring() {
        if (poolMonitoringTask != null) {
            poolMonitoringTask.cancel(false);
            poolMonitoringTask = null;
            log.debug("Stopped connection pool monitoring on: {}", memberId);
        }
    }
}
