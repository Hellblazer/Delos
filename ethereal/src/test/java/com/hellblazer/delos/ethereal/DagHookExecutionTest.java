/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.ethereal;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.ethereal.Dag.DagImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for DAG hook execution outside write lock (Delos-k602).
 *
 * Validates that postInsert hooks execute after the write lock is released,
 * allowing concurrent DAG operations to proceed without blocking.
 *
 * PERFORMANCE (Delos-k602):
 * Hook execution optimization reduces lock hold time by moving postInsert hooks
 * outside the critical section:
 *
 * Before (hooks inside lock):
 * write(() -> {
 *     var unit = v.embed(this);
 *     for (var hook : preInsert) { hook.accept(unit); }  // Inside lock - OK
 *     // ... update DAG state ...
 *     for (var hook : postInsert) { hook.accept(unit); } // Inside lock - PROBLEM
 * });
 *
 * After (hooks outside lock):
 * Unit unit;
 * write(() -> {
 *     unit = v.embed(this);
 *     for (var hook : preInsert) { hook.accept(unit); }  // Inside lock - stays
 *     // ... update DAG state ...
 * });
 * for (var hook : postInsert) { hook.accept(unit); }     // Outside lock - FIXED
 *
 * Benefits:
 * 1. Lock hold time reduced (no longer includes hook execution)
 * 2. Concurrent reads/writes can proceed during hook execution
 * 3. Hooks can be slow without blocking DAG operations
 * 4. Async hooks supported for non-critical operations
 *
 * Requirements:
 * - Hooks execute after lock release (post-commit)
 * - Optional async hook execution
 * - Hook timeout enforcement (default 100ms)
 * - Hook exceptions don't fail insert
 * - Hooks receive correct unit data
 *
 * @author hal.hildebrand
 */
public class DagHookExecutionTest {

    private Unit createDealingUnit(Config config, short creator) {
        return PreUnit.newFreeUnit(creator, 0, new Unit[config.nProc()], 0, ByteString.EMPTY,
                                   config.digestAlgorithm(), config.signer());
    }

    /**
     * Verify that postInsert hooks execute after the write lock is released.
     * This is the core requirement - hooks should not block concurrent DAG operations.
     */
    @Test
    @Timeout(5)
    void testHooksExecuteOutsideLock() throws Exception {
        var config = Config.newBuilder().setnProc((short) 4).build();
        var dag = new DagImpl(config, 0);

        var hookExecuted = new AtomicBoolean(false);
        var hookBlocker = new CountDownLatch(1);
        var readOperationCompleted = new AtomicBoolean(false);

        // Register a hook that blocks for 100ms
        dag.afterInsert(unit -> {
            hookExecuted.set(true);
            try {
                // Simulate slow hook (e.g., metrics, logging, notification)
                hookBlocker.await(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        var unit = createDealingUnit(config, (short) 0);

        // Start insert in separate thread
        var insertThread = new Thread(() -> dag.insert(unit));
        insertThread.start();

        // Wait a bit for hook to start executing
        Thread.sleep(50);

        // Attempt concurrent read operation while hook is blocked
        // This should succeed immediately if hook executes outside lock
        var readThread = new Thread(() -> {
            var maxLevel = dag.maxLevel(); // Read operation
            readOperationCompleted.set(true);
        });
        readThread.start();

        // Read should complete quickly (within 100ms) even though hook is blocked
        readThread.join(100);

        // Unblock the hook
        hookBlocker.countDown();
        insertThread.join(200);

        // Assertions
        assertTrue(hookExecuted.get(), "Hook should have been executed");
        assertTrue(readOperationCompleted.get(),
            "Read operation should complete without waiting for hook (hook outside lock)");
    }

    /**
     * Verify that hooks receive correct unit data after lock release.
     * The unit data must be consistent even though the hook executes outside the lock.
     */
    @Test
    @Timeout(5)
    void testHooksReceiveCorrectData() throws Exception {
        var config = Config.newBuilder().setnProc((short) 4).build();
        var dag = new DagImpl(config, 0);

        var capturedHash = new AtomicReference<Digest>();
        var capturedCreator = new AtomicInteger(-1);
        var capturedHeight = new AtomicInteger(-1);

        // Register hook that captures unit data
        dag.afterInsert(unit -> {
            capturedHash.set(unit.hash());
            capturedCreator.set(unit.creator());
            capturedHeight.set(unit.height());
        });

        var unit = PreUnit.newFreeUnit((short) 2, 0, new Unit[4], 0,
                                       ByteString.copyFrom(new byte[]{1, 2, 3}),
                                       config.digestAlgorithm(), config.signer());

        dag.insert(unit);

        // Allow async hook execution to complete
        Thread.sleep(100);

        // Verify hook received correct data
        assertNotNull(capturedHash.get(), "Hook should have received unit hash");
        assertEquals(unit.hash(), capturedHash.get(), "Hook should receive correct hash");
        assertEquals(2, capturedCreator.get(), "Hook should receive correct creator");
        assertEquals(0, capturedHeight.get(), "Hook should receive correct height");
    }

    /**
     * Verify that hook exceptions don't cause insert to fail.
     * Insert operation must succeed even if hooks throw exceptions.
     */
    @Test
    @Timeout(5)
    void testHookExceptionsDontFailInsert() throws Exception {
        var config = Config.newBuilder().setnProc((short) 4).build();
        var dag = new DagImpl(config, 0);

        var hook1Executed = new AtomicBoolean(false);
        var hook2Executed = new AtomicBoolean(false);

        // Register hook that throws exception
        dag.afterInsert(unit -> {
            hook1Executed.set(true);
            throw new RuntimeException("Hook failure (simulated)");
        });

        // Register second hook that should still execute
        dag.afterInsert(unit -> {
            hook2Executed.set(true);
        });

        var unit = createDealingUnit(config, (short) 0);

        // Insert should not throw exception
        assertDoesNotThrow(() -> dag.insert(unit));

        // Allow hooks to execute
        Thread.sleep(100);

        // Verify unit was inserted successfully
        assertTrue(dag.contains(unit.hash()), "Unit should be in DAG despite hook exception");

        // Verify both hooks were attempted
        assertTrue(hook1Executed.get(), "First hook should have executed");
        assertTrue(hook2Executed.get(), "Second hook should have executed despite first hook exception");
    }

    /**
     * Verify that multiple hooks execute in registration order.
     */
    @Test
    @Timeout(5)
    void testMultipleHooksExecuteInOrder() throws Exception {
        var config = Config.newBuilder().setnProc((short) 4).build();
        var dag = new DagImpl(config, 0);

        var executionOrder = Collections.synchronizedList(new ArrayList<Integer>());

        // Register multiple hooks
        dag.afterInsert(unit -> executionOrder.add(1));
        dag.afterInsert(unit -> executionOrder.add(2));
        dag.afterInsert(unit -> executionOrder.add(3));

        var unit = createDealingUnit(config, (short) 0);
        dag.insert(unit);

        // Allow hooks to complete
        Thread.sleep(100);

        // Verify execution order
        assertEquals(List.of(1, 2, 3), executionOrder,
            "Hooks should execute in registration order");
    }

    /**
     * Verify that preInsert hooks still execute inside the lock.
     * Only postInsert hooks should be moved outside the lock.
     */
    @Test
    @Timeout(5)
    void testPreInsertHooksStillInsideLock() throws Exception {
        var config = Config.newBuilder().setnProc((short) 4).build();
        var dag = new DagImpl(config, 0);

        var preInsertExecuted = new AtomicBoolean(false);
        var unitInDagDuringPreInsert = new AtomicBoolean(false);

        // Register preInsert hook that checks if unit is already in DAG
        dag.beforeInsert(unit -> {
            preInsertExecuted.set(true);
            // During preInsert, unit should NOT yet be in DAG (we're inside the lock before insertion)
            unitInDagDuringPreInsert.set(dag.contains(unit.hash()));
        });

        var unit = createDealingUnit(config, (short) 0);
        dag.insert(unit);

        // Verify preInsert hook executed
        assertTrue(preInsertExecuted.get(), "PreInsert hook should have executed");
        assertFalse(unitInDagDuringPreInsert.get(),
            "Unit should not be in DAG during preInsert (hook executes before insertion)");

        // After insert completes, unit should be in DAG
        assertTrue(dag.contains(unit.hash()), "Unit should be in DAG after insert");
    }

    /**
     * Verify that the DAG remains usable during hook execution.
     * Multiple concurrent operations should be possible.
     */
    @Test
    @Timeout(5)
    void testConcurrentOperationsDuringHookExecution() throws Exception {
        var config = Config.newBuilder().setnProc((short) 4).build();
        var dag = new DagImpl(config, 0);

        var hookBlocker = new CountDownLatch(1);
        var concurrentOpsCompleted = new AtomicInteger(0);

        // Register slow hook
        dag.afterInsert(unit -> {
            try {
                hookBlocker.await(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        var unit = createDealingUnit(config, (short) 0);

        var insertThread = new Thread(() -> dag.insert(unit));
        insertThread.start();

        // Wait for hook to be executing
        Thread.sleep(50);

        // Launch concurrent operations while hook is blocked
        var executor = Executors.newFixedThreadPool(5);
        var operations = List.of(
            (Runnable) () -> { dag.maxLevel(); concurrentOpsCompleted.incrementAndGet(); },
            (Runnable) () -> { dag.maxView(); concurrentOpsCompleted.incrementAndGet(); },
            (Runnable) () -> { dag.contains(unit.hash()); concurrentOpsCompleted.incrementAndGet(); },
            (Runnable) () -> { dag.nProc(); concurrentOpsCompleted.incrementAndGet(); },
            (Runnable) () -> { dag.epoch(); concurrentOpsCompleted.incrementAndGet(); }
        );

        operations.forEach(executor::submit);
        executor.shutdown();

        // All operations should complete quickly despite hook being blocked
        var completed = executor.awaitTermination(100, TimeUnit.MILLISECONDS);

        // Unblock hook
        hookBlocker.countDown();
        insertThread.join(200);

        // Verify concurrent operations completed
        assertTrue(completed, "Concurrent operations should complete without waiting for hook");
        assertEquals(5, concurrentOpsCompleted.get(), "All 5 operations should have completed");
    }
}
