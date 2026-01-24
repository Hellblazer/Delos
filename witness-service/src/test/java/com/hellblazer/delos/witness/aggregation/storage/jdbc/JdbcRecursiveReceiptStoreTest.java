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
import com.hellblazer.delos.witness.aggregation.HierarchicalAggregate;
import com.hellblazer.delos.witness.aggregation.TreeNode;
import com.hellblazer.delos.witness.aggregation.TreeConfiguration;
import com.hellblazer.delos.witness.aggregation.recursive.CompressionCodec;
import com.hellblazer.delos.witness.aggregation.recursive.RecursiveAggregateReceipt;
import com.hellblazer.delos.witness.aggregation.recursive.compression.CompressionConfig;
import com.hellblazer.delos.witness.aggregation.recursive.compression.RecursiveAggregateReceiptCompressor;
import com.hellblazer.delos.witness.aggregation.storage.ReceiptStore;
import com.hellblazer.delos.witness.aggregation.storage.RecursiveReceiptStore;
import com.hellblazer.delos.witness.aggregation.storage.test.ReceiptStoreContract;
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
 * Test class for JdbcRecursiveReceiptStore extending ReceiptStoreContract.
 * <p>
 * Uses H2 in-memory database with Liquibase migrations for schema setup.
 * Tests CRUD operations, compression integration, epoch range queries, and concurrency.
 *
 * @author hal.hildebrand
 */
public class JdbcRecursiveReceiptStoreTest extends ReceiptStoreContract<RecursiveAggregateReceipt> {

    private DataSource dataSource;
    private JdbcRecursiveReceiptStore jdbcStore;
    private final Random random = new Random(42); // Deterministic for tests

    @Override
    protected ReceiptStore<RecursiveAggregateReceipt> createStore() {
        try {
            // Create H2 in-memory database
            var config = new HikariConfig();
            config.setJdbcUrl("jdbc:h2:mem:test_recursive_" + System.nanoTime() + ";MODE=MySQL");
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

            jdbcStore = new JdbcRecursiveReceiptStore(dataSource, new RecursiveAggregateReceiptCompressor(),
                                                       CompressionConfig.FAST, 1000);
            return jdbcStore;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create JDBC store", e);
        }
    }

    @Override
    protected RecursiveAggregateReceipt createReceipt(String key, int epoch) {
        // Create a test EventCoordinates from key
        var digest = com.hellblazer.delos.cryptography.DigestAlgorithm.DEFAULT.digest(key);
        var identifier = new SelfAddressingIdentifier(digest);
        var event = new EventCoordinates(identifier, org.joou.ULong.valueOf(0), digest, "icp");

        // Build RecursiveAggregateReceipt with minimal HierarchicalAggregate
        return RecursiveAggregateReceipt.builder()
                                        .baseAggregate(createMinimalHierarchical(event))
                                        .epochs(epoch, epoch)
                                        .event(event)
                                        .totalUniqueSigners(10)
                                        .compressionCodec(CompressionCodec.NONE)
                                        .build();
    }

    @Override
    protected String getKey(RecursiveAggregateReceipt receipt) {
        return receipt.event().toString();
    }

    @Override
    protected int getEpoch(RecursiveAggregateReceipt receipt) {
        return (int) receipt.endEpoch(); // Use endEpoch for queries
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
     * Test epoch range query.
     */
    @Test
    void testGetReceiptsByEpochRange() {
        var event1 = createTestEvent("range-1");
        var receipt1 = RecursiveAggregateReceipt.builder()
                                                .baseAggregate(createMinimalHierarchical(event1))
                                                .epochs(0, 5)
                                                .event(event1)
                                                .totalUniqueSigners(10)
                                                .compressionCodec(CompressionCodec.NONE)
                                                .build();

        var event2 = createTestEvent("range-2");
        var receipt2 = RecursiveAggregateReceipt.builder()
                                                .baseAggregate(createMinimalHierarchical(event2))
                                                .epochs(10, 15)
                                                .event(event2)
                                                .totalUniqueSigners(10)
                                                .compressionCodec(CompressionCodec.NONE)
                                                .build();

        store.store(getKey(receipt1), receipt1);
        store.store(getKey(receipt2), receipt2);

        // Query overlapping ranges
        var range1 = ((RecursiveReceiptStore) store).getReceiptsByEpochRange(0, 5);
        assertEquals(1, range1.size());
        assertTrue(range1.contains(receipt1));

        var range2 = ((RecursiveReceiptStore) store).getReceiptsByEpochRange(10, 15);
        assertEquals(1, range2.size());
        assertTrue(range2.contains(receipt2));

        var range3 = ((RecursiveReceiptStore) store).getReceiptsByEpochRange(0, 20);
        assertEquals(2, range3.size());

        var range4 = ((RecursiveReceiptStore) store).getReceiptsByEpochRange(20, 30);
        assertTrue(range4.isEmpty());
    }

    /**
     * Test compression round-trip.
     */
    @Test
    void testCompressionRoundTrip() {
        var receipt = createReceipt("compression-test", 0);
        var key = getKey(receipt);

        // Store with compression
        jdbcStore.store(key, receipt);

        // Retrieve and verify
        var retrieved = jdbcStore.retrieve(key);
        assertTrue(retrieved.isPresent());
        assertEquals(receipt, retrieved.get());
    }

    /**
     * Test cache behavior.
     */
    @Test
    void testCacheBehavior() {
        var receipt = createReceipt("cache-test", 0);
        var key = getKey(receipt);

        // Store receipt
        jdbcStore.store(key, receipt);

        // First retrieval (cache miss)
        var start1 = System.nanoTime();
        var retrieved1 = jdbcStore.retrieve(key);
        var elapsed1 = System.nanoTime() - start1;
        assertTrue(retrieved1.isPresent());

        // Second retrieval (cache hit)
        var start2 = System.nanoTime();
        var retrieved2 = jdbcStore.retrieve(key);
        var elapsed2 = System.nanoTime() - start2;
        assertTrue(retrieved2.isPresent());

        // Cache hit should be faster
        assertTrue(elapsed2 < elapsed1);
    }

    private HierarchicalAggregate createMinimalHierarchical(EventCoordinates event) {
        var signatureBytes = new byte[96];
        random.nextBytes(signatureBytes);
        var signature = new BLSSignature(signatureBytes);
        var bitmapBytes = new byte[8];
        random.nextBytes(bitmapBytes);

        // Create a LeafNode with proper parameters
        var leafNode = new TreeNode.LeafNode(1L, signature, 10, bitmapBytes, 1, 0, java.util.Optional.empty());

        // Create minimal TreeConfiguration
        var treeConfig = TreeConfiguration.create(1, 8);

        return new HierarchicalAggregate(leafNode, treeConfig, event, 10, 1);
    }

    private EventCoordinates createTestEvent(String seed) {
        var digest = com.hellblazer.delos.cryptography.DigestAlgorithm.DEFAULT.digest(seed);
        var identifier = new SelfAddressingIdentifier(digest);
        return new EventCoordinates(identifier, org.joou.ULong.valueOf(0), digest, "icp");
    }
}
