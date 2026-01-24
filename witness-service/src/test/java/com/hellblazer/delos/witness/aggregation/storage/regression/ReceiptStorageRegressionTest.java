/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.witness.aggregation.storage.regression;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.bls.BLSAggregate;
import com.hellblazer.delos.cryptography.bls.BLSSignature;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.aggregation.SignatureFormat;
import com.hellblazer.delos.witness.aggregation.compression.AggregateWitnessReceiptCompressor;
import com.hellblazer.delos.witness.aggregation.recursive.compression.CompressionConfig;
import com.hellblazer.delos.witness.aggregation.storage.jdbc.JdbcAggregateReceiptStore;
import com.hellblazer.delos.witness.aggregation.storage.memory.InMemoryAggregateReceiptStore;
import com.hellblazer.delos.witness.receipt.AggregateWitnessReceipt;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.*;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests to detect performance degradation.
 * Establishes and verifies performance baselines.
 * <p>
 * Performance Baselines:
 * - In-memory store: 1000 receipts in <100ms (1ms/receipt average)
 * - In-memory retrieve: <1ms per operation
 * - JDBC store: 100 receipts in <2000ms (20ms/receipt average)
 * - JDBC retrieve (cache hit): <1ms
 * - JDBC retrieve (cache miss): <50ms
 * <p>
 * Purpose:
 * - Detect performance regressions in storage operations
 * - Verify cache effectiveness
 * - Monitor compression overhead
 * - Ensure thread-safety performance
 * <p>
 * These baselines are conservative to avoid false failures
 * on slower CI/CD environments. Local development should
 * see much better performance.
 *
 * @author hal.hildebrand
 * @since Phase 3.4.6
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ReceiptStorageRegressionTest {

    private static final int STORE_COUNT = 1000;
    private static final long IN_MEMORY_STORE_TARGET_MS = 100; // 1000 stores in 100ms
    private static final long IN_MEMORY_RETRIEVE_TARGET_MS = 1; // <1ms for single retrieve
    private static final long JDBC_STORE_TARGET_MS = 2000; // 100 stores in 2000ms (conservative)
    private static final long JDBC_RETRIEVE_TARGET_MS = 50; // <50ms for DB retrieve

    private DataSource dataSource;
    private InMemoryAggregateReceiptStore inMemoryAggregateStore;
    private JdbcAggregateReceiptStore jdbcAggregateStore;

    @BeforeEach
    void setUp() throws Exception {
        // Create in-memory stores
        inMemoryAggregateStore = new InMemoryAggregateReceiptStore();

        // Create JDBC stores
        dataSource = createH2DataSource();
        applyMigrations(dataSource);

        jdbcAggregateStore = new JdbcAggregateReceiptStore(dataSource, new AggregateWitnessReceiptCompressor(),
                                                            CompressionConfig.FAST, 1000, 60);
    }

    @AfterEach
    void tearDown() {
        if (inMemoryAggregateStore != null) {
            inMemoryAggregateStore.shutdown();
        }
        if (jdbcAggregateStore != null) {
            jdbcAggregateStore.shutdown();
        }
        if (dataSource instanceof HikariDataSource ds) {
            ds.close();
        }
    }

    // ==================== In-Memory Store Regression Tests ====================

    @Test
    @Order(1)
    void shouldNotDegradeInMemoryStorePerformance() {
        // GIVEN: In-memory store and receipts
        var receipts = generateAggregateReceipts(STORE_COUNT);

        // WHEN: Store all receipts
        var start = System.nanoTime();
        for (var receipt : receipts) {
            inMemoryAggregateStore.store(toKey(receipt), receipt);
        }
        var elapsed = (System.nanoTime() - start) / 1_000_000;

        // THEN: Should meet baseline
        assertThat(elapsed).describedAs(
            "Storing %d receipts in-memory should be <%dms (baseline), got: %dms",
            STORE_COUNT, IN_MEMORY_STORE_TARGET_MS, elapsed
        ).isLessThan(IN_MEMORY_STORE_TARGET_MS);

        // Also verify throughput
        var throughput = (double) STORE_COUNT / (elapsed / 1000.0);
        assertThat(throughput).describedAs(
            "In-memory store throughput should be >10,000 receipts/sec, got: %.0f/sec",
            throughput
        ).isGreaterThan(10_000.0);
    }

    @Test
    @Order(2)
    void shouldNotDegradeInMemoryRetrievePerformance() {
        // GIVEN: In-memory store with data
        var receipts = generateAggregateReceipts(STORE_COUNT);
        for (var receipt : receipts) {
            inMemoryAggregateStore.store(toKey(receipt), receipt);
        }

        // Warmup
        for (int i = 0; i < 10; i++) {
            inMemoryAggregateStore.retrieve(toKey(receipts.get(500)));
        }

        // WHEN: Retrieve random receipt
        var randomReceipt = receipts.get(500);
        var start = System.nanoTime();
        inMemoryAggregateStore.retrieve(toKey(randomReceipt));
        var elapsed = (System.nanoTime() - start) / 1_000_000;

        // THEN: Should meet baseline
        assertThat(elapsed).describedAs(
            "Single in-memory retrieve should be <%dms (baseline), got: %dms",
            IN_MEMORY_RETRIEVE_TARGET_MS, elapsed
        ).isLessThan(IN_MEMORY_RETRIEVE_TARGET_MS);
    }

    // ==================== JDBC Store Regression Tests ====================

    @Test
    @Order(10)
    void shouldNotDegradeJdbcStorePerformance() {
        // GIVEN: JDBC store and receipts (fewer items due to slower JDBC)
        var receipts = generateAggregateReceipts(100);

        // WHEN: Store all receipts
        var start = System.nanoTime();
        for (var receipt : receipts) {
            jdbcAggregateStore.store(toKey(receipt), receipt);
        }
        var elapsed = (System.nanoTime() - start) / 1_000_000;

        // THEN: Should meet baseline (20ms per receipt average)
        assertThat(elapsed).describedAs(
            "Storing 100 receipts in JDBC should be <%dms (baseline), got: %dms",
            JDBC_STORE_TARGET_MS, elapsed
        ).isLessThan(JDBC_STORE_TARGET_MS);
    }

    @Test
    @Order(11)
    void shouldNotDegradeJdbcRetrievePerformance() throws Exception {
        // GIVEN: JDBC store with data
        var receipts = generateAggregateReceipts(100);
        for (var receipt : receipts) {
            jdbcAggregateStore.store(toKey(receipt), receipt);
        }

        // Create new store instance to ensure cache miss (DB hit)
        var freshStore = new JdbcAggregateReceiptStore(dataSource, new AggregateWitnessReceiptCompressor(),
                                                        CompressionConfig.FAST, 1000, 60);

        // WHEN: Retrieve from database (cache miss)
        var randomReceipt = receipts.get(50);
        var start = System.nanoTime();
        freshStore.retrieve(toKey(randomReceipt));
        var elapsed = (System.nanoTime() - start) / 1_000_000;

        // THEN: Should meet baseline
        assertThat(elapsed).describedAs(
            "JDBC retrieve (cache miss) should be <%dms (baseline), got: %dms",
            JDBC_RETRIEVE_TARGET_MS, elapsed
        ).isLessThan(JDBC_RETRIEVE_TARGET_MS);

        freshStore.shutdown();
    }

    @Test
    @Order(12)
    void shouldNotDegradeJdbcCacheHitPerformance() {
        // GIVEN: JDBC store with cached data
        var receipts = generateAggregateReceipts(100);
        for (var receipt : receipts) {
            jdbcAggregateStore.store(toKey(receipt), receipt);
        }

        // First retrieve to populate cache
        var randomReceipt = receipts.get(50);
        jdbcAggregateStore.retrieve(toKey(randomReceipt));

        // Warmup
        for (int i = 0; i < 10; i++) {
            jdbcAggregateStore.retrieve(toKey(randomReceipt));
        }

        // WHEN: Retrieve from cache (cache hit)
        var start = System.nanoTime();
        jdbcAggregateStore.retrieve(toKey(randomReceipt));
        var elapsed = (System.nanoTime() - start) / 1_000_000;

        // THEN: Cache hit should be fast (<1ms)
        assertThat(elapsed).describedAs(
            "JDBC retrieve (cache hit) should be <1ms (baseline), got: %dms",
            elapsed
        ).isLessThan(1L);
    }

    // ==================== Query Performance Regression Tests ====================

    @Test
    @Order(20)
    void shouldNotDegradeListByEpochPerformance() {
        // GIVEN: In-memory store with 1000 receipts across 10 epochs
        for (int i = 0; i < 1000; i++) {
            var receipt = createAggregateReceiptForEpoch("epoch-" + i, i % 10);
            inMemoryAggregateStore.store(toKey(receipt), receipt);
        }

        // WHEN: Query by epoch
        var start = System.nanoTime();
        inMemoryAggregateStore.listByEpoch(5);
        var elapsed = (System.nanoTime() - start) / 1_000_000;

        // THEN: Should be fast (<10ms for 1000 items)
        assertThat(elapsed).describedAs(
            "listByEpoch on 1000 items should be <10ms (baseline), got: %dms",
            elapsed
        ).isLessThan(10L);
    }

    // ==================== Compression Overhead Regression Tests ====================

    @Test
    @Order(30)
    void shouldNotDegradeCompressionOverhead() {
        // GIVEN: Receipt and compressor
        var receipt = createAggregateReceipt("compression-overhead", 50);
        var compressor = new AggregateWitnessReceiptCompressor();

        // WHEN: Measure compression overhead
        var metrics = compressor.getMetrics(receipt, CompressionConfig.BEST);

        // THEN: Compression should be reasonably fast (<200ms for first call)
        var latencyMs = metrics.compressionTimeNs() / 1_000_000.0;
        assertThat(latencyMs).describedAs(
            "Compression latency should be <200ms (baseline), got: %.2fms",
            latencyMs
        ).isLessThan(200.0);
    }

    // ==================== Concurrent Performance Regression Tests ====================

    @Test
    @Order(40)
    void shouldNotDegradeConcurrentStorePerformance() throws InterruptedException {
        // GIVEN: 10 threads each storing 100 receipts
        var threads = new ArrayList<Thread>();
        var receiptsPerThread = 100;
        var threadCount = 10;

        var start = System.nanoTime();

        for (int t = 0; t < threadCount; t++) {
            var threadId = t;
            var thread = new Thread(() -> {
                for (int i = 0; i < receiptsPerThread; i++) {
                    var receipt = createAggregateReceipt("thread-" + threadId + "-" + i, 50);
                    inMemoryAggregateStore.store(toKey(receipt), receipt);
                }
            });
            threads.add(thread);
            thread.start();
        }

        for (var thread : threads) {
            thread.join();
        }

        var elapsed = (System.nanoTime() - start) / 1_000_000;

        // THEN: Should complete in reasonable time
        assertThat(elapsed).describedAs(
            "Concurrent stores (10 threads × 100 receipts) should be <5000ms (baseline), got: %dms",
            elapsed
        ).isLessThan(5000L);

        // Verify throughput
        var totalReceipts = threadCount * receiptsPerThread;
        var throughput = (double) totalReceipts / (elapsed / 1000.0);
        assertThat(throughput).describedAs(
            "Concurrent throughput should be >200 receipts/sec, got: %.0f/sec",
            throughput
        ).isGreaterThan(200.0);
    }

    // ==================== Helper Methods ====================

    private List<AggregateWitnessReceipt> generateAggregateReceipts(int count) {
        var receipts = new ArrayList<AggregateWitnessReceipt>();
        for (int i = 0; i < count; i++) {
            receipts.add(createAggregateReceipt("receipt-" + i, 50));
        }
        return receipts;
    }

    private AggregateWitnessReceipt createAggregateReceipt(String id, int signerCount) {
        var digest = DigestAlgorithm.DEFAULT.digest(id);
        var identifier = new SelfAddressingIdentifier(digest);
        var event = new EventCoordinates(identifier, org.joou.ULong.valueOf(0), digest, "icp");

        var sigBytes = new byte[96]; // BLS signature
        for (int i = 0; i < sigBytes.length; i++) {
            sigBytes[i] = (byte) (i % 256);
        }

        var bitmapSize = (signerCount + 7) / 8;
        var bitmap = new byte[Math.max(1, bitmapSize)];
        for (int i = 0; i < bitmap.length; i++) {
            bitmap[i] = (byte) 0xFF;
        }

        var aggregate = new BLSAggregate(new BLSSignature(sigBytes), bitmap);

        var signerIndices = new ArrayList<Integer>();
        for (int i = 0; i < signerCount; i++) {
            signerIndices.add(i);
        }

        return new AggregateWitnessReceipt(event, aggregate, signerIndices, SignatureFormat.BLS_12_381,
                                            System.currentTimeMillis(), 5);
    }

    private AggregateWitnessReceipt createAggregateReceiptForEpoch(String id, int epoch) {
        var digest = DigestAlgorithm.DEFAULT.digest(id);
        var identifier = new SelfAddressingIdentifier(digest);
        var event = new EventCoordinates(identifier, org.joou.ULong.valueOf(0), digest, "icp");

        var sigBytes = new byte[96];
        for (int i = 0; i < sigBytes.length; i++) {
            sigBytes[i] = (byte) (i % 256);
        }

        var bitmap = new byte[7]; // 50 signers
        for (int i = 0; i < bitmap.length; i++) {
            bitmap[i] = (byte) 0xFF;
        }

        var aggregate = new BLSAggregate(new BLSSignature(sigBytes), bitmap);

        var signerIndices = new ArrayList<Integer>();
        for (int i = 0; i < 50; i++) {
            signerIndices.add(i);
        }

        return new AggregateWitnessReceipt(event, aggregate, signerIndices, SignatureFormat.BLS_12_381,
                                            System.currentTimeMillis(), epoch);
    }

    private String toKey(AggregateWitnessReceipt receipt) {
        return receipt.event().toString();
    }

    private DataSource createH2DataSource() {
        var config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:test_regression_" + System.nanoTime() + ";MODE=MySQL");
        config.setUsername("sa");
        config.setPassword("");
        config.setMaximumPoolSize(10);
        config.setAutoCommit(true);
        return new HikariDataSource(config);
    }

    private void applyMigrations(DataSource ds) throws Exception {
        try (var conn = ds.getConnection()) {
            var database = DatabaseFactory.getInstance()
                                          .findCorrectDatabaseImplementation(new JdbcConnection(conn));
            var liquibase = new Liquibase("db/changelog/db.changelog-master.yaml", new ClassLoaderResourceAccessor(),
                                          database);
            liquibase.update(new Contexts());
        }
    }
}
