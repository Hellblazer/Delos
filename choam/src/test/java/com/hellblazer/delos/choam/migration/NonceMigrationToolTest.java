/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.migration;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test nonce migration tool for safe transition to persistent storage
 *
 * @author hal.hildebrand
 */
public class NonceMigrationToolTest {

    private NonceMigrationTool               migration;
    private MockNonceStore                   oldStore;
    private MockNonceStore                   newStore;

    @BeforeEach
    public void setUp() {
        oldStore = new MockNonceStore();
        newStore = new MockNonceStore();
        migration = new NonceMigrationTool(oldStore, newStore);
        migration.setGracePeriod(Duration.ofMillis(100)); // Short for tests
    }

    @AfterEach
    public void tearDown() {
        migration.shutdown();
    }

    @Test
    public void testSuccessfulMigration() {
        // Populate old store
        var digest1 = DigestAlgorithm.DEFAULT.getOrigin();
        var digest2 = DigestAlgorithm.DEFAULT.digest("test".getBytes());
        oldStore.data.put(digest1, 10);
        oldStore.data.put(digest2, 20);

        // Execute migration
        var result = migration.migrate();

        // Verify success
        assertTrue(result.success(), "Migration should succeed");
        assertEquals(2, result.snapshot().size(), "Snapshot should have 2 entries");

        // Verify new store initialized
        assertTrue(newStore.initialized, "New store should be initialized");
        assertEquals(10, newStore.data.get(digest1), "Nonce value should match");
        assertEquals(20, newStore.data.get(digest2), "Nonce value should match");
    }

    @Test
    public void testMigrationWithInFlightTransactions() throws InterruptedException {
        // Populate old store
        var digest1 = DigestAlgorithm.DEFAULT.getOrigin();
        oldStore.data.put(digest1, 10);

        // Start migration
        var migrationThread = new Thread(() -> migration.migrate());
        migrationThread.start();

        // Simulate in-flight transaction during migration
        Thread.sleep(50); // During grace period
        oldStore.data.put(digest1, 11); // Nonce incremented

        // Wait for migration to complete
        migrationThread.join();

        // Verify new store has updated nonce
        assertTrue(newStore.data.get(digest1) >= 11,
                  "New store should have nonce >= 11 after grace period sync");
    }

    @Test
    public void testRollback() {
        // Setup and migrate
        var digest1 = DigestAlgorithm.DEFAULT.getOrigin();
        oldStore.data.put(digest1, 10);
        var result = migration.migrate();
        assertTrue(result.success());

        // Simulate problem - corrupt new store
        newStore.data.put(digest1, 5); // Regression!

        // Rollback
        var rollbackResult = migration.rollback();
        assertTrue(rollbackResult.success(), "Rollback should succeed");

        // Verify old store restored
        assertEquals(10, oldStore.data.get(digest1), "Old store should be restored");

        // Verify new store cleared
        assertTrue(newStore.cleared, "New store should be cleared");
    }

    @Test
    public void testValidationFailsOnMissingEntries() {
        // Populate old store
        var digest1 = DigestAlgorithm.DEFAULT.getOrigin();
        var digest2 = DigestAlgorithm.DEFAULT.digest("test".getBytes());
        oldStore.data.put(digest1, 10);
        oldStore.data.put(digest2, 20);

        // Make new store fail to store one entry
        newStore.failOnInitialize = true;

        // Execute migration
        var result = migration.migrate();

        // Verify validation detected the problem
        assertFalse(result.success(), "Migration should fail validation");
        assertTrue(result.message().contains("validation"), "Message should mention validation");
    }

    @Test
    public void testValidationFailsOnNonceRegression() {
        // Populate old store
        var digest1 = DigestAlgorithm.DEFAULT.getOrigin();
        oldStore.data.put(digest1, 10);

        // Make new store corrupt nonce value
        newStore.corruptNonces = true;

        // Execute migration
        var result = migration.migrate();

        // Verify validation detected regression
        assertFalse(result.success(), "Migration should fail on nonce regression");
    }

    @Test
    public void testCannotMigrateTwice() {
        // First migration
        oldStore.data.put(DigestAlgorithm.DEFAULT.getOrigin(), 10);
        var result1 = migration.migrate();
        assertTrue(result1.success());

        // Second migration
        var result2 = migration.migrate();
        assertFalse(result2.success(), "Second migration should fail");
        assertTrue(result2.message().contains("already"), "Message should say already executed");
    }

    @Test
    public void testRollbackWithoutMigration() {
        // Try rollback without migration
        var result = migration.rollback();
        assertFalse(result.success(), "Rollback should fail without migration");
        assertTrue(result.message().contains("No migration"), "Message should say no migration");
    }

    @Test
    public void testGracePeriodAllowsCompletingTransactions() throws InterruptedException {
        // Longer grace period for this test
        migration.setGracePeriod(Duration.ofMillis(200));

        // Populate old store
        var digest1 = DigestAlgorithm.DEFAULT.getOrigin();
        oldStore.data.put(digest1, 10);

        // Start migration in background
        var future = new Thread(() -> migration.migrate());
        future.start();

        // Simulate slow transaction
        Thread.sleep(100); // Midway through grace period
        oldStore.data.put(digest1, 15);

        // Wait for migration
        future.join();

        // New store should have picked up the increment
        assertTrue(newStore.data.get(digest1) >= 15,
                  "Grace period should allow slow transactions to complete");
    }

    @Test
    public void testLargeNonceSet() {
        // Populate with many nonces
        for (int i = 0; i < 1000; i++) {
            var digest = DigestAlgorithm.DEFAULT.digest(("test" + i).getBytes());
            oldStore.data.put(digest, i * 10);
        }

        // Execute migration
        var result = migration.migrate();

        // Verify success
        assertTrue(result.success(), "Migration of large set should succeed");
        assertEquals(1000, result.snapshot().size(), "Should migrate all 1000 entries");
        assertEquals(1000, newStore.data.size(), "New store should have 1000 entries");
    }

    /**
     * Mock nonce store for testing
     */
    private static class MockNonceStore implements NonceMigrationTool.NonceStore {
        final Map<Digest, Integer> data = new ConcurrentHashMap<>();
        boolean initialized = false;
        boolean cleared = false;
        boolean failOnInitialize = false;
        boolean corruptNonces = false;

        @Override
        public Map<Digest, Integer> snapshot() {
            return new ConcurrentHashMap<>(data);
        }

        @Override
        public Map<Digest, Integer> delta(Map<Digest, Integer> baseline) {
            Map<Digest, Integer> delta = new ConcurrentHashMap<>();
            for (var entry : data.entrySet()) {
                var baselineValue = baseline.get(entry.getKey());
                if (baselineValue == null || !baselineValue.equals(entry.getValue())) {
                    delta.put(entry.getKey(), entry.getValue());
                }
            }
            return delta;
        }

        @Override
        public void initialize(Map<Digest, Integer> initData) {
            if (failOnInitialize) {
                // Simulate partial failure - only store half
                int count = 0;
                for (var entry : initData.entrySet()) {
                    if (count++ < initData.size() / 2) {
                        data.put(entry.getKey(), corruptNonces ? entry.getValue() - 1 : entry.getValue());
                    }
                }
            } else {
                for (var entry : initData.entrySet()) {
                    data.put(entry.getKey(), corruptNonces ? entry.getValue() - 1 : entry.getValue());
                }
            }
            initialized = true;
        }

        @Override
        public void update(Map<Digest, Integer> updateData) {
            data.putAll(updateData);
        }

        @Override
        public void restore(Map<Digest, Integer> restoreData) {
            data.clear();
            data.putAll(restoreData);
        }

        @Override
        public void clear() {
            data.clear();
            cleared = true;
        }
    }
}
