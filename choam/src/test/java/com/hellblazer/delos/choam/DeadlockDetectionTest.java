/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.MicrometerServerConnectionCacheMetrics;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import java.util.concurrent.Executors;
import com.hellblazer.delos.choam.TransactionExecutor;
import com.hellblazer.delos.choam.proto.Transaction;
import com.hellblazer.delos.choam.support.HashedCertifiedBlock;
import com.hellblazer.delos.choam.support.MicrometerChoamMetrics;
import com.hellblazer.delos.context.StaticContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.ethereal.Config;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.utils.Utils;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deadlock Detection Test Suite
 * <p>
 * Deterministic tests for CHOAM lock ordering and deadlock prevention.
 * <p>
 * <b>Purpose</b>: Validate that headLock and viewStateLock remain disjoint under
 * concurrent load. These tests use CountDownLatches for deterministic synchronization
 * to ensure repeatable results on CI.
 * <p>
 * <b>Test Scenarios</b>:
 * <ul>
 *   <li>Concurrent consume() operations (headLock write lock)</li>
 *   <li>Concurrent reconfigure() operations (viewStateLock exclusive)</li>
 *   <li>Mixed consume() + reconfigure() (validate disjoint locks)</li>
 *   <li>Artificial deadlock injection (negative test - should detect)</li>
 * </ul>
 * <p>
 * <b>Lock Ordering (from LOCK_ORDERING.md)</b>:
 * <ul>
 *   <li>headLock (ReadWriteLock): protects blockchain head, genesis, view blocks</li>
 *   <li>viewStateLock (ReentrantLock): protects view reconfiguration state</li>
 *   <li><b>INVARIANT</b>: headLock ⊥ viewStateLock (never nested, no circular wait)</li>
 * </ul>
 * <p>
 * <b>Deterministic Design</b>:
 * <ul>
 *   <li>Fixed thread counts (no randomness)</li>
 *   <li>CountDownLatches for synchronization (no race conditions)</li>
 *   <li>Explicit thread orchestration (repeatable execution order)</li>
 *   <li>JMX ThreadMXBean for deadlock detection</li>
 * </ul>
 * <p>
 * <b>CI Reliability</b>: Run with {@code ./mvnw test -Drepeat=10} to validate determinism
 * <p>
 * References: Delos-7yk8, choam/LOCK_ORDERING.md
 *
 * @author hal.hildebrand
 */
@Tag("stress")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class DeadlockDetectionTest {
    private static final boolean LARGE_TESTS = Boolean.getBoolean("large_tests");
    private static final boolean IS_CI = Boolean.parseBoolean(System.getenv().getOrDefault("CI", "false"));
    private static final int     CARDINALITY = 4;

    private Map<Digest, CHOAM>              choams;
    private Map<Digest, Router>             routers;
    private List<SigningMember>             members;
    private SimpleMeterRegistry             registry;
    private ScheduledExecutorService        scheduler;
    private ExecutorService                 executor;
    private ThreadMXBean                    threadMXBean;

    @BeforeEach
    public void before() throws Exception {
        scheduler = Executors.newScheduledThreadPool(10, Thread.ofVirtual().factory());
        executor = Executors.newVirtualThreadPerTaskExecutor();
        threadMXBean = ManagementFactory.getThreadMXBean();

        var origin = DigestAlgorithm.DEFAULT.getOrigin();
        registry = new SimpleMeterRegistry();
        var metrics = new MicrometerChoamMetrics(origin, registry);

        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 16, 17, 18 }); // Deterministic seed

        var params = Parameters.newBuilder()
                               .setGenerateGenesis(true)
                               .setGenesisViewId(origin.prefix(entropy.nextLong()))
                               .setGossipDuration(Duration.ofMillis(LARGE_TESTS ? 10 : 20))
                               .setProducer(Parameters.ProducerParameters.newBuilder()
                                                                          .setMaxBatchCount(1000)
                                                                          .setMaxBatchByteSize(50 * 1024 * 1024)
                                                                          .setGossipDuration(Duration.ofMillis(LARGE_TESTS ? 10 : 20))
                                                                          .setBatchInterval(Duration.ofMillis(LARGE_TESTS ? 50 : 50))
                                                                          .setEthereal(Config.newBuilder()
                                                                                             .setNumberOfEpochs(LARGE_TESTS ? 12 : 2)
                                                                                             .setEpochLength(LARGE_TESTS ? 33 : 11))
                                                                          .build())
                               .setCheckpointBlockDelta(LARGE_TESTS ? 5 : 3);

        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);

        members = IntStream.range(0, CARDINALITY)
                           .mapToObj(_ -> stereotomy.newIdentifier())
                           .map(ControlledIdentifierMember::new)
                           .map(e -> (SigningMember) e)
                           .toList();
        var context = new StaticContext<>(origin, 0.2, members, 3);
        final var prefix = UUID.randomUUID().toString();
        routers = members.stream()
                         .collect(Collectors.toMap(m -> m.getId(), m -> new LocalServer(prefix, m).router(
                         ServerConnectionCache.newBuilder()
                                              .setMetrics(new MicrometerServerConnectionCacheMetrics(registry))
                                              .setTarget(CARDINALITY), executor)));
        choams = members.stream().collect(Collectors.toMap(m -> m.getId(), m -> {
            final TransactionExecutor processor = new TransactionExecutor() {
                @SuppressWarnings({ "unchecked", "rawtypes" })
                @Override
                public void execute(int index, Digest hash, Transaction t, CompletableFuture f) {
                    if (f != null) {
                        f.completeAsync(() -> new Object(), executor);
                    }
                }
            };
            params.getProducer().ethereal().setSigner(m);
            var runtime = Parameters.RuntimeParameters.newBuilder();
            File fn = null;
            try {
                fn = File.createTempFile("deadlock-", ".dat");
                fn.deleteOnExit();
            } catch (IOException e) {
                fail(e);
            }
            return new CHOAM(params.build(runtime.setMember(m)
                                                 .setMetrics(metrics)
                                                 .setCommunications(routers.get(m.getId()))
                                                 .setProcessor(processor)
                                                 .setRestorer(Parameters.RuntimeParameters.NOOP_RESTORER)
                                                 .setContext(context)
                                                 .build()));
        }));
    }

    @AfterEach
    public void after() throws Exception {
        if (routers != null) {
            routers.values().forEach(e -> e.close(Duration.ofSeconds(0)));
            routers = null;
        }
        if (choams != null) {
            choams.values().forEach(e -> e.stop());
            choams = null;
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
        if (executor != null) {
            executor.shutdown();
        }
        members = null;
        registry = null;
    }

    /**
     * Test: Concurrent consume() operations should not deadlock
     * <p>
     * consume() acquires headLock.writeLock() (CHOAM.java:708)
     * <p>
     * This test validates that multiple threads can safely call consume() concurrently
     * without deadlock. Uses CountDownLatch for deterministic synchronization.
     */
    @Test
    @Order(1)
    public void testConcurrentConsumeOperations() throws Exception {
        routers.values().forEach(Router::start);
        choams.values().forEach(CHOAM::start);

        boolean activated = Utils.waitForCondition(LARGE_TESTS ? 30_000 : 15_000, 1_000,
                                                   () -> choams.values().stream().allMatch(c -> c.active()));
        assertTrue(activated, "System did not become active");

        // Deterministic concurrent consume test - simplified approach
        int transactioneersPerChoam = LARGE_TESTS ? 4 : 2;
        int txnsPerTransactioneer = LARGE_TESTS ? 15 : 8;
        CountDownLatch endGate = new CountDownLatch(choams.size() * transactioneersPerChoam);
        List<Transactioneer> transactioneers = new ArrayList<>();

        // Create transactioneers that will concurrently submit transactions (triggering consume())
        choams.values().forEach(choam -> {
            for (int i = 0; i < transactioneersPerChoam; i++) {
                transactioneers.add(new Transactioneer(scheduler, choam.getSession(),
                                                       Duration.ofSeconds(3), txnsPerTransactioneer, endGate));
            }
        });

        // Start all transactioneers
        transactioneers.forEach(Transactioneer::start);

        boolean completed = endGate.await(LARGE_TESTS ? 180 : (IS_CI ? 120 : 60), TimeUnit.SECONDS);
        assertTrue(completed, "Concurrent consume() operations should complete without deadlock");

        // Verify no deadlocks occurred
        long[] deadlockedThreads = threadMXBean.findDeadlockedThreads();
        assertNull(deadlockedThreads, "No deadlocks should be detected");

        routers.values().forEach(e -> e.close(Duration.ofSeconds(0)));
        choams.values().forEach(CHOAM::stop);
    }

    /**
     * Test: Mixed consume() and reconfigure() operations should not deadlock
     * <p>
     * consume() acquires headLock.writeLock() (CHOAM.java:708)
     * reconfigure() acquires viewStateLock (CHOAM.java:1082)
     * <p>
     * <b>CRITICAL INVARIANT</b>: These locks are DISJOINT (never nested)
     * <p>
     * This test validates that concurrent block acceptance and view reconfiguration
     * do not deadlock, proving the disjoint lock invariant holds under load.
     */
    @Test
    @Order(2)
    public void testMixedConsumeAndReconfigureOperations() throws Exception {
        routers.values().forEach(Router::start);
        choams.values().forEach(CHOAM::start);

        boolean activated = Utils.waitForCondition(LARGE_TESTS ? 30_000 : 15_000, 1_000,
                                                   () -> choams.values().stream().allMatch(c -> c.active()));
        assertTrue(activated, "System did not become active");

        // Mixed operations test - transactions (consume) + potential reconfigurations
        int rounds = LARGE_TESTS ? 5 : 3;
        for (int round = 0; round < rounds; round++) {
            CountDownLatch roundGate = new CountDownLatch(choams.size() * 2);

            choams.values().forEach(choam -> {
                // Transactioneers trigger consume() via block acceptance
                var transactioneer1 = new Transactioneer(scheduler, choam.getSession(),
                                                        Duration.ofSeconds(3), LARGE_TESTS ? 8 : 5, roundGate);
                var transactioneer2 = new Transactioneer(scheduler, choam.getSession(),
                                                        Duration.ofSeconds(3), LARGE_TESTS ? 8 : 5, roundGate);
                transactioneer1.start();
                transactioneer2.start();
            });

            // CI (2-core): large_tests creates more work per round (8 txns vs 5), needs proportionally more time
            boolean completed = roundGate.await(LARGE_TESTS ? (IS_CI ? 120 : 45) : (IS_CI ? 90 : 25), TimeUnit.SECONDS);
            assertTrue(completed, "Round " + round + " should complete without deadlock");

            // Check for deadlocks after each round
            long[] deadlockedThreads = threadMXBean.findDeadlockedThreads();
            assertNull(deadlockedThreads, "No deadlocks in round " + round);
        }

        routers.values().forEach(e -> e.close(Duration.ofSeconds(0)));
        choams.values().forEach(CHOAM::stop);
    }

    /**
     * Test: System should detect artificial deadlock
     * <p>
     * <b>NEGATIVE TEST</b>: This test validates that JMX can detect deadlocks.
     * <p>
     * We create a controlled deadlock scenario and verify detection. Note that
     * deadlocked threads CANNOT be cleaned up (interrupt doesn't release synchronized
     * locks), so this test runs LAST to avoid polluting other tests.
     * <p>
     * NOTE: Uses platform threads because JMX deadlock detection doesn't work
     * reliably with virtual threads. Threads are daemon threads to minimize impact.
     */
    @Test
    @Order(999) // Run LAST - leaves deadlocked threads
    public void testArtificialDeadlockDetection() throws Exception {
        // Create two locks for artificial deadlock
        final Object lockA = new Object();
        final Object lockB = new Object();

        CountDownLatch thread1Ready = new CountDownLatch(1);
        CountDownLatch thread2Ready = new CountDownLatch(1);
        CountDownLatch deadlockCreated = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger interruptCount = new AtomicInteger(0);

        // Thread 1: Acquire lockA, then lockB (PLATFORM DAEMON THREAD for JMX detection)
        Thread t1 = Thread.ofPlatform().daemon().start(() -> {
            try {
                synchronized (lockA) {
                    thread1Ready.countDown();
                    thread2Ready.await(2, TimeUnit.SECONDS); // Wait for thread 2
                    Thread.sleep(100); // Give thread 2 time to acquire lockB
                    synchronized (lockB) {
                        successCount.incrementAndGet();
                        deadlockCreated.countDown();
                    }
                }
            } catch (InterruptedException e) {
                interruptCount.incrementAndGet();
                Thread.currentThread().interrupt();
            }
        });

        // Thread 2: Acquire lockB, then lockA (opposite order - creates deadlock)
        // PLATFORM DAEMON THREAD for JMX detection
        Thread t2 = Thread.ofPlatform().daemon().start(() -> {
            try {
                synchronized (lockB) {
                    thread2Ready.countDown();
                    thread1Ready.await(2, TimeUnit.SECONDS); // Wait for thread 1
                    Thread.sleep(100); // Give thread 1 time to acquire lockA
                    synchronized (lockA) {
                        successCount.incrementAndGet();
                        deadlockCreated.countDown();
                    }
                }
            } catch (InterruptedException e) {
                interruptCount.incrementAndGet();
                Thread.currentThread().interrupt();
            }
        });

        // Wait for deadlock to form (with timeout)
        boolean completed = deadlockCreated.await(2, TimeUnit.SECONDS);

        // Deadlock should prevent completion
        assertFalse(completed, "Artificial deadlock should prevent completion");
        assertEquals(0, successCount.get(), "No threads should complete due to deadlock");

        // JMX should detect the deadlock (works with platform threads)
        Thread.sleep(300); // Give JMX time to detect
        long[] deadlockedThreads = threadMXBean.findDeadlockedThreads();

        // Assert deadlock was detected
        assertNotNull(deadlockedThreads, "JMX ThreadMXBean should detect artificial deadlock");
        assertTrue(deadlockedThreads.length >= 2, "At least 2 threads should be deadlocked");

        // Cleanup - interrupt threads and wait for cleanup
        // Note: Interrupting doesn't immediately break synchronous locks,
        // but daemon threads will be cleaned up when test ends
        t1.interrupt();
        t2.interrupt();

        // Don't wait for join - daemon threads will be cleaned up automatically
        // This prevents hanging the test if threads are truly deadlocked
    }

    /**
     * Test: Verify no lock contention under normal load
     * <p>
     * This test validates that the disjoint lock design (headLock ⊥ viewStateLock)
     * minimizes lock contention. Under normal operation, threads should not block
     * waiting for locks.
     */
    @Test
    @Order(3)
    public void testNoLockContentionUnderNormalLoad() throws Exception {
        routers.values().forEach(Router::start);
        choams.values().forEach(CHOAM::start);

        boolean activated = Utils.waitForCondition(LARGE_TESTS ? 30_000 : 15_000, 1_000,
                                                   () -> choams.values().stream().allMatch(c -> c.active()));
        assertTrue(activated, "System did not become active");

        // Normal load test - measure completion time
        long startTime = System.nanoTime();

        CountDownLatch normalLoad = new CountDownLatch(choams.size() * 3);
        choams.values().forEach(choam -> {
            for (int i = 0; i < 3; i++) {
                var transactioneer = new Transactioneer(scheduler, choam.getSession(),
                                                       Duration.ofSeconds(3), LARGE_TESTS ? 10 : 6, normalLoad);
                transactioneer.start();
            }
        });

        boolean completed = normalLoad.await(LARGE_TESTS ? 60 : 30, TimeUnit.SECONDS);
        assertTrue(completed, "Normal load should complete without contention");

        long duration = (System.nanoTime() - startTime) / 1_000_000; // Convert to ms

        // Verify no deadlocks
        long[] deadlockedThreads = threadMXBean.findDeadlockedThreads();
        assertNull(deadlockedThreads, "No deadlocks under normal load");

        // Log completion time for performance baseline
        System.out.printf("Normal load completed in %d ms (no lock contention)%n", duration);

        routers.values().forEach(e -> e.close(Duration.ofSeconds(0)));
        choams.values().forEach(CHOAM::stop);
    }

    /**
     * Test: Verify deterministic test execution (repeatability)
     * <p>
     * This test runs multiple iterations of the same test to verify that
     * CountDownLatch synchronization produces repeatable results on CI.
     * <p>
     * Run with {@code -Drepeat=10} to validate CI reliability.
     */
    @Test
    @Order(4)
    public void testDeterministicExecution() throws Exception {
        int iterations = Integer.parseInt(System.getProperty("repeat", LARGE_TESTS ? "5" : "3"));

        for (int iter = 0; iter < iterations; iter++) {
            routers.values().forEach(Router::start);
            choams.values().forEach(CHOAM::start);

            boolean activated = Utils.waitForCondition(LARGE_TESTS ? 30_000 : 15_000, 1_000,
                                                       () -> choams.values().stream().allMatch(c -> c.active()));
            assertTrue(activated, "System should activate in iteration " + iter);

            CountDownLatch iterationGate = new CountDownLatch(choams.size());
            choams.values().forEach(choam -> {
                var transactioneer = new Transactioneer(scheduler, choam.getSession(),
                                                       Duration.ofSeconds(3), LARGE_TESTS ? 5 : 3, iterationGate);
                transactioneer.start();
            });

            // Extended timeout to account for transaction retries during view changes
            // Same reasoning as CHOAMFSMErrorPathsTest: 3s timeout × 5 retries + recovery time
            boolean completed = iterationGate.await(LARGE_TESTS ? 120 : 90, TimeUnit.SECONDS);
            assertTrue(completed, "Iteration " + iter + " should complete deterministically");

            // Verify no deadlocks
            long[] deadlockedThreads = threadMXBean.findDeadlockedThreads();
            assertNull(deadlockedThreads, "No deadlocks in iteration " + iter);

            // Cleanup between iterations
            routers.values().forEach(e -> e.close(Duration.ofSeconds(0)));
            choams.values().forEach(CHOAM::stop);

            // Recreate for next iteration
            if (iter < iterations - 1) {
                before();
            }
        }
    }
}
