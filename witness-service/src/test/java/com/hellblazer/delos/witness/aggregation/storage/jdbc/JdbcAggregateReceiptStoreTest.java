/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.witness.aggregation.storage.jdbc;

import com.hellblazer.delos.cryptography.bls.BLSAggregate;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.aggregation.SignatureFormat;
import com.hellblazer.delos.witness.aggregation.compression.AggregateWitnessReceiptCompressor;
import com.hellblazer.delos.witness.aggregation.recursive.compression.CompressionConfig;
import com.hellblazer.delos.witness.aggregation.storage.AggregateReceiptStore;
import com.hellblazer.delos.witness.aggregation.storage.ReceiptStore;
import com.hellblazer.delos.witness.aggregation.storage.test.ReceiptStoreContract;
import com.hellblazer.delos.witness.receipt.AggregateWitnessReceipt;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for JdbcAggregateReceiptStore extending ReceiptStoreContract.
 * <p>
 * Uses H2 in-memory database with Liquibase migrations for schema setup.
 * Tests CRUD operations, compression integration, caching, and concurrency.
 * <p>
 * Performance targets:
 * - Store operation: <10ms
 * - Retrieve (cache hit): <1ms
 * - Retrieve (cache miss): <50ms
 *
 * @author hal.hildebrand
 */
public class JdbcAggregateReceiptStoreTest extends ReceiptStoreContract<AggregateWitnessReceipt> {

    private DataSource dataSource;
    private JdbcAggregateReceiptStore jdbcStore;
    private final Random random = new Random(42); // Deterministic for tests

    @Override
    protected ReceiptStore<AggregateWitnessReceipt> createStore() {
        try {
            // Create H2 in-memory database
            var config = new HikariConfig();
            config.setJdbcUrl("jdbc:h2:mem:test_aggregate_" + System.nanoTime() + ";MODE=MySQL");
            config.setUsername("sa");
            config.setPassword("");
            config.setMaximumPoolSize(10);
            config.setAutoCommit(true);

            dataSource = new HikariDataSource(config);

            // Apply Liquibase migrations
            try (var conn = dataSource.getConnection()) {
                var database = DatabaseFactory.getInstance()
                                              .findCorrectDatabaseImplementation(new JdbcConnection(conn));
                var liquibase = new Liquibase("db/changelog/db.changelog-master.yaml",
                                              new ClassLoaderResourceAccessor(), database);
                liquibase.update(new Contexts());
            }

            jdbcStore = new JdbcAggregateReceiptStore(dataSource, new AggregateWitnessReceiptCompressor(),
                                                       CompressionConfig.FAST, 1000);
            return jdbcStore;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create JDBC store", e);
        }
    }

    @Override
    protected AggregateWitnessReceipt createReceipt(String key, int epoch) {
        // Create a test EventCoordinates from key
        var digest = com.hellblazer.delos.cryptography.DigestAlgorithm.DEFAULT.digest(key);
        var identifier = new SelfAddressingIdentifier(digest);
        var event = new EventCoordinates(identifier, org.joou.ULong.valueOf(0), digest, "icp");

        // Create a simple BLS aggregate signature for testing
        var signatureBytes = new byte[96]; // BLS signature is 96 bytes
        random.nextBytes(signatureBytes);
        var signature = new BLSSignature(signatureBytes);

        var bitmapBytes = new byte[8]; // Small bitmap
        random.nextBytes(bitmapBytes);
        var aggregate = new BLSAggregate(signature, bitmapBytes);

        return new AggregateWitnessReceipt(event, aggregate, List.of(0, 1, 2), SignatureFormat.BLS_12_381,
                                           System.currentTimeMillis(), epoch);
    }

    @Override
    protected String getKey(AggregateWitnessReceipt receipt) {
        return receipt.event().toString();
    }

    @Override
    protected int getEpoch(AggregateWitnessReceipt receipt) {
        return receipt.epoch();
    }

    @AfterEach
    void tearDownDatabase() {
        if (jdbcStore != null) {
            jdbcStore.shutdown();
        }
        if (dataSource instanceof HikariDataSource hikari) {
            hikari.close();
        }
    }

    /**
     * Test that compression is applied transparently.
     */
    @Test
    void testCompressionRoundTrip() {
        var receipt = createReceipt("compression-test", 0);
        var key = getKey(receipt);

        // Store with compression
        jdbcStore.store(key, receipt);

        // Retrieve and verify
        var retrieved = jdbcStore.retrieve(key);
        assertTrue(retrieved.isPresent(), "Compressed receipt should be retrievable");
        assertEquals(receipt, retrieved.get(), "Retrieved receipt should match original after decompression");
    }

    /**
     * Test cache behavior (hits, misses).
     */
    @Test
    void testCacheBehavior() {
        var receipt = createReceipt("cache-test", 0);
        var key = getKey(receipt);

        // Store receipt
        jdbcStore.store(key, receipt);

        // First retrieval (cache miss, loads from DB)
        var start1 = System.nanoTime();
        var retrieved1 = jdbcStore.retrieve(key);
        var elapsed1 = System.nanoTime() - start1;
        assertTrue(retrieved1.isPresent());

        // Second retrieval (cache hit, much faster)
        var start2 = System.nanoTime();
        var retrieved2 = jdbcStore.retrieve(key);
        var elapsed2 = System.nanoTime() - start2;
        assertTrue(retrieved2.isPresent());

        // Cache hit should be faster than cache miss
        assertTrue(elapsed2 < elapsed1, "Cache hit should be faster than cache miss");
    }

    /**
     * Test getReceiptForEvent convenience method.
     */
    @Test
    void testGetReceiptForEvent() {
        var receipt = createReceipt("event-test", 0);
        var key = getKey(receipt);

        // Store receipt
        store.store(key, receipt);

        // Retrieve by EventCoordinates
        var retrieved = ((AggregateReceiptStore) store).getReceiptForEvent(receipt.event());
        assertTrue(retrieved.isPresent(), "Should find receipt by event coordinates");
        assertEquals(receipt, retrieved.get());
    }

    /**
     * Test storage size estimation.
     */
    @Test
    void testStorageSize() throws SQLException {
        assertEquals(0, store.getStorageSize(), "Empty store should have size 0");

        var receipt1 = createReceipt("size-test-1", 0);
        var receipt2 = createReceipt("size-test-2", 0);

        store.store(getKey(receipt1), receipt1);
        assertEquals(1, store.getStorageSize());

        store.store(getKey(receipt2), receipt2);
        assertEquals(2, store.getStorageSize());

        store.delete(getKey(receipt1));
        assertEquals(1, store.getStorageSize());
    }

    /**
     * Test concurrent access with multiple threads.
     */
    @Test
    void testConcurrentAccess() throws InterruptedException {
        final int threadCount = 10;
        final int opsPerThread = 50;

        var threads = new Thread[threadCount];
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < opsPerThread; i++) {
                    var receipt = createReceipt("concurrent-" + threadId + "-" + i, threadId);
                    store.store(getKey(receipt), receipt);
                }
            });
            threads[t].start();
        }

        for (var thread : threads) {
            thread.join();
        }

        assertEquals(threadCount * opsPerThread, store.getStorageSize(),
                     "All concurrent operations should succeed");
    }
}
