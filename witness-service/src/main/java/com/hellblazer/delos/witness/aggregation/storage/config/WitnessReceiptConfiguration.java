/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation.storage.config;

import com.hellblazer.delos.witness.aggregation.recursive.compression.CompressionConfig;

/**
 * Configuration for witness receipt storage and compression strategies.
 * <p>
 * Immutable record for thread-safe configuration sharing across witness service components.
 * Supports multiple storage backends (in-memory, JDBC) with optional compression and caching.
 * <p>
 * <b>Storage Types</b>:
 * <ul>
 *   <li><b>MEMORY</b>: Fast, ephemeral storage using ConcurrentHashMap (default)</li>
 *   <li><b>JDBC</b>: Persistent storage using H2 database with Liquibase migrations</li>
 * </ul>
 * <p>
 * <b>Compression Support</b>:
 * <ul>
 *   <li><b>NONE</b>: No compression (default for development)</li>
 *   <li><b>LZ4</b>: Fast compression, 10-15% size reduction</li>
 *   <li><b>ZSTD</b>: Better compression, 15-20% size reduction (production)</li>
 * </ul>
 * <p>
 * <b>Caching</b>: Caffeine LRU cache for JDBC backend to reduce database reads.
 * Cache enabled by default with 1000 entries and 60-minute TTL.
 * <p>
 * <b>Usage Example</b>:
 * <pre>{@code
 * // Development configuration: in-memory, no compression
 * var devConfig = WitnessReceiptConfiguration.DEFAULT;
 *
 * // Production configuration: JDBC with ZSTD compression
 * var prodConfig = WitnessReceiptConfiguration.PRODUCTION;
 *
 * // Custom configuration
 * var customConfig = new WitnessReceiptConfiguration(
 *     StoreType.JDBC,
 *     CompressionConfig.FAST,
 *     true,      // Enable caching
 *     5000,      // Cache 5000 receipts
 *     120        // 2-hour TTL
 * );
 * }</pre>
 *
 * @param storeType         Storage backend type (MEMORY or JDBC)
 * @param compressionConfig Compression strategy (NONE, LZ4, or ZSTD)
 * @param cacheEnabled      Enable Caffeine LRU caching for JDBC backend
 * @param maxCacheSize      Maximum number of cached receipts (>= 100)
 * @param cacheTTLMinutes   Cache time-to-live in minutes (>= 1)
 * @author hal.hildebrand
 * @see CompressionConfig
 * @see com.hellblazer.delos.witness.aggregation.storage.AggregateReceiptStore
 * @see com.hellblazer.delos.witness.aggregation.storage.RecursiveReceiptStore
 */
public record WitnessReceiptConfiguration(
    StoreType storeType,
    CompressionConfig compressionConfig,
    boolean cacheEnabled,
    int maxCacheSize,
    int cacheTTLMinutes
) {

    /**
     * Storage backend type for witness receipts.
     */
    public enum StoreType {
        /**
         * In-memory storage using ConcurrentHashMap.
         * Fast but ephemeral - data lost on JVM restart.
         * Suitable for development and testing.
         */
        MEMORY,

        /**
         * JDBC storage using H2 database.
         * Persistent storage with Liquibase schema management.
         * Suitable for production deployments.
         */
        JDBC
    }

    /**
     * Default configuration: In-memory with no compression.
     * <p>
     * Suitable for development and testing environments.
     * Provides fast access with minimal overhead.
     */
    public static final WitnessReceiptConfiguration DEFAULT = new WitnessReceiptConfiguration(
        StoreType.MEMORY,
        CompressionConfig.NONE,
        true,
        1000,
        60
    );

    /**
     * Production configuration: JDBC with ZSTD compression.
     * <p>
     * Optimized for production deployments with:
     * - Persistent storage surviving restarts
     * - ZSTD compression for 15-20% size reduction
     * - LRU caching for reduced database reads
     * - Balanced cache size and TTL
     */
    public static final WitnessReceiptConfiguration PRODUCTION = new WitnessReceiptConfiguration(
        StoreType.JDBC,
        CompressionConfig.BEST,
        true,
        1000,
        60
    );

    /**
     * Compact constructor with validation.
     *
     * @throws IllegalArgumentException if storeType is null
     * @throws IllegalArgumentException if compressionConfig is null
     * @throws IllegalArgumentException if maxCacheSize < 100
     * @throws IllegalArgumentException if cacheTTLMinutes < 1
     */
    public WitnessReceiptConfiguration {
        if (storeType == null) {
            throw new IllegalArgumentException("storeType cannot be null");
        }
        if (compressionConfig == null) {
            throw new IllegalArgumentException("compressionConfig cannot be null");
        }
        if (maxCacheSize < 100) {
            throw new IllegalArgumentException("maxCacheSize must be >= 100");
        }
        if (cacheTTLMinutes < 1) {
            throw new IllegalArgumentException("cacheTTLMinutes must be >= 1");
        }
    }
}
