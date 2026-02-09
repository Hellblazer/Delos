/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for NonceTracker - replay protection via persistent nonce tracking.
 *
 * Tests DoS protection via sliding window (10,000 nonces per source) and
 * block height-based expiration.
 *
 * @author hal.hildebrand
 */
public class NonceTrackerTest {

    @TempDir
    Path tempDir;

    private PersistentNonceStore persistentStore;
    private InMemoryNonceStore memoryStore;

    @BeforeEach
    public void setUp() {
        var storeFile = tempDir.resolve("nonce-test.mv.db").toFile();
        persistentStore = new PersistentNonceStore(storeFile);
        memoryStore = new InMemoryNonceStore();
    }

    @AfterEach
    public void tearDown() {
        if (persistentStore != null) {
            persistentStore.close();
        }
    }

    @Test
    public void testBasicNonceIncrement() {
        var source = DigestAlgorithm.DEFAULT.getOrigin();

        assertEquals(0, persistentStore.getAndIncrement(source));
        assertEquals(1, persistentStore.getAndIncrement(source));
        assertEquals(2, persistentStore.getAndIncrement(source));

        assertEquals(0, memoryStore.getAndIncrement(source));
        assertEquals(1, memoryStore.getAndIncrement(source));
    }

    @Test
    public void testNonceValidation() {
        var source = DigestAlgorithm.DEFAULT.getOrigin();

        // First nonce should be 0
        int nonce0 = persistentStore.getAndIncrement(source);
        assertEquals(0, nonce0);

        // Nonce 1 should be valid (next expected nonce)
        assertTrue(persistentStore.validateNonce(source, 1));

        // Nonce 0 should be invalid (already used)
        assertFalse(persistentStore.validateNonce(source, 0));

        // Nonce -1 should be invalid (negative)
        assertFalse(persistentStore.validateNonce(source, -1));

        // Get next nonce (1)
        int nonce1 = persistentStore.getAndIncrement(source);
        assertEquals(1, nonce1);

        // Nonce 0 should now be invalid (replay)
        assertFalse(persistentStore.validateNonce(source, 0));
    }

    @Test
    public void testPersistenceAcrossRestarts() throws Exception {
        var source = DigestAlgorithm.DEFAULT.getOrigin();
        var storeFile = tempDir.resolve("persistence-test.mv.db").toFile();

        // First session: increment nonce to 5
        try (var store1 = new PersistentNonceStore(storeFile)) {
            for (int i = 0; i < 5; i++) {
                assertEquals(i, store1.getAndIncrement(source));
            }
        }

        // Second session: nonce should resume at 5
        try (var store2 = new PersistentNonceStore(storeFile)) {
            assertEquals(5, store2.getAndIncrement(source));
            assertEquals(6, store2.getAndIncrement(source));
        }

        // Verify replay attack prevention
        try (var store3 = new PersistentNonceStore(storeFile)) {
            assertFalse(store3.validateNonce(source, 0), "Old nonce 0 should be invalid");
            assertFalse(store3.validateNonce(source, 3), "Old nonce 3 should be invalid");
            assertTrue(store3.validateNonce(source, 7), "Next nonce 7 should be valid");
        }
    }

    @Test
    public void testStrictOrderingEnforcement() {
        var source = DigestAlgorithm.DEFAULT.getOrigin();

        // Use several nonces
        for (int i = 0; i < 100; i++) {
            assertEquals(i, persistentStore.getAndIncrement(source));
        }

        // Current nonce is 100, so only nonce 100 should be valid
        assertTrue(persistentStore.validateNonce(source, 100));

        // All previously used nonces should be invalid
        assertFalse(persistentStore.validateNonce(source, 0));
        assertFalse(persistentStore.validateNonce(source, 50));
        assertFalse(persistentStore.validateNonce(source, 99));

        // Future nonces should be invalid
        assertFalse(persistentStore.validateNonce(source, 101));
        assertFalse(persistentStore.validateNonce(source, 1000));
    }

    @Test
    public void testBlockHeightExpiration() {
        var source = DigestAlgorithm.DEFAULT.getOrigin();
        long initialHeight = 1000L;

        persistentStore.checkpoint(initialHeight);

        // Get some nonces at height 1000
        for (int i = 0; i < 5; i++) {
            persistentStore.getAndIncrement(source);
        }

        // Next expected nonce is 5 (already used 0-4)
        assertTrue(persistentStore.validateNonce(source, 5));

        // Already-used nonces should be invalid
        assertFalse(persistentStore.validateNonce(source, 0));
        assertFalse(persistentStore.validateNonce(source, 4));

        // Advance to height 11001 (beyond 10,000 block window)
        persistentStore.checkpoint(11001L);

        // Entry should be expired - nonce 5 should now be invalid
        assertFalse(persistentStore.validateNonce(source, 5),
                   "Nonces from height 1000 should expire at height 11001");

        // New nonce should start fresh at 0 after expiration
        int newNonce = persistentStore.getAndIncrement(source);
        assertEquals(0, newNonce, "After expiration, nonce counter should reset to 0");

        // Next expected nonce is now 1
        assertTrue(persistentStore.validateNonce(source, 1));
    }

    @Test
    public void testConcurrentAccess() throws InterruptedException {
        var source = DigestAlgorithm.DEFAULT.getOrigin();
        int numThreads = 10;
        int noncesPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch latch = new CountDownLatch(numThreads);
        List<Integer> allNonces = new CopyOnWriteArrayList<>();

        for (int t = 0; t < numThreads; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < noncesPerThread; i++) {
                        int nonce = persistentStore.getAndIncrement(source);
                        allNonces.add(nonce);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Concurrent operations should complete");
        executor.shutdown();

        // Should have exactly numThreads * noncesPerThread unique nonces
        assertEquals(numThreads * noncesPerThread, allNonces.size());

        // All nonces should be unique (no duplicates from race conditions)
        var uniqueNonces = new java.util.HashSet<>(allNonces);
        assertEquals(allNonces.size(), uniqueNonces.size(),
                    "All nonces should be unique (no race condition duplicates)");
    }

    @Test
    public void testMultipleSourcesIndependent() {
        var source1 = DigestAlgorithm.DEFAULT.digest("source1".getBytes());
        var source2 = DigestAlgorithm.DEFAULT.digest("source2".getBytes());

        // Each source should have independent nonce sequence
        assertEquals(0, persistentStore.getAndIncrement(source1));
        assertEquals(0, persistentStore.getAndIncrement(source2));
        assertEquals(1, persistentStore.getAndIncrement(source1));
        assertEquals(1, persistentStore.getAndIncrement(source2));

        // Validation should be independent - each source expects nonce 2 next
        assertTrue(persistentStore.validateNonce(source1, 2));
        assertTrue(persistentStore.validateNonce(source2, 2));
        assertFalse(persistentStore.validateNonce(source1, 0)); // Used
        assertFalse(persistentStore.validateNonce(source1, 1)); // Used
        assertFalse(persistentStore.validateNonce(source2, 0)); // Used
        assertFalse(persistentStore.validateNonce(source2, 1)); // Used
    }

    @Test
    public void testClear() {
        var source = DigestAlgorithm.DEFAULT.getOrigin();

        // Add some nonces
        for (int i = 0; i < 5; i++) {
            persistentStore.getAndIncrement(source);
        }

        // Clear should reset
        persistentStore.clear();

        // Nonce should restart at 0
        assertEquals(0, persistentStore.getAndIncrement(source));
    }

    @Test
    public void testInMemoryStoreNoPersistence() throws Exception {
        var source = DigestAlgorithm.DEFAULT.getOrigin();

        // Increment nonce
        assertEquals(0, memoryStore.getAndIncrement(source));
        assertEquals(1, memoryStore.getAndIncrement(source));

        // Create new instance (simulates restart)
        var newMemoryStore = new InMemoryNonceStore();

        // Should start from 0 (no persistence)
        assertEquals(0, newMemoryStore.getAndIncrement(source));
    }

    @Test
    public void testInMemoryStrictOrdering() {
        var source = DigestAlgorithm.DEFAULT.getOrigin();

        // New source: only nonce 0 should be valid
        assertTrue(memoryStore.validateNonce(source, 0), "New source should accept nonce 0");
        assertFalse(memoryStore.validateNonce(source, 1), "New source should reject nonce 1");
        assertFalse(memoryStore.validateNonce(source, -1), "New source should reject negative nonces");

        // Use nonce 0
        assertEquals(0, memoryStore.getAndIncrement(source));

        // Now only nonce 1 should be valid
        assertFalse(memoryStore.validateNonce(source, 0), "Used nonce 0 should be invalid (replay)");
        assertTrue(memoryStore.validateNonce(source, 1), "Next nonce 1 should be valid");
        assertFalse(memoryStore.validateNonce(source, 2), "Future nonce 2 should be invalid");

        // Use nonce 1
        assertEquals(1, memoryStore.getAndIncrement(source));

        // Now only nonce 2 should be valid
        assertFalse(memoryStore.validateNonce(source, 0), "Old nonce 0 should be invalid");
        assertFalse(memoryStore.validateNonce(source, 1), "Used nonce 1 should be invalid");
        assertTrue(memoryStore.validateNonce(source, 2), "Next nonce 2 should be valid");
        assertFalse(memoryStore.validateNonce(source, 3), "Future nonce 3 should be invalid");
    }

    @Test
    public void testInMemoryReplayProtection() {
        var source = DigestAlgorithm.DEFAULT.getOrigin();

        // Use several nonces
        for (int i = 0; i < 100; i++) {
            assertEquals(i, memoryStore.getAndIncrement(source));
        }

        // Current nonce is 100, so only nonce 100 should be valid
        assertTrue(memoryStore.validateNonce(source, 100), "Next expected nonce should be valid");

        // All previously used nonces should be invalid (replay protection)
        assertFalse(memoryStore.validateNonce(source, 0), "Old nonce 0 should be invalid");
        assertFalse(memoryStore.validateNonce(source, 50), "Old nonce 50 should be invalid");
        assertFalse(memoryStore.validateNonce(source, 99), "Old nonce 99 should be invalid");

        // Future nonces should be invalid (strict ordering)
        assertFalse(memoryStore.validateNonce(source, 101), "Future nonce 101 should be invalid");
        assertFalse(memoryStore.validateNonce(source, 1000), "Future nonce 1000 should be invalid");
    }

    @Test
    public void testInMemoryMultipleSourcesIndependent() {
        var source1 = DigestAlgorithm.DEFAULT.digest("source1".getBytes());
        var source2 = DigestAlgorithm.DEFAULT.digest("source2".getBytes());

        // Each source should have independent nonce sequence
        assertEquals(0, memoryStore.getAndIncrement(source1));
        assertEquals(0, memoryStore.getAndIncrement(source2));
        assertEquals(1, memoryStore.getAndIncrement(source1));
        assertEquals(1, memoryStore.getAndIncrement(source2));

        // Validation should be independent - each source expects nonce 2 next
        assertTrue(memoryStore.validateNonce(source1, 2), "Source1 should expect nonce 2");
        assertTrue(memoryStore.validateNonce(source2, 2), "Source2 should expect nonce 2");

        // Used nonces should be invalid for each source
        assertFalse(memoryStore.validateNonce(source1, 0), "Source1 nonce 0 used");
        assertFalse(memoryStore.validateNonce(source1, 1), "Source1 nonce 1 used");
        assertFalse(memoryStore.validateNonce(source2, 0), "Source2 nonce 0 used");
        assertFalse(memoryStore.validateNonce(source2, 1), "Source2 nonce 1 used");
    }

    @Test
    public void testFeatureFlagIntegration() {
        // With persistence enabled
        FeatureFlags.NONCE_PERSISTENCE.setEnabled(true);
        try {
            var tracker = NonceTracker.create(tempDir.resolve("feature-flag-test.mv.db").toFile());
            assertTrue(tracker instanceof PersistentNonceStore,
                      "Should use PersistentNonceStore when flag enabled");
            tracker.close();
        } finally {
            FeatureFlags.NONCE_PERSISTENCE.setEnabled(false);
        }

        // With persistence disabled
        var tracker2 = NonceTracker.create(tempDir.resolve("unused.mv.db").toFile());
        assertTrue(tracker2 instanceof InMemoryNonceStore,
                  "Should use InMemoryNonceStore when flag disabled");
    }
}
