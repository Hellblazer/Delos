/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.model;

import java.time.Duration;

/**
 * Performance SLA (Service Level Agreement) constants for model module operations.
 * <p>
 * Defines target latencies, throughputs, and resource limits for multi-tenancy features.
 * Used by performance benchmarks and monitoring infrastructure.
 * <p>
 * See docs/PERFORMANCE_BASELINES.md for detailed rationale and test scenarios.
 *
 * @author hal.hildebrand
 */
public final class PerformanceSLA {

    // ===== Subdomain Spawn Performance =====

    /**
     * Target p50 latency for subdomain spawn (cold or warm).
     */
    public static final Duration SPAWN_P50_TARGET = Duration.ofMillis(100);

    /**
     * Target p95 latency for subdomain spawn (includes outliers).
     */
    public static final Duration SPAWN_P95_TARGET = Duration.ofMillis(500);

    /**
     * Target p99 latency for subdomain spawn (maximum acceptable delay).
     */
    public static final Duration SPAWN_P99_TARGET = Duration.ofMillis(1000);

    /**
     * Target concurrent spawn throughput (subdomains per second).
     */
    public static final int SPAWN_CONCURRENT_THROUGHPUT_TARGET = 10; // subdomains/sec

    /**
     * Target sequential spawn throughput (subdomains per second).
     */
    public static final int SPAWN_SEQUENTIAL_THROUGHPUT_TARGET = 5; // subdomains/sec

    // ===== Portal Routing Performance =====

    /**
     * Target p50 latency for Portal routing (send via Unix socket).
     */
    public static final Duration ROUTING_P50_TARGET = Duration.ofMillis(5);

    /**
     * Target p95 latency for Portal routing.
     */
    public static final Duration ROUTING_P95_TARGET = Duration.ofMillis(20);

    /**
     * Target p99 latency for Portal routing.
     */
    public static final Duration ROUTING_P99_TARGET = Duration.ofMillis(50);

    /**
     * Target routing throughput (requests per second per ProcessContainerDomain).
     */
    public static final int ROUTING_THROUGHPUT_TARGET = 1000; // req/sec

    /**
     * Target concurrent in-flight requests.
     */
    public static final int ROUTING_CONCURRENT_REQUESTS_TARGET = 100;

    // ===== Delegation Gossip Performance =====

    /**
     * Target convergence time for 2-replica cluster (90% nodes consistent).
     */
    public static final Duration GOSSIP_CONVERGENCE_2_REPLICAS = Duration.ofSeconds(2);

    /**
     * Target convergence time for 5-replica cluster (90% nodes consistent).
     */
    public static final Duration GOSSIP_CONVERGENCE_5_REPLICAS = Duration.ofSeconds(5);

    /**
     * Target convergence time for 10-replica cluster (90% nodes consistent).
     */
    public static final Duration GOSSIP_CONVERGENCE_10_REPLICAS = Duration.ofSeconds(10);

    /**
     * Target convergence time for 50-replica cluster (90% nodes consistent).
     */
    public static final Duration GOSSIP_CONVERGENCE_50_REPLICAS = Duration.ofSeconds(30);

    /**
     * Target per-node bandwidth consumption for gossip.
     */
    public static final long GOSSIP_BANDWIDTH_TARGET_BYTES_PER_SEC = 100 * 1024; // 100 KB/sec

    /**
     * Target Bloom filter size per gossip message.
     */
    public static final long GOSSIP_BLOOM_FILTER_SIZE_BYTES = 1024; // 1 KB

    // ===== Oracle Performance =====

    /**
     * Target p50 latency for Oracle.check() (read-only).
     */
    public static final Duration ORACLE_CHECK_P50_TARGET = Duration.ofMillis(5);

    /**
     * Target p95 latency for Oracle.check().
     */
    public static final Duration ORACLE_CHECK_P95_TARGET = Duration.ofMillis(20);

    /**
     * Target p50 latency for Oracle write operations (add/delete/map).
     */
    public static final Duration ORACLE_WRITE_P50_TARGET = Duration.ofMillis(10);

    /**
     * Target p95 latency for Oracle write operations.
     */
    public static final Duration ORACLE_WRITE_P95_TARGET = Duration.ofMillis(50);

    /**
     * Target latency for Oracle.getCurrentBlock() (should be cached).
     */
    public static final Duration ORACLE_GET_CURRENT_BLOCK_TARGET = Duration.ofMillis(1);

    /**
     * Target cache hit rate for getCurrentBlock().
     */
    public static final double ORACLE_GET_CURRENT_BLOCK_CACHE_HIT_RATE = 0.99; // 99%

    // ===== Scalability Limits =====

    /**
     * Target maximum concurrent subdomains per ProcessContainerDomain.
     */
    public static final int MAX_CONCURRENT_SUBDOMAINS_PER_CONTAINER = 100;

    /**
     * Target maximum concurrent subdomains per host (OS limits).
     */
    public static final int MAX_CONCURRENT_SUBDOMAINS_PER_HOST = 500;

    /**
     * Target heap memory per subdomain (matches ResourceLimits default).
     */
    public static final long MEMORY_PER_SUBDOMAIN_HEAP_BYTES = 50 * 1024 * 1024; // 50 MB

    /**
     * Target native memory per subdomain (matches ResourceLimits default).
     */
    public static final long MEMORY_PER_SUBDOMAIN_NATIVE_BYTES = 100 * 1024 * 1024; // 100 MB

    /**
     * File descriptors per subdomain (portal + context sockets).
     */
    public static final int FILE_DESCRIPTORS_PER_SUBDOMAIN = 2;

    // ===== Performance Degradation Thresholds =====

    /**
     * Expected spawn p95 latency with 10 subdomains.
     */
    public static final Duration SPAWN_P95_10_SUBDOMAINS = Duration.ofMillis(500);

    /**
     * Expected spawn p95 latency with 50 subdomains.
     */
    public static final Duration SPAWN_P95_50_SUBDOMAINS = Duration.ofMillis(750);

    /**
     * Expected spawn p95 latency with 100 subdomains.
     */
    public static final Duration SPAWN_P95_100_SUBDOMAINS = Duration.ofMillis(1000);

    /**
     * Expected routing p95 latency with 10 subdomains.
     */
    public static final Duration ROUTING_P95_10_SUBDOMAINS = Duration.ofMillis(20);

    /**
     * Expected routing p95 latency with 50 subdomains.
     */
    public static final Duration ROUTING_P95_50_SUBDOMAINS = Duration.ofMillis(30);

    /**
     * Expected routing p95 latency with 100 subdomains.
     */
    public static final Duration ROUTING_P95_100_SUBDOMAINS = Duration.ofMillis(50);

    // ===== Monitoring and Alerting Thresholds =====

    /**
     * Warning threshold for spawn p95 latency (monitoring alert).
     */
    public static final Duration SPAWN_P95_WARNING_THRESHOLD = Duration.ofMillis(1000);

    /**
     * Warning threshold for routing p95 latency (monitoring alert).
     */
    public static final Duration ROUTING_P95_WARNING_THRESHOLD = Duration.ofMillis(50);

    /**
     * Critical threshold for active subdomain count (% of limit).
     */
    public static final double SUBDOMAIN_COUNT_CRITICAL_THRESHOLD = 0.80; // 80%

    /**
     * Critical threshold for file descriptor usage (% of limit).
     */
    public static final double FILE_DESCRIPTOR_CRITICAL_THRESHOLD = 0.90; // 90%

    private PerformanceSLA() {
        // Utility class - no instantiation
    }
}
