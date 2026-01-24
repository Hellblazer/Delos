/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.aggregation.storage.factory;

import com.hellblazer.delos.witness.aggregation.compression.AggregateWitnessReceiptCompressor;
import com.hellblazer.delos.witness.aggregation.recursive.compression.RecursiveAggregateReceiptCompressor;
import com.hellblazer.delos.witness.aggregation.storage.*;
import com.hellblazer.delos.witness.aggregation.storage.config.WitnessReceiptConfiguration;
import com.hellblazer.delos.witness.aggregation.storage.memory.InMemoryAggregateReceiptStore;
import com.hellblazer.delos.witness.aggregation.storage.memory.InMemoryRecursiveReceiptStore;
import com.hellblazer.delos.witness.aggregation.storage.jdbc.JdbcAggregateReceiptStore;
import com.hellblazer.delos.witness.aggregation.storage.jdbc.JdbcRecursiveReceiptStore;
import com.hellblazer.delos.witness.metrics.BLSMetrics;

import javax.sql.DataSource;

/**
 * Factory for creating configured receipt store instances.
 * <p>
 * Handles store type selection, compression wrapping, and Caffeine caching based on
 * {@link WitnessReceiptConfiguration}. Provides a single factory method for each
 * receipt type (aggregate and recursive).
 * <p>
 * <b>Store Selection Strategy</b>:
 * <ul>
 *   <li><b>MEMORY</b>: Creates in-memory ConcurrentHashMap-based stores (fast, ephemeral)</li>
 *   <li><b>JDBC</b>: Creates H2 database stores with Liquibase migrations (persistent)</li>
 * </ul>
 * <p>
 * <b>Optional Wrapping</b>:
 * <ul>
 *   <li><b>Compression</b>: Applied if {@code compressionConfig.codec() != NONE}</li>
 *   <li><b>Caching</b>: Applied if {@code cacheEnabled == true} (JDBC only)</li>
 * </ul>
 * <p>
 * <b>Usage Example</b>:
 * <pre>{@code
 * // Create stores from configuration
 * var config = WitnessReceiptConfiguration.PRODUCTION;
 * var dataSource = createDataSource();
 * var metrics = new BLSMetrics();
 *
 * var aggregateStore = ReceiptStoreFactory.createAggregateReceiptStore(
 *     config, dataSource, metrics);
 *
 * var recursiveStore = ReceiptStoreFactory.createRecursiveReceiptStore(
 *     config, dataSource, metrics);
 *
 * // Use stores with WitnessReceiptManager
 * var manager = new WitnessReceiptManager(
 *     parameters, buffer, isActive, degraded, metrics,
 *     aggregateStore, recursiveStore);
 * }</pre>
 * <p>
 * <b>Thread-Safety</b>: This factory is stateless and thread-safe. All created stores
 * inherit thread-safety guarantees from {@link ReceiptStore} interface.
 * <p>
 * <b>Error Handling</b>:
 * <ul>
 *   <li>Throws {@link IllegalArgumentException} if config is null</li>
 *   <li>Throws {@link IllegalArgumentException} if dataSource is null for JDBC stores</li>
 *   <li>Stores may throw {@link StorageException} during runtime operations</li>
 * </ul>
 *
 * @author hal.hildebrand
 * @see WitnessReceiptConfiguration
 * @see AggregateReceiptStore
 * @see RecursiveReceiptStore
 */
public class ReceiptStoreFactory {

    /**
     * Private constructor to prevent instantiation.
     * This is a utility class with only static methods.
     */
    private ReceiptStoreFactory() {
        throw new UnsupportedOperationException("Utility class - do not instantiate");
    }

    /**
     * Create AggregateReceiptStore with configured strategy.
     * <p>
     * Selects store implementation based on {@code config.storeType()}:
     * <ul>
     *   <li><b>MEMORY</b>: {@link InMemoryAggregateReceiptStore} (fast, ephemeral)</li>
     *   <li><b>JDBC</b>: {@link JdbcAggregateReceiptStore} (persistent, queryable)</li>
     * </ul>
     * <p>
     * Optionally wraps store with compression and caching layers if configured.
     *
     * @param config     Configuration for storage type, compression, and caching (non-null)
     * @param dataSource JDBC data source (required if config.storeType() == JDBC, nullable otherwise)
     * @param metrics    BLS metrics collector (nullable)
     * @return Configured AggregateReceiptStore instance (never null)
     * @throws IllegalArgumentException if config is null
     * @throws IllegalArgumentException if dataSource is null and storeType is JDBC
     */
    public static AggregateReceiptStore createAggregateReceiptStore(
        WitnessReceiptConfiguration config,
        DataSource dataSource,
        BLSMetrics metrics) {

        if (config == null) {
            throw new IllegalArgumentException("config cannot be null");
        }

        // Create base store based on type
        AggregateReceiptStore baseStore = switch (config.storeType()) {
            case MEMORY -> new InMemoryAggregateReceiptStore();
            case JDBC -> {
                if (dataSource == null) {
                    throw new IllegalArgumentException("dataSource required for JDBC storage");
                }
                // Create compressor for JDBC store
                var compressor = new AggregateWitnessReceiptCompressor();
                yield new JdbcAggregateReceiptStore(
                    dataSource,
                    compressor,
                    config.compressionConfig(),
                    config.maxCacheSize(),
                    config.cacheTTLMinutes()
                );
            }
        };

        // Note: Compression and caching are handled internally by JDBC stores
        // In-memory stores do not use compression or caching

        return baseStore;
    }

    /**
     * Create RecursiveReceiptStore with configured strategy.
     * <p>
     * Selects store implementation based on {@code config.storeType()}:
     * <ul>
     *   <li><b>MEMORY</b>: {@link InMemoryRecursiveReceiptStore} (fast, ephemeral)</li>
     *   <li><b>JDBC</b>: {@link JdbcRecursiveReceiptStore} (persistent, queryable)</li>
     * </ul>
     * <p>
     * Optionally wraps store with compression and caching layers if configured.
     *
     * @param config     Configuration for storage type, compression, and caching (non-null)
     * @param dataSource JDBC data source (required if config.storeType() == JDBC, nullable otherwise)
     * @param metrics    BLS metrics collector (nullable)
     * @return Configured RecursiveReceiptStore instance (never null)
     * @throws IllegalArgumentException if config is null
     * @throws IllegalArgumentException if dataSource is null and storeType is JDBC
     */
    public static RecursiveReceiptStore createRecursiveReceiptStore(
        WitnessReceiptConfiguration config,
        DataSource dataSource,
        BLSMetrics metrics) {

        if (config == null) {
            throw new IllegalArgumentException("config cannot be null");
        }

        // Create base store based on type
        RecursiveReceiptStore baseStore = switch (config.storeType()) {
            case MEMORY -> new InMemoryRecursiveReceiptStore();
            case JDBC -> {
                if (dataSource == null) {
                    throw new IllegalArgumentException("dataSource required for JDBC storage");
                }
                // Create compressor for JDBC store
                var compressor = new RecursiveAggregateReceiptCompressor();
                yield new JdbcRecursiveReceiptStore(
                    dataSource,
                    compressor,
                    config.compressionConfig(),
                    config.maxCacheSize(),
                    config.cacheTTLMinutes()
                );
            }
        };

        // Note: Compression and caching are handled internally by JDBC stores
        // In-memory stores do not use compression or caching

        return baseStore;
    }
}
