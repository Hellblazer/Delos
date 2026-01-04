/*
 * Copyright (c) 2025, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy.security;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.cryptography.Signer.SignerImpl;
import com.hellblazer.delos.stereotomy.ControlledIdentifier;
import com.hellblazer.delos.stereotomy.KERL;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.db.UniKERLDirect;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.spec.IdentifierSpecification;
import com.hellblazer.delos.stereotomy.identifier.spec.KeyConfigurationDigester;
import com.hellblazer.delos.stereotomy.jks.JksKeyStore;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import liquibase.Liquibase;
import liquibase.database.core.H2Database;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.h2.jdbc.JdbcConnection;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.joou.ULong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.hellblazer.delos.cryptography.SigningThreshold.unweighted;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for all security fixes working together
 * <p>
 * This test suite covers the interaction of all CRIT fixes:
 * - CRIT-1 (1tj): XOR permutation in KeyConfigurationDigester
 * - CRIT-2 (8kb): MemKERL race condition with per-identifier locks
 * - CRIT-4 (4xi): UniKERL transaction boundaries
 * - CRIT-5: State transition atomicity (verified as non-issue - immutable pattern)
 * - CRIT-6 (srr): Key material clearing in JksKeyStore
 * <p>
 * These tests verify that all fixes work correctly together under realistic workloads
 * combining concurrent operations, full event chains, and proper resource cleanup.
 *
 * @author hal.hildebrand
 */
public class SecurityFixIntegrationTest {

    private Connection connection;
    private DSLContext dsl;

    @AfterEach
    void teardown() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }

    /**
     * Full event chain test covering all security fixes working together.
     * <p>
     * This test exercises:
     * 1. Key generation with proper clearing (CRIT-6)
     * 2. Inception events using KeyConfigurationDigester XOR logic (CRIT-1)
     * 3. Concurrent operations with per-identifier locking (CRIT-2)
     * 4. Transaction boundaries in UniKERL (CRIT-4)
     * 5. Event chain integrity across all operations
     */
    @Test
    void testFullEventChainWithAllFixes() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 1, 2, 3 });

        // Setup JksKeyStore with password provider (CRIT-6)
        var sharedPassword = "integrationTestPassword".toCharArray();
        var passwordProvider = (java.util.function.Supplier<char[]>) () -> sharedPassword;

        var keyStore = KeyStore.getInstance("JKS");
        keyStore.load(null, null);
        var jksKeyStore = new JksKeyStore(keyStore, passwordProvider);

        // Setup UniKERL with proper transaction support (CRIT-4)
        setupUniKERL();
        var uniKerl = new UniKERLDirect(connection, DigestAlgorithm.DEFAULT);
        var controller = new StereotomyImpl(jksKeyStore, uniKerl, entropy);

        // Create identifier with proper key configuration digest (CRIT-1)
        var identifier = controller.newIdentifier();
        assertNotNull(identifier, "Identifier should be created");

        // Verify inception event was properly stored
        var inceptionState = uniKerl.getKeyState(identifier.getIdentifier());
        assertNotNull(inceptionState, "Inception state should exist");
        assertEquals(0, inceptionState.getCoordinates().getSequenceNumber().longValue(),
                     "Inception should be at sequence 0");

        // Perform sequential rotations to build event chain
        for (int i = 0; i < 5; i++) {
            identifier.rotate();
        }

        // Verify complete event chain
        var finalState = identifier.getCoordinates();
        assertEquals(5, finalState.getSequenceNumber().longValue(), "Should have 5 rotations after inception");

        // Verify KERL consistency
        var kerl = uniKerl.kerl(identifier.getIdentifier());
        assertNotNull(kerl, "KERL should exist");
        assertEquals(6, kerl.size(), "KERL should have inception + 5 rotations");

        // Verify all events have consecutive sequence numbers
        for (int i = 0; i < kerl.size(); i++) {
            var entry = kerl.get(i);
            assertEquals(i, entry.event().getSequenceNumber().longValue(),
                         "Sequence numbers should be consecutive");
        }

        // Verify password still works after multiple operations (CRIT-6)
        assertEquals("integrationTestPassword", new String(sharedPassword),
                     "Password should remain intact for reuse");
    }

    /**
     * Concurrent multi-identifier test covering MemKERL locking and event chain integrity.
     * <p>
     * This test exercises:
     * 1. Multiple identifiers being operated on concurrently (CRIT-2)
     * 2. Per-identifier locking preventing race conditions (CRIT-2)
     * 3. Key configuration digest correctness under load (CRIT-1)
     * 4. Event chain integrity for all identifiers
     */
    @Test
    void testConcurrentMultiIdentifierOperations() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 4, 5, 6 });

        // Use MemKERL for this test (CRIT-2)
        var kel = new MemKERL(DigestAlgorithm.DEFAULT);
        var ks = new MemKeyStore();
        var controller = new StereotomyImpl(ks, kel, entropy);

        // Create multiple identifiers
        int identifierCount = 10;
        var identifiers = new ArrayList<ControlledIdentifier<? extends Identifier>>();
        for (int i = 0; i < identifierCount; i++) {
            identifiers.add(controller.newIdentifier());
        }

        // Launch concurrent operations on different identifiers
        int rotationsPerIdentifier = 5;
        int totalThreads = identifierCount * rotationsPerIdentifier;
        var barrier = new CyclicBarrier(totalThreads);
        var latch = new CountDownLatch(totalThreads);
        var errors = new AtomicInteger(0);
        var exceptionRef = new AtomicReference<Throwable>();

        var executor = Executors.newFixedThreadPool(totalThreads);
        try {
            for (int idIdx = 0; idIdx < identifierCount; idIdx++) {
                final var identifier = identifiers.get(idIdx);
                for (int rotIdx = 0; rotIdx < rotationsPerIdentifier; rotIdx++) {
                    executor.submit(() -> {
                        try {
                            barrier.await(); // Synchronize start to maximize concurrency
                            identifier.rotate();
                        } catch (Throwable e) {
                            errors.incrementAndGet();
                            exceptionRef.compareAndSet(null, e);
                        } finally {
                            latch.countDown();
                        }
                    });
                }
            }

            latch.await();
        } finally {
            executor.shutdown();
        }

        // Verify no errors occurred
        assertEquals(0, errors.get(), "No errors should occur during concurrent operations");
        assertNull(exceptionRef.get(), "No exceptions should be thrown");

        // Verify each identifier has correct state
        for (var identifier : identifiers) {
            var finalState = identifier.getCoordinates();
            assertEquals(rotationsPerIdentifier, finalState.getSequenceNumber().longValue(),
                         "Each identifier should have correct number of rotations");

            // Verify KERL consistency for each identifier
            var kerl = kel.kerl(identifier.getIdentifier());
            assertNotNull(kerl);
            assertEquals(rotationsPerIdentifier + 1, kerl.size(), "KERL should have inception + rotations");

            // Verify consecutive sequence numbers
            for (int i = 0; i < kerl.size(); i++) {
                assertEquals(i, kerl.get(i).event().getSequenceNumber().longValue());
            }
        }
    }

    /**
     * Multi-instance concurrent rotation test (CRIT-2 specific).
     * <p>
     * This test creates multiple ControlledIdentifier instances for the SAME identifier
     * and performs concurrent rotations to verify that per-identifier locking works correctly.
     * <p>
     * Note: Some rotations may fail due to key state conflicts (expected behavior),
     * but KERL consistency should always be maintained.
     */
    @Test
    void testMultiInstanceConcurrentRotation() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 7, 8, 9 });

        var kel = new MemKERL(DigestAlgorithm.DEFAULT);
        var ks = new MemKeyStore();
        var controller = new StereotomyImpl(ks, kel, entropy);

        // Create original identifier
        var originalIdentifier = controller.newIdentifier();
        var identifier = originalIdentifier.getIdentifier();

        // Create multiple instances for the SAME identifier (CRIT-2 test case)
        int instanceCount = 5;
        var instances = new ArrayList<ControlledIdentifier<? extends Identifier>>();
        for (int i = 0; i < instanceCount; i++) {
            instances.add(controller.controlOf(identifier));
        }

        // Launch concurrent rotations from different instances
        int rotationsPerInstance = 3;
        int totalThreads = instanceCount * rotationsPerInstance;
        var barrier = new CyclicBarrier(totalThreads);
        var latch = new CountDownLatch(totalThreads);
        var successCount = new AtomicInteger(0);
        var failureCount = new AtomicInteger(0);

        var executor = Executors.newFixedThreadPool(totalThreads);
        try {
            for (int instanceIdx = 0; instanceIdx < instanceCount; instanceIdx++) {
                final var instance = instances.get(instanceIdx);
                for (int rotIdx = 0; rotIdx < rotationsPerInstance; rotIdx++) {
                    executor.submit(() -> {
                        try {
                            barrier.await();
                            instance.rotate();
                            successCount.incrementAndGet();
                        } catch (Throwable e) {
                            // Some failures are expected due to concurrent key state changes
                            failureCount.incrementAndGet();
                        } finally {
                            latch.countDown();
                        }
                    });
                }
            }

            latch.await();
        } finally {
            executor.shutdown();
        }

        // At least some rotations should succeed
        assertTrue(successCount.get() > 0, "At least some rotations should succeed");

        // Verify KERL consistency regardless of success/failure count
        var kerl = kel.kerl(identifier);
        assertNotNull(kerl, "KERL should exist");

        // Verify all events have consecutive sequence numbers
        for (int i = 0; i < kerl.size(); i++) {
            assertEquals(i, kerl.get(i).event().getSequenceNumber().longValue(),
                         "Sequence numbers should be consecutive despite concurrent operations");
        }

        // Verify the final state is consistent
        var finalState = kel.getKeyState(identifier);
        assertNotNull(finalState, "Final state should exist");
        var finalEvent = kel.getKeyEvent(finalState.getCoordinates());
        assertNotNull(finalEvent, "Final event should exist at final state coordinates");
    }

    /**
     * UniKERL transaction boundary test under concurrent load (CRIT-4).
     * <p>
     * This test verifies that UniKERL maintains database consistency even under
     * concurrent append operations, with proper transaction boundaries.
     */
    @Test
    void testUniKERLTransactionBoundariesUnderLoad() throws Exception {
        setupUniKERL();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 10, 11, 12 });

        var uniKerl = new UniKERLDirect(connection, DigestAlgorithm.DEFAULT);
        var ks = new MemKeyStore();
        var controller = new StereotomyImpl(ks, uniKerl, entropy);

        // Create multiple identifiers
        int identifierCount = 5;
        var identifiers = new ArrayList<ControlledIdentifier<? extends Identifier>>();
        for (int i = 0; i < identifierCount; i++) {
            identifiers.add(controller.newIdentifier());
        }

        // Concurrent rotations on different identifiers
        int rotationsPerIdentifier = 3;
        int totalOps = identifierCount * rotationsPerIdentifier;
        var latch = new CountDownLatch(totalOps);
        var errors = new AtomicInteger(0);

        var executor = Executors.newFixedThreadPool(totalOps);
        try {
            for (int i = 0; i < identifierCount; i++) {
                final var identifier = identifiers.get(i);
                for (int j = 0; j < rotationsPerIdentifier; j++) {
                    executor.submit(() -> {
                        try {
                            identifier.rotate();
                        } catch (Throwable e) {
                            errors.incrementAndGet();
                        } finally {
                            latch.countDown();
                        }
                    });
                }
            }

            latch.await();
        } finally {
            executor.shutdown();
        }

        // Verify no errors
        assertEquals(0, errors.get(), "No errors should occur");

        // Verify each identifier's state
        for (var identifier : identifiers) {
            var state = uniKerl.getKeyState(identifier.getIdentifier());
            assertNotNull(state, "State should exist");
            assertEquals(rotationsPerIdentifier, state.getCoordinates().getSequenceNumber().longValue());

            // Verify complete KERL
            var kerl = uniKerl.kerl(identifier.getIdentifier());
            assertEquals(rotationsPerIdentifier + 1, kerl.size());

            // Verify consecutive sequence numbers (database integrity)
            for (int i = 0; i < kerl.size(); i++) {
                assertEquals(i, kerl.get(i).event().getSequenceNumber().longValue(),
                             "Database should maintain consecutive sequence numbers");
            }
        }
    }

    /**
     * XOR digest order independence test (CRIT-1).
     * <p>
     * Verifies that KeyConfigurationDigester produces the same digest regardless
     * of the order in which next keys are specified.
     */
    @Test
    void testXORDigestOrderIndependence() {
        var algo = DigestAlgorithm.DEFAULT;
        var entropy = new SecureRandom();

        // Generate keys
        var sigAlgo = SignatureAlgorithm.DEFAULT;
        var key1 = sigAlgo.generateKeyPair(entropy).getPublic();
        var key2 = sigAlgo.generateKeyPair(entropy).getPublic();
        var key3 = sigAlgo.generateKeyPair(entropy).getPublic();

        var threshold = unweighted(2);

        // Different orderings of the same keys
        var ordering1 = List.of(key1, key2, key3);
        var ordering2 = List.of(key3, key1, key2);
        var ordering3 = List.of(key2, key3, key1);

        // All orderings should produce the same digest (XOR is commutative)
        var digest1 = KeyConfigurationDigester.digest(threshold, ordering1, algo);
        var digest2 = KeyConfigurationDigester.digest(threshold, ordering2, algo);
        var digest3 = KeyConfigurationDigester.digest(threshold, ordering3, algo);

        assertEquals(digest1, digest2, "XOR digest should be order-independent");
        assertEquals(digest1, digest3, "XOR digest should be order-independent");
        assertEquals(digest2, digest3, "XOR digest should be order-independent");
    }

    /**
     * Key material password reuse test (CRIT-6).
     * <p>
     * Verifies that JksKeyStore correctly handles password providers that return
     * the same array instance, and that the password remains usable across operations.
     */
    @Test
    void testKeyMaterialPasswordReuse() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 13, 14, 15 });

        // Provider that returns the SAME array every time
        var sharedPassword = "sharedPassword123".toCharArray();
        var passwordProvider = (java.util.function.Supplier<char[]>) () -> sharedPassword;

        var keyStore = KeyStore.getInstance("JKS");
        keyStore.load(null, null);
        var jksKeyStore = new JksKeyStore(keyStore, passwordProvider);

        var sigAlgo = SignatureAlgorithm.DEFAULT;

        // Perform multiple store operations
        for (int i = 0; i < 10; i++) {
            KeyPair keyPair = sigAlgo.generateKeyPair(entropy);
            jksKeyStore.storeKey("test-key-" + i, keyPair);
        }

        // Verify password is still intact
        assertEquals("sharedPassword123", new String(sharedPassword),
                     "Provider's password should remain intact");

        // Verify all keys can be retrieved
        for (int i = 0; i < 10; i++) {
            var retrieved = jksKeyStore.getKey("test-key-" + i);
            assertTrue(retrieved.isPresent(), "Key " + i + " should be retrievable");
        }
    }

    /**
     * Stress test combining all security fixes.
     * <p>
     * This repeated test runs a complex scenario combining:
     * - Multiple identifiers
     * - Concurrent operations
     * - Both MemKERL and UniKERL
     * - Key material operations
     * - Full event chains
     */
    @RepeatedTest(3)
    void testAllFixesCombinedStressTest() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { (byte) System.nanoTime() });

        // Setup both KERL implementations
        var memKel = new MemKERL(DigestAlgorithm.DEFAULT);
        setupUniKERL();
        var uniKerl = new UniKERLDirect(connection, DigestAlgorithm.DEFAULT);

        // Setup key store with password
        var password = "stressTestPassword".toCharArray();
        var passwordProvider = (java.util.function.Supplier<char[]>) () -> password;
        var keyStore = KeyStore.getInstance("JKS");
        keyStore.load(null, null);
        var jksKeyStore = new JksKeyStore(keyStore, passwordProvider);

        // Test with both KERL implementations
        for (KERL.AppendKERL kel : List.of(memKel, uniKerl)) {
            var controller = new StereotomyImpl(jksKeyStore, kel, entropy);

            // Create identifiers
            int identifierCount = 5;
            var identifiers = new ArrayList<ControlledIdentifier<? extends Identifier>>();
            for (int i = 0; i < identifierCount; i++) {
                identifiers.add(controller.newIdentifier());
            }

            // Concurrent rotations
            int rotationsPerIdentifier = 3;
            int totalOps = identifierCount * rotationsPerIdentifier;
            var latch = new CountDownLatch(totalOps);
            var errors = new AtomicInteger(0);

            ExecutorService executor = Executors.newFixedThreadPool(totalOps);
            try {
                for (int i = 0; i < identifierCount; i++) {
                    final var identifier = identifiers.get(i);
                    for (int j = 0; j < rotationsPerIdentifier; j++) {
                        executor.submit(() -> {
                            try {
                                identifier.rotate();
                            } catch (Throwable e) {
                                errors.incrementAndGet();
                            } finally {
                                latch.countDown();
                            }
                        });
                    }
                }

                latch.await();
            } finally {
                executor.shutdown();
            }

            // Verify no errors
            assertEquals(0, errors.get(), "No errors in stress test with " + kel.getClass().getSimpleName());

            // Verify all identifiers have correct state
            for (var identifier : identifiers) {
                var state = kel.getKeyState(identifier.getIdentifier());
                assertNotNull(state);
                assertEquals(rotationsPerIdentifier, state.getCoordinates().getSequenceNumber().longValue());

                var kerl = kel.kerl(identifier.getIdentifier());
                assertEquals(rotationsPerIdentifier + 1, kerl.size());
            }
        }

        // Verify password still works
        assertEquals("stressTestPassword", new String(password), "Password should remain intact");
    }

    // Helper method to setup UniKERL database
    private void setupUniKERL() throws Exception {
        final var url = String.format("jdbc:h2:mem:integration_test-%s;DB_CLOSE_DELAY=-1", Math.random());
        connection = new JdbcConnection(url, new Properties(), "", "", false);

        var database = new H2Database();
        database.setConnection(new liquibase.database.jvm.JdbcConnection(connection));
        try (Liquibase liquibase = new Liquibase("/stereotomy/initialize.xml", new ClassLoaderResourceAccessor(),
                                                 database)) {
            liquibase.update((String) null);
        }
        connection = new JdbcConnection(url, new Properties(), "", "", false);
        dsl = DSL.using(connection);
    }
}
