/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.model;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Metrics snapshot for a subdomain.
 * <p>
 * Tracks operational statistics including spawn time, request counts, error rates, and latency.
 * Immutable snapshot of metrics at a point in time.
 *
 * @author hal.hildebrand
 */
public class SubDomainMetrics {
    private final Instant  spawnTime;
    private final long     requestCount;
    private final long     errorCount;
    private final Duration averageLatency;
    private final Duration p95Latency;
    private final Duration p99Latency;

    public SubDomainMetrics(Instant spawnTime, long requestCount, long errorCount, Duration averageLatency,
                            Duration p95Latency, Duration p99Latency) {
        this.spawnTime = spawnTime;
        this.requestCount = requestCount;
        this.errorCount = errorCount;
        this.averageLatency = averageLatency;
        this.p95Latency = p95Latency;
        this.p99Latency = p99Latency;
    }

    /**
     * When this subdomain was spawned.
     *
     * @return spawn timestamp
     */
    public Instant getSpawnTime() {
        return spawnTime;
    }

    /**
     * Uptime since spawn.
     *
     * @return duration since spawn time
     */
    public Duration getUptime() {
        return Duration.between(spawnTime, Instant.now());
    }

    /**
     * Total number of requests sent to this subdomain.
     *
     * @return request count
     */
    public long getRequestCount() {
        return requestCount;
    }

    /**
     * Number of requests that resulted in errors.
     *
     * @return error count
     */
    public long getErrorCount() {
        return errorCount;
    }

    /**
     * Error rate as a fraction.
     *
     * @return errors / requests (0.0 to 1.0)
     */
    public double getErrorRate() {
        return requestCount == 0 ? 0.0 : (double) errorCount / requestCount;
    }

    /**
     * Average request latency.
     *
     * @return mean latency across all requests
     */
    public Duration getAverageLatency() {
        return averageLatency;
    }

    /**
     * 95th percentile latency.
     *
     * @return latency at p95
     */
    public Duration getP95Latency() {
        return p95Latency;
    }

    /**
     * 99th percentile latency.
     *
     * @return latency at p99
     */
    public Duration getP99Latency() {
        return p99Latency;
    }

    @Override
    public String toString() {
        return String.format(
        "SubDomainMetrics{uptime=%s, requests=%d, errors=%d, errorRate=%.2f%%, avgLatency=%dms, p95=%dms, p99=%dms}",
        getUptime(), requestCount, errorCount, getErrorRate() * 100, averageLatency.toMillis(),
        p95Latency.toMillis(), p99Latency.toMillis());
    }
}
