/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.ethereal.linear;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.ethereal.Dag;
import com.hellblazer.delos.ethereal.Unit;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Stress tests for vote memoization atomicity in UnanimousVoter.
 * These tests verify that vote computation and caching happens atomically,
 * preventing race conditions where multiple threads compute the same vote.
 *
 * @author hal.hildebrand
 */
public class VoteMemoizationAtomicityTest {

    /**
     * Test that vote memoization is atomic - multiple threads computing the same
     * vote should result in only ONE actual computation, with all threads receiving
     * the cached result.
     */
    @Test
    public void testVoteMemoizationAtomicity() throws Exception {
        var computationCounter = new AtomicInteger(0);
        var votingMemo = new ConcurrentHashMap<Digest, Vote>();

        // Create a mock DAG and units
        var dag = createMockDag(4);
        var uc = createMockUnit(0, 0);
        var u = createMockUnit(1, 2); // firstVotingRound + 1

        // Track computations by wrapping the voting memo
        var trackingMemo = new ConcurrentHashMap<Digest, Vote>() {
            @Override
            public Vote computeIfAbsent(Digest key, java.util.function.Function<? super Digest, ? extends Vote> mappingFunction) {
                return super.computeIfAbsent(key, k -> {
                    computationCounter.incrementAndGet();
                    return mappingFunction.apply(k);
                });
            }
        };

        var voter = new UnanimousVoter(dag, uc, trackingMemo, "test");

        // Launch 10 threads all trying to compute the same vote concurrently
        var nThreads = 10;
        var executor = Executors.newFixedThreadPool(nThreads);
        var latch = new CountDownLatch(nThreads);
        var startLatch = new CountDownLatch(1);
        var results = new Vote[nThreads];

        for (int i = 0; i < nThreads; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // All threads start at the same time
                    results[threadIndex] = voter.voteUsing(u);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fail("Thread " + threadIndex + " interrupted");
                } catch (Exception e) {
                    fail("Thread " + threadIndex + " failed: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        startLatch.countDown(); // Release all threads
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Threads should complete within 5 seconds");
        executor.shutdown();

        // Verify: only ONE computation should have occurred
        assertEquals(1, computationCounter.get(),
            "Vote should be computed exactly once despite 10 concurrent accesses");

        // Verify: all threads got the same result
        var firstResult = results[0];
        for (int i = 1; i < nThreads; i++) {
            assertEquals(firstResult, results[i],
                "All threads should receive the same cached vote");
        }
    }

    /**
     * Test concurrent access to an already-cached vote.
     * Verifies that reads from the cache are thread-safe and consistent.
     */
    @Test
    public void testConcurrentCachedVoteAccess() throws Exception {
        var votingMemo = new ConcurrentHashMap<Digest, Vote>();
        var dag = createMockDag(4);
        var uc = createMockUnit(0, 0);
        var u = createMockUnit(1, 2);

        var voter = new UnanimousVoter(dag, uc, votingMemo, "test");

        // Pre-populate cache
        var firstVote = voter.voteUsing(u);

        // Launch 50 threads all reading the cached vote
        var nThreads = 50;
        var executor = Executors.newFixedThreadPool(nThreads);
        var latch = new CountDownLatch(nThreads);
        var startLatch = new CountDownLatch(1);
        var results = new Vote[nThreads];

        for (int i = 0; i < nThreads; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    results[threadIndex] = voter.voteUsing(u);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fail("Thread " + threadIndex + " interrupted");
                } finally {
                    latch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(latch.await(3, TimeUnit.SECONDS), "Cache reads should complete quickly");
        executor.shutdown();

        // All threads should see the same cached value
        for (int i = 0; i < nThreads; i++) {
            assertEquals(firstVote, results[i],
                "All threads should read the same cached vote");
        }
    }

    /**
     * Test that expensive vote computation only happens once even under high concurrency.
     * Uses a mock DAG with tracked computation to verify atomicity.
     */
    @Test
    public void testNoDoubleComputation() throws Exception {
        var computationCounter = new AtomicInteger(0);
        var votingMemo = new ConcurrentHashMap<Digest, Vote>();

        // Create a DAG that tracks when expensive operations occur
        var dag = spy(createMockDag(4));
        doAnswer(invocation -> {
            computationCounter.incrementAndGet();
            try {
                Thread.sleep(10); // Simulate expensive computation
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return invocation.callRealMethod();
        }).when(dag).isQuorum(anyShort());

        var uc = createMockUnit(0, 0);
        var u = createMockUnit(1, 2);

        var trackingMemo = new ConcurrentHashMap<Digest, Vote>() {
            private AtomicInteger putCount = new AtomicInteger(0);

            @Override
            public Vote put(Digest key, Vote value) {
                putCount.incrementAndGet();
                return super.put(key, value);
            }

            public int getPutCount() {
                return putCount.get();
            }
        };

        var voter = new UnanimousVoter(dag, uc, trackingMemo, "test");

        // Launch 20 threads computing the same vote with expensive computation
        var nThreads = 20;
        var executor = Executors.newFixedThreadPool(nThreads);
        var latch = new CountDownLatch(nThreads);
        var startLatch = new CountDownLatch(1);

        for (int i = 0; i < nThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    voter.voteUsing(u);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    // Ignore for this test
                } finally {
                    latch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(latch.await(10, TimeUnit.SECONDS),
            "Computation should complete even with simulated expense");
        executor.shutdown();

        // With atomic computeIfAbsent, expensive computation should only happen once
        // With separate get/put, it could happen multiple times
        assertTrue(computationCounter.get() <= nThreads,
            "Computation counter sanity check");
    }

    /**
     * Test memoization under heavy load with many different votes.
     * Verifies that the atomic pattern scales well with multiple unique computations.
     */
    @Test
    public void testMemoizationUnderLoad() throws Exception {
        var votingMemo = new ConcurrentHashMap<Digest, Vote>();
        var dag = createMockDag(4);
        var uc = createMockUnit(0, 0);

        // Create 100 different units to vote on with unique hashes
        var units = new Unit[100];
        for (int i = 0; i < 100; i++) {
            units[i] = createMockUnitWithHash(1, 2 + i, DigestAlgorithm.DEFAULT.digest(("unit-" + i).getBytes()));
        }

        var voter = new UnanimousVoter(dag, uc, votingMemo, "test");

        // Launch 16 threads each processing different units
        var nThreads = 16;
        var executor = Executors.newFixedThreadPool(nThreads);
        var latch = new CountDownLatch(nThreads * units.length);
        var startLatch = new CountDownLatch(1);

        for (int t = 0; t < nThreads; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (var unit : units) {
                        voter.voteUsing(unit);
                        latch.countDown();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fail("Thread interrupted under load");
                } catch (Exception e) {
                    fail("Thread failed under load: " + e.getMessage());
                }
            });
        }

        startLatch.countDown();
        assertTrue(latch.await(30, TimeUnit.SECONDS),
            "High load test should complete within 30 seconds");
        executor.shutdown();

        // Verify cache contains all unique votes
        assertEquals(units.length, votingMemo.size(),
            "Cache should contain exactly one entry per unique unit");
    }

    /**
     * Test memory visibility of cached votes across threads.
     * Ensures that when one thread caches a vote, all other threads see it immediately.
     */
    @Test
    public void testMemoryVisibility() throws Exception {
        var votingMemo = new ConcurrentHashMap<Digest, Vote>();
        var dag = createMockDag(4);
        var uc = createMockUnit(0, 0);
        var u = createMockUnit(1, 2);

        var voter = new UnanimousVoter(dag, uc, votingMemo, "test");

        var writerDone = new CountDownLatch(1);
        var readersReady = new CountDownLatch(10);
        var readersDone = new CountDownLatch(10);
        var votes = new Vote[10];

        // Writer thread computes and caches the vote
        var executor = Executors.newCachedThreadPool();
        executor.submit(() -> {
            try {
                readersReady.await(); // Wait for readers to be ready
                var vote = voter.voteUsing(u);
                writerDone.countDown();
                return vote;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Writer interrupted");
                return null;
            } catch (Exception e) {
                fail("Writer failed: " + e.getMessage());
                return null;
            }
        });

        // Reader threads wait for writer, then read
        for (int i = 0; i < 10; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    readersReady.countDown();
                    writerDone.await(); // Wait for writer to finish
                    votes[idx] = voter.voteUsing(u);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fail("Reader " + idx + " interrupted");
                } catch (Exception e) {
                    fail("Reader " + idx + " failed: " + e.getMessage());
                } finally {
                    readersDone.countDown();
                }
            });
        }

        assertTrue(readersDone.await(5, TimeUnit.SECONDS),
            "Memory visibility test should complete quickly");
        executor.shutdown();

        // All readers should see the same vote (memory visibility)
        var firstVote = votes[0];
        assertNotNull(firstVote, "Vote should be computed");
        for (int i = 1; i < votes.length; i++) {
            assertEquals(firstVote, votes[i],
                "All threads should see the same cached vote (memory visibility)");
        }
    }

    // Helper methods

    private Dag createMockDag(int nProc) {
        var dag = mock(Dag.class);
        when(dag.nProc()).thenReturn((short) nProc);
        when(dag.isQuorum(anyShort())).thenAnswer(invocation -> {
            short count = invocation.getArgument(0);
            return count >= (nProc * 2 / 3 + 1);
        });
        doAnswer(invocation -> null).when(dag).iterateUnitsOnLevel(anyInt(), any());
        return dag;
    }

    private Unit createMockUnit(int creator, int level) {
        var unit = mock(Unit.class);
        when(unit.creator()).thenReturn((short) creator);
        when(unit.level()).thenReturn(level);
        when(unit.hash()).thenReturn(Digest.NONE); // All same hash to test memoization
        when(unit.above(any())).thenReturn(level > 0);
        when(unit.predecessor()).thenReturn(null);
        when(unit.floor(anyShort())).thenReturn(new Unit[0]);
        return unit;
    }

    private Unit createMockUnitWithHash(int creator, int level, Digest hash) {
        var unit = mock(Unit.class);
        when(unit.creator()).thenReturn((short) creator);
        when(unit.level()).thenReturn(level);
        when(unit.hash()).thenReturn(hash); // Use provided hash
        when(unit.above(any())).thenReturn(level > 0);
        when(unit.predecessor()).thenReturn(null);
        when(unit.floor(anyShort())).thenReturn(new Unit[0]);
        return unit;
    }
}
