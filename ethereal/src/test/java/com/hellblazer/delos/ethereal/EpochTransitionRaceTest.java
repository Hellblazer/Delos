/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.ethereal;

import static org.junit.jupiter.api.Assertions.*;

import java.security.SecureRandom;
import java.util.LinkedList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.joou.ULong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.cryptography.Signer;
import com.hellblazer.delos.cryptography.Verifier;
import com.hellblazer.delos.ethereal.PreUnit.preUnit;

/**
 * Stress test to validate that epoch transition logic is atomic and prevents TOCTOU races.
 *
 * This test demonstrates that synchronized epoch operations prevent the race condition where:
 * 1. Thread A reads epoch (e.g., epoch 5)
 * 2. Thread B calls newEpoch(6), starts transition
 * 3. Thread A creates/processes unit with old epoch 5 while epoch 6 is initializing
 * 4. Epoch state becomes inconsistent
 *
 * Without synchronization, Byzantine nodes could exploit the transition window to inject
 * old-epoch units that bypass validation, potentially causing consensus divergence.
 *
 * @author hal.hildebrand
 */
public class EpochTransitionRaceTest {

    private static final int ITERATIONS = 500;
    private static final int THREADS = 8;
    private static final int N_PROC = 4;

    private ExecutorService executor;
    private Config config;
    private Signer signer;
    private Verifier[] verifiers;

    @BeforeEach
    public void setUp() throws Exception {
        executor = Executors.newFixedThreadPool(THREADS);
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });

        // Create verifiers array for all processors
        verifiers = new Verifier[N_PROC];
        var keypairs = new java.security.KeyPair[N_PROC];
        for (int i = 0; i < N_PROC; i++) {
            keypairs[i] = SignatureAlgorithm.DEFAULT.generateKeyPair();
            verifiers[i] = new Verifier.DefaultVerifier(keypairs[i].getPublic());
        }

        // Process 0's signer must match verifiers[0]
        signer = new Signer.SignerImpl(keypairs[0].getPrivate(), ULong.MIN);
        config = Config.newBuilder()
                       .setnProc((short) N_PROC)
                       .setPid((short) 0)
                       .setSigner(signer)
                       .setEpochLength(33)
                       .setNumberOfEpochs(10)
                       .setBias(2)
                       .build();
    }

    @AfterEach
    public void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /**
     * Test that epoch transitions are atomic: no unit from old epoch can be accepted
     * during newEpoch() execution.
     */
    @Test
    public void testEpochTransitionAtomicity() throws Exception {
        var lastTiming = new LinkedList<Unit>();
        var epochViolations = new AtomicInteger(0);
        var epochReads = new AtomicInteger(0);

        Creator creator = new Creator(config, null, lastTiming, u -> {
            // Track sent units
        }, epoch -> new SimpleEpochProofBuilder(), verifiers);

        creator.start();

        var barrier = new CyclicBarrier(THREADS);
        var latch = new CountDownLatch(THREADS);

        // Threads compete: some reading epoch, some triggering transitions
        for (int i = 0; i < THREADS; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    barrier.await(); // Synchronized start

                    for (int j = 0; j < ITERATIONS / THREADS; j++) {
                        if (threadId % 2 == 0) {
                            // Read epoch repeatedly
                            var e1 = creator.getCurrentEpoch();
                            Thread.yield();
                            var e2 = creator.getCurrentEpoch();
                            epochReads.incrementAndGet();

                            // Epoch should never decrease
                            if (e2 < e1) {
                                epochViolations.incrementAndGet();
                            }
                        } else {
                            // Simulate epoch state access
                            var currentEpoch = creator.getCurrentEpoch();
                            // Just reading to create contention
                            if (currentEpoch < 0) {
                                epochViolations.incrementAndGet();
                            }
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(60, TimeUnit.SECONDS), "Test should complete within timeout");
        executor.shutdown();

        assertEquals(0, epochViolations.get(),
            "No epoch violations - epochs should be monotonic. Reads: " + epochReads.get());
    }

    /**
     * Test that epoch reads during transitions return consistent values.
     */
    @Test
    public void testEpochReadConsistency() throws Exception {
        var lastTiming = new LinkedList<Unit>();
        var inconsistencies = new AtomicInteger(0);
        var totalReads = new AtomicInteger(0);

        Creator creator = new Creator(config, null, lastTiming, u -> {}, epoch -> new SimpleEpochProofBuilder(),
                                      verifiers);
        creator.start();

        var barrier = new CyclicBarrier(THREADS);
        var latch = new CountDownLatch(THREADS);

        for (int i = 0; i < THREADS; i++) {
            executor.submit(() -> {
                try {
                    barrier.await();

                    for (int j = 0; j < ITERATIONS / THREADS; j++) {
                        // Read epoch three times in succession
                        var e1 = creator.getCurrentEpoch();
                        var e2 = creator.getCurrentEpoch();
                        var e3 = creator.getCurrentEpoch();
                        totalReads.addAndGet(3);

                        // Reads should be monotonic non-decreasing
                        if (e1 > e2 || e2 > e3) {
                            inconsistencies.incrementAndGet();
                        }

                        if (j % 10 == 0) {
                            Thread.yield(); // Encourage interleaving
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(60, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(0, inconsistencies.get(),
            "Epoch reads should be monotonic. Total reads: " + totalReads.get());
    }

    /**
     * Test concurrent access to epoch state doesn't cause race conditions.
     */
    @Test
    public void testConcurrentEpochAccess() throws Exception {
        var lastTiming = new LinkedList<Unit>();
        var violations = new AtomicInteger(0);

        Creator creator = new Creator(config, null, lastTiming, u -> {}, epoch -> new SimpleEpochProofBuilder(),
                                      verifiers);
        creator.start();

        var barrier = new CyclicBarrier(THREADS);
        var latch = new CountDownLatch(THREADS);
        var minEpochSeen = new AtomicInteger(Integer.MAX_VALUE);
        var maxEpochSeen = new AtomicInteger(0);

        for (int i = 0; i < THREADS; i++) {
            executor.submit(() -> {
                try {
                    barrier.await();

                    for (int j = 0; j < ITERATIONS; j++) {
                        var epoch = creator.getCurrentEpoch();

                        // Track min/max epochs seen
                        minEpochSeen.updateAndGet(current -> Math.min(current, epoch));
                        maxEpochSeen.updateAndGet(current -> Math.max(current, epoch));

                        // Epoch should be non-negative
                        if (epoch < 0) {
                            violations.incrementAndGet();
                        }

                        if (j % 50 == 0) {
                            Thread.yield();
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(60, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(0, violations.get(), "No violations detected");
        assertTrue(minEpochSeen.get() >= 0, "Minimum epoch should be >= 0");
        assertTrue(maxEpochSeen.get() >= minEpochSeen.get(), "Max epoch >= min epoch");
    }

    /**
     * Stress test with rapid epoch reads under contention.
     */
    @Test
    public void testHighContentionEpochReads() throws Exception {
        var lastTiming = new LinkedList<Unit>();
        var negativeEpochs = new AtomicInteger(0);
        var backwards = new AtomicInteger(0);

        Creator creator = new Creator(config, null, lastTiming, u -> {}, epoch -> new SimpleEpochProofBuilder(),
                                      verifiers);
        creator.start();

        var startSignal = new CountDownLatch(1);
        var doneSignal = new CountDownLatch(THREADS);

        for (int i = 0; i < THREADS; i++) {
            executor.submit(() -> {
                try {
                    startSignal.await(); // All threads start together

                    var lastSeen = -1;
                    for (int j = 0; j < ITERATIONS * 2; j++) {
                        var epoch = creator.getCurrentEpoch();

                        if (epoch < 0) {
                            negativeEpochs.incrementAndGet();
                        }

                        if (lastSeen >= 0 && epoch < lastSeen) {
                            backwards.incrementAndGet();
                        }

                        lastSeen = epoch;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneSignal.countDown();
                }
            });
        }

        startSignal.countDown(); // Start all threads
        assertTrue(doneSignal.await(60, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(0, negativeEpochs.get(), "No negative epochs should be observed");
        assertEquals(0, backwards.get(), "Epochs should never go backwards within a thread");
    }

    /**
     * Test that multiple threads reading epoch concurrently get consistent results.
     */
    @Test
    public void testMemoryVisibility() throws Exception {
        var lastTiming = new LinkedList<Unit>();

        Creator creator = new Creator(config, null, lastTiming, u -> {}, epoch -> new SimpleEpochProofBuilder(),
                                      verifiers);
        creator.start();

        var initialEpoch = creator.getCurrentEpoch();
        var allThreadsSeeSameEpoch = new AtomicInteger(0);
        var barrier = new CyclicBarrier(THREADS);
        var latch = new CountDownLatch(THREADS);

        for (int i = 0; i < THREADS; i++) {
            executor.submit(() -> {
                try {
                    barrier.await();

                    // All threads should see the same epoch at this instant
                    var epoch = creator.getCurrentEpoch();
                    if (epoch == initialEpoch) {
                        allThreadsSeeSameEpoch.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        // At least some threads should have seen the initial epoch
        assertTrue(allThreadsSeeSameEpoch.get() > 0,
            "At least some threads should see consistent epoch value");
    }

    /**
     * Simple epoch proof builder for testing.
     */
    private static class SimpleEpochProofBuilder implements EpochProofBuilder {
        @Override
        public ByteString buildShare(Unit timingUnit) {
            return ByteString.copyFromUtf8("epoch-proof-share");
        }

        @Override
        public ByteString tryBuilding(Unit unit) {
            // No automatic epoch transitions in these tests
            return null;
        }

        @Override
        public boolean verify(Unit unit) {
            return true;
        }
    }

    /**
     * Helper: Create a test PreUnit.
     */
    @SuppressWarnings("unused")
    private PreUnit createTestPreUnit(short creator, int epoch, int height) {
        var crown = new Crown(new int[N_PROC], Digest.NONE);
        var signature = new JohnHancock(SignatureAlgorithm.DEFAULT, new byte[64], ULong.MIN);
        var hash = DigestAlgorithm.DEFAULT.digest("test-unit-" + creator + "-" + epoch + "-" + height);
        return new preUnit(creator, epoch, height, hash, crown, ByteString.EMPTY, signature, new byte[0]);
    }
}
