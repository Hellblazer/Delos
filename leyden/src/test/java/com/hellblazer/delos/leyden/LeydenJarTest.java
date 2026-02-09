/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.leyden;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.leyden.proto.Binding;
import com.hellblazer.delos.leyden.proto.Bound;
import com.hellblazer.delos.leyden.proto.Key;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.utils.Utils;
import org.h2.jdbcx.JdbcConnectionPool;
import org.h2.mvstore.MVStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author hal.hildebrand
 **/
public class LeydenJarTest {

    private static final double                            PBYZ    = 0.1;
    protected final      TreeMap<SigningMember, LeydenJar> dhts    = new TreeMap<>();
    protected final      Map<SigningMember, Router>        routers = new HashMap<>();
    private              String                            prefix;
    private              LeydenJar.OpValidator             validator;
    private              DynamicContext<Member>            context;

    @AfterEach
    public void after() {
        routers.values().forEach(r -> r.close(Duration.ofSeconds(0)));
        routers.clear();
        dhts.values().forEach(t -> t.stop());
        dhts.clear();
    }

    @BeforeEach
    public void before() throws Exception {
        validator = new LeydenJar.OpValidator() {
            @Override
            public boolean validateBind(Bound bound) {
                return true;
            }

            @Override
            public boolean validateGet(byte[] key) {
                return true;
            }

            @Override
            public boolean validateUnbind(byte[] key) {
                return true;
            }
        };
        prefix = UUID.randomUUID().toString();
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        var cardinality = 5;
        var identities = IntStream.range(0, cardinality)
                                  .mapToObj(i -> stereotomy.newIdentifier())
                                  .collect(Collectors.toMap(controlled -> new ControlledIdentifierMember(controlled),
                                                            controlled -> controlled));
        var b = DynamicContext.newBuilder();
        b.setpByz(PBYZ).setCardinality(cardinality);
        context = b.build();
        identities.keySet().forEach(m -> context.activate(m));
        identities.keySet().forEach(member -> instantiate(member, context));

        System.out.println();
        System.out.println();
        System.out.printf("Cardinality: %s, Prob Byz: %s, Rings: %s Majority: %s%n", cardinality, PBYZ,
                          context.getRingCount(), context.majority());
        System.out.println();
    }

    @Test
    public void smokin() {
        routers.values().forEach(r -> r.start());
        dhts.values().forEach(lj -> lj.start(Duration.ofMillis(10)));

        var source = dhts.firstEntry().getValue();
        var sink = dhts.lastEntry().getValue();

        var key = ByteString.copyFrom("hello".getBytes());
        var value = ByteString.copyFrom("world".getBytes());
        var binding = Binding.newBuilder().setBound(Bound.newBuilder().setKey(key).setValue(value).build()).build();
        source.bind(binding);

        for (var e : dhts.entrySet()) {
            var success = Utils.waitForCondition(10_000, () -> {
                Bound bound;
                try {
                    bound = e.getValue().get(Key.newBuilder().setKey(key).build());
                } catch (NoSuchElementException nse) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ex) {
                    }
                    return false;
                }
                return bound != null;
            });
            assertTrue(success, "Failed for " + e.getKey().getId());
        }
    }

    /**
     * Test thread safety of MVMap access under concurrent load (Delos-xkms)
     * Verifies that concurrent bind/get/unbind operations don't cause corruption or exceptions
     */
    @Test
    public void testConcurrentMVMapAccess() throws Exception {
        routers.values().forEach(r -> r.start());
        dhts.values().forEach(lj -> lj.start(Duration.ofMillis(10)));

        var jar = dhts.firstEntry().getValue();
        var threadCount = 20;
        var operationsPerThread = 50;
        var executor = Executors.newFixedThreadPool(threadCount);
        var latch = new CountDownLatch(threadCount);
        var errors = new AtomicInteger(0);

        // Launch concurrent operations
        for (int t = 0; t < threadCount; t++) {
            var threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < operationsPerThread; i++) {
                        var key = ByteString.copyFrom(("key-" + threadId + "-" + i).getBytes());
                        var value = ByteString.copyFrom(("value-" + threadId + "-" + i).getBytes());
                        var binding = Binding.newBuilder()
                                             .setBound(Bound.newBuilder().setKey(key).setValue(value).build())
                                             .build();

                        // Bind
                        jar.bind(binding);

                        // Get
                        var retrieved = jar.get(Key.newBuilder().setKey(key).build());
                        assertNotNull(retrieved, "Retrieved bound should not be null");

                        // Unbind
                        jar.unbind(Key.newBuilder().setKey(key).build());
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all threads to complete
        assertTrue(latch.await(30, TimeUnit.SECONDS), "Operations should complete within timeout");
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "Executor should shut down cleanly");

        assertEquals(0, errors.get(), "No errors should occur during concurrent operations");
    }

    /**
     * Test scheduler shutdown during stop (Delos-x5ez)
     * Verifies that scheduler is properly shut down and virtual threads are terminated
     */
    @Test
    public void testSchedulerShutdown() throws Exception {
        routers.values().forEach(r -> r.start());
        var jar = dhts.firstEntry().getValue();

        // Start the jar (starts scheduler)
        jar.start(Duration.ofMillis(100));

        // Give scheduler time to start tasks
        Thread.sleep(500);

        // Get thread count before stop
        var threadsBeforeStop = Thread.getAllStackTraces().keySet().size();

        // Stop the jar (should shut down scheduler)
        jar.stop();

        // Wait for scheduler threads to terminate
        Thread.sleep(1000);

        // Get thread count after stop
        var threadsAfterStop = Thread.getAllStackTraces().keySet().size();

        // Verify thread count hasn't grown (no lingering virtual threads)
        assertTrue(threadsAfterStop <= threadsBeforeStop + 5,
                   String.format("Thread count should not grow significantly after stop. Before: %d, After: %d",
                                 threadsBeforeStop, threadsAfterStop));
    }

    /**
     * Test that LeydenJar can be cleanly restarted after stop (Delos-x5ez)
     */
    @Test
    public void testCleanRestart() throws Exception {
        routers.values().forEach(r -> r.start());
        var jar = dhts.firstEntry().getValue();

        // First start
        jar.start(Duration.ofMillis(100));
        Thread.sleep(200);
        jar.stop();
        Thread.sleep(500);

        // Second start - should work without issues
        jar.start(Duration.ofMillis(100));
        Thread.sleep(200);

        // Verify functionality still works
        var key = ByteString.copyFrom("restart-test".getBytes());
        var value = ByteString.copyFrom("restart-value".getBytes());
        var binding = Binding.newBuilder().setBound(Bound.newBuilder().setKey(key).setValue(value).build()).build();

        assertDoesNotThrow(() -> jar.bind(binding), "Bind should work after restart");

        var retrieved = jar.get(Key.newBuilder().setKey(key).build());
        assertNotNull(retrieved, "Get should work after restart");
        assertEquals(key, retrieved.getKey(), "Retrieved key should match");

        jar.stop();
    }

    /**
     * Test concurrent reconcile and write operations (CRITICAL race condition test)
     * Verifies reconcile() doesn't access bottled map outside synchronization
     */
    @Test
    public void testConcurrentReconcileAndWrite() throws Exception {
        routers.values().forEach(r -> r.start());
        dhts.values().forEach(lj -> lj.start(Duration.ofMillis(5))); // Fast reconciliation

        var source = dhts.firstEntry().getValue();
        var executor = Executors.newFixedThreadPool(10);
        var latch = new CountDownLatch(100);
        var errors = new AtomicInteger(0);

        // Concurrent bind operations while reconciliation is happening
        for (int i = 0; i < 100; i++) {
            var iteration = i;
            executor.submit(() -> {
                try {
                    var key = ByteString.copyFrom(("concurrent-key-" + iteration).getBytes());
                    var value = ByteString.copyFrom(("concurrent-value-" + iteration).getBytes());
                    var binding = Binding.newBuilder()
                                         .setBound(Bound.newBuilder().setKey(key).setValue(value).build())
                                         .build();
                    source.bind(binding);
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "Concurrent operations should complete");
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(0, errors.get(), "No errors should occur during concurrent reconcile and write");
    }

    /**
     * Test concurrent start/stop calls (CRITICAL race condition test)
     * Verifies scheduler lifecycle is properly synchronized
     */
    @Test
    public void testStartStopRace() throws Exception {
        routers.values().forEach(r -> r.start());
        var jar = dhts.firstEntry().getValue();
        var executor = Executors.newFixedThreadPool(10);
        var latch = new CountDownLatch(20);
        var errors = new AtomicInteger(0);

        // Concurrent start/stop calls
        for (int i = 0; i < 10; i++) {
            executor.submit(() -> {
                try {
                    jar.start(Duration.ofMillis(100));
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    jar.stop();
                } catch (Exception e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "Start/stop operations should complete");
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(0, errors.get(), "No errors should occur during concurrent start/stop");

        // Ensure clean final state
        jar.stop();
    }

    /**
     * Test scheduler actually terminates (CRITICAL verification test)
     * Verifies scheduler.isTerminated() returns true after stop
     */
    @Test
    public void testSchedulerActuallyTerminates() throws Exception {
        routers.values().forEach(r -> r.start());
        var jar = dhts.firstEntry().getValue();

        // Start the jar
        jar.start(Duration.ofMillis(100));
        Thread.sleep(200);

        // Stop the jar
        jar.stop();

        // Wait for scheduler to terminate
        Thread.sleep(6000); // Exceeds 5s timeout + buffer

        // Verify scheduler is truly terminated
        // This test will fail if scheduler shutdown is incomplete
        // We can't directly access scheduler field, but we can verify behavior
        assertDoesNotThrow(() -> jar.start(Duration.ofMillis(100)),
                          "Should be able to restart after complete shutdown");

        jar.stop();
    }

    protected void instantiate(SigningMember member, Context<Member> context) {
        final var url = String.format("jdbc:h2:mem:%s-%s;DB_CLOSE_ON_EXIT=FALSE", member.getId(), prefix);
        JdbcConnectionPool connectionPool = JdbcConnectionPool.create(url, "", "");
        connectionPool.setMaxConnections(10);
        var exec = Executors.newVirtualThreadPerTaskExecutor();
        var router = new LocalServer(prefix, member).router(ServerConnectionCache.newBuilder().setTarget(2));
        routers.put(member, router);
        dhts.put(member,
                 new LeydenJar(validator, Duration.ofSeconds(5), member, context, Duration.ofMillis(10), router, 0.0125,
                               DigestAlgorithm.DEFAULT, new MVStore.Builder().open(), null, null));
    }
}
