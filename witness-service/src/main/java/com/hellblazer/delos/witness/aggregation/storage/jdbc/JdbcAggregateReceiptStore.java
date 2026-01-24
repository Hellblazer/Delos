/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.witness.aggregation.storage.jdbc;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hellblazer.delos.witness.aggregation.compression.AggregateWitnessReceiptCompressor;
import com.hellblazer.delos.witness.aggregation.recursive.compression.CompressionConfig;
import com.hellblazer.delos.witness.aggregation.storage.AggregateReceiptStore;
import com.hellblazer.delos.witness.aggregation.storage.StorageException;
import com.hellblazer.delos.witness.receipt.AggregateWitnessReceipt;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JDBC-backed storage for AggregateWitnessReceipt with LRU caching and compression.
 * <p>
 * Thread-safe: All database operations use connection pooling.
 * Virtual thread compatible: Non-blocking I/O with proper resource management.
 * <p>
 * Features:
 * - Persistent storage in H2 database
 * - Transparent compression/decompression
 * - LRU cache for hot receipts (Caffeine)
 * - Idempotent store operations (INSERT IGNORE)
 * - Atomic CRUD operations
 * - Epoch-based queries with indexing
 * <p>
 * Performance characteristics:
 * - Store: <10ms (incl. compression)
 * - Retrieve (cache hit): <1ms
 * - Retrieve (cache miss): <50ms (decompression + DB query)
 * - Cache: 1000 entries, 1-hour TTL by default
 * <p>
 * Database schema managed by Liquibase:
 * - Table: aggregate_receipts
 * - Primary key: receipt_key (VARCHAR)
 * - Indexed: epoch (INT)
 *
 * @author hal.hildebrand
 * @since Phase 3.4.4
 */
public class JdbcAggregateReceiptStore implements AggregateReceiptStore {

    private final DataSource dataSource;
    private final AggregateWitnessReceiptCompressor compressor;
    private final CompressionConfig compressionConfig;
    private final Cache<String, AggregateWitnessReceipt> cache;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    /**
     * Create JDBC store with specified configuration.
     *
     * @param dataSource        Database connection pool
     * @param compressor        Receipt compressor
     * @param compressionConfig Compression configuration
     * @param cacheSize         Maximum cache entries
     */
    public JdbcAggregateReceiptStore(DataSource dataSource, AggregateWitnessReceiptCompressor compressor,
                                     CompressionConfig compressionConfig, int cacheSize) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource cannot be null");
        this.compressor = Objects.requireNonNull(compressor, "compressor cannot be null");
        this.compressionConfig = Objects.requireNonNull(compressionConfig, "compressionConfig cannot be null");

        // LRU cache with 1-hour TTL
        this.cache = Caffeine.newBuilder()
                             .maximumSize(cacheSize)
                             .expireAfterWrite(1, TimeUnit.HOURS)
                             .recordStats()
                             .build();
    }

    @Override
    public void store(String key, AggregateWitnessReceipt receipt) {
        Objects.requireNonNull(key, "key cannot be null");
        Objects.requireNonNull(receipt, "receipt cannot be null");
        checkNotShutdown();

        try {
            // Compress receipt
            var compressed = compressor.toCompressedBytes(receipt, compressionConfig);

            // Store in database (idempotent INSERT IGNORE)
            try (var conn = dataSource.getConnection();
                 var stmt = conn.prepareStatement(
                     "INSERT IGNORE INTO aggregate_receipts (receipt_key, receipt_data, epoch, compressed, compression_codec) " +
                     "VALUES (?, ?, ?, ?, ?)")) {

                stmt.setString(1, key);
                stmt.setBytes(2, compressed);
                stmt.setInt(3, receipt.epoch());
                stmt.setBoolean(4, true);
                stmt.setByte(5, (byte) compressionConfig.codec().getNumber());

                var rowsInserted = stmt.executeUpdate();

                // Only update cache if insert was successful (not a duplicate)
                if (rowsInserted > 0) {
                    cache.put(key, receipt);
                }

            } catch (SQLException e) {
                throw new StorageException("Failed to store receipt: " + key, e);
            }

        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            throw new StorageException("Failed to compress receipt: " + key, e);
        }
    }

    @Override
    public Optional<AggregateWitnessReceipt> retrieve(String key) {
        Objects.requireNonNull(key, "key cannot be null");
        checkNotShutdown();

        // Check cache first
        var cached = cache.getIfPresent(key);
        if (cached != null) {
            return Optional.of(cached);
        }

        // Load from database
        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("SELECT receipt_data FROM aggregate_receipts WHERE receipt_key = ?")) {

            stmt.setString(1, key);

            try (var rs = stmt.executeQuery()) {
                if (rs.next()) {
                    var compressed = rs.getBytes("receipt_data");
                    var receipt = compressor.fromCompressedBytes(compressed);

                    // Populate cache
                    cache.put(key, receipt);

                    return Optional.of(receipt);
                }
            }

        } catch (SQLException e) {
            throw new StorageException("Failed to retrieve receipt: " + key, e);
        } catch (Exception e) {
            throw new StorageException("Failed to decompress receipt: " + key, e);
        }

        return Optional.empty();
    }

    @Override
    public boolean delete(String key) {
        Objects.requireNonNull(key, "key cannot be null");
        checkNotShutdown();

        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("DELETE FROM aggregate_receipts WHERE receipt_key = ?")) {

            stmt.setString(1, key);
            var rowsDeleted = stmt.executeUpdate();

            // Evict from cache
            cache.invalidate(key);

            return rowsDeleted > 0;

        } catch (SQLException e) {
            throw new StorageException("Failed to delete receipt: " + key, e);
        }
    }

    @Override
    public List<AggregateWitnessReceipt> listByEpoch(int epoch) {
        checkNotShutdown();

        var results = new ArrayList<AggregateWitnessReceipt>();

        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                 "SELECT receipt_key, receipt_data FROM aggregate_receipts WHERE epoch = ?")) {

            stmt.setInt(1, epoch);

            try (var rs = stmt.executeQuery()) {
                while (rs.next()) {
                    var key = rs.getString("receipt_key");
                    var compressed = rs.getBytes("receipt_data");
                    var receipt = compressor.fromCompressedBytes(compressed);

                    // Populate cache
                    cache.put(key, receipt);

                    results.add(receipt);
                }
            }

        } catch (SQLException e) {
            throw new StorageException("Failed to list receipts for epoch: " + epoch, e);
        } catch (Exception e) {
            throw new StorageException("Failed to decompress receipts for epoch: " + epoch, e);
        }

        return results;
    }

    @Override
    public int getStorageSize() {
        checkNotShutdown();

        try (var conn = dataSource.getConnection();
             var stmt = conn.prepareStatement("SELECT COUNT(*) FROM aggregate_receipts");
             var rs = stmt.executeQuery()) {

            if (rs.next()) {
                return rs.getInt(1);
            }

        } catch (SQLException e) {
            throw new StorageException("Failed to get storage size", e);
        }

        return 0;
    }

    @Override
    public void shutdown() {
        if (shutdown.compareAndSet(false, true)) {
            // Clear cache
            cache.invalidateAll();
            cache.cleanUp();
        }
    }

    /**
     * Check if store is shutdown and throw exception if so.
     */
    private void checkNotShutdown() {
        if (shutdown.get()) {
            throw new StorageException("Store is shutdown");
        }
    }

    /**
     * Get cache statistics for monitoring.
     *
     * @return Cache stats (hits, misses, evictions)
     */
    public com.github.benmanes.caffeine.cache.stats.CacheStats getCacheStats() {
        return cache.stats();
    }
}
