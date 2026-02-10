/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.choam.proto.Checkpoint;
import com.hellblazer.delos.choam.support.CheckpointManagerImpl;
import com.hellblazer.delos.choam.support.CheckpointState;
import com.hellblazer.delos.choam.support.HashedCertifiedBlock;
import com.hellblazer.delos.choam.support.MVBlockStore;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.h2.mvstore.MVStore;
import org.joou.ULong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for background checkpoint creation optimization.
 * <p>
 * Tests:
 * - Background checkpoint creation completes successfully
 * - Thread safety with concurrent checkpoint operations
 * - Configuration parameter for async vs sync mode
 * - Latency improvement measurement
 * - Proper cleanup and shutdown
 *
 * @author hal.hildebrand
 */
public class BackgroundCheckpointTest {

    private static final int CHECKPOINT_SIZE_BYTES = 10 * 1024; // 10KB test checkpoint
    private static final int SEGMENT_SIZE = 1024; // 1KB segments

    @TempDir
    Path tempDir;

    private MVStore store;
    private MVBlockStore blockStore;
    private ControlledIdentifierMember member;
    private DigestAlgorithm digestAlgorithm;

    @BeforeEach
    public void setup() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 1, 2, 3 });
        digestAlgorithm = DigestAlgorithm.DEFAULT;

        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(digestAlgorithm), entropy);
        member = new ControlledIdentifierMember(stereotomy.newIdentifier());

        var storeFile = tempDir.resolve("test.mv.db").toFile();
        store = new MVStore.Builder().fileName(storeFile.getAbsolutePath()).open();
        blockStore = new MVBlockStore(digestAlgorithm, store);
    }

    @AfterEach
    public void cleanup() {
        if (store != null && !store.isClosed()) {
            store.close();
        }
    }

    /**
     * Test 1: Synchronous checkpoint creation (default behavior, baseline).
     * This test establishes baseline behavior and measures synchronous latency.
     */
    @Test
    public void testSynchronousCheckpointCreation() throws Exception {
        var params = buildParameters(false); // Synchronous mode
        var checkpointManager = new CheckpointManagerImpl(blockStore, params);

        var state = createTestStateFile(CHECKPOINT_SIZE_BYTES);
        var height = ULong.valueOf(1);

        var startTime = System.nanoTime();
        checkpointManager.createCheckpoint(height, state);
        var syncLatency = Duration.ofNanos(System.nanoTime() - startTime);

        // Verify checkpoint was created
        var checkpoint = checkpointManager.getCheckpoint(height);
        assertNotNull(checkpoint, "Checkpoint should be created");
        assertEquals(CHECKPOINT_SIZE_BYTES, checkpoint.getByteSize(), "Checkpoint size should match");

        // Log baseline latency for comparison
        System.out.printf("Synchronous checkpoint latency: %d ms%n", syncLatency.toMillis());

        // Synchronous should block, so checkpoint is immediately available
        assertNotNull(checkpointManager.getCheckpointState(height), "Checkpoint state should be cached");
    }

    /**
     * Test 2: Background checkpoint creation completes successfully.
     * Validates that async mode creates checkpoint without errors.
     */
    @Test
    public void testBackgroundCheckpointCreation() throws Exception {
        var params = buildParameters(true); // Async mode
        var checkpointManager = new CheckpointManagerImpl(blockStore, params);

        var state = createTestStateFile(CHECKPOINT_SIZE_BYTES);
        var height = ULong.valueOf(1);

        var startTime = System.nanoTime();
        checkpointManager.createCheckpoint(height, state);
        var callLatency = Duration.ofNanos(System.nanoTime() - startTime);

        // Async call should return quickly (not blocking on I/O)
        assertTrue(callLatency.toMillis() < 50,
            "Async checkpoint call should return quickly, took: " + callLatency.toMillis() + "ms");

        // Wait for background thread to complete (with timeout)
        var completed = awaitCheckpointCreation(checkpointManager, height, Duration.ofSeconds(5));
        assertTrue(completed, "Background checkpoint should complete within timeout");

        // Verify checkpoint was created
        var checkpoint = checkpointManager.getCheckpoint(height);
        assertNotNull(checkpoint, "Checkpoint should be created asynchronously");
        assertEquals(CHECKPOINT_SIZE_BYTES, checkpoint.getByteSize(), "Checkpoint size should match");

        System.out.printf("Async checkpoint call latency: %d ms%n", callLatency.toMillis());
    }

    /**
     * Test 3: Thread safety with concurrent checkpoint creation.
     * Validates that multiple concurrent checkpoint calls don't corrupt state.
     */
    @Test
    public void testConcurrentCheckpointCreation() throws Exception {
        var params = buildParameters(true); // Async mode
        var checkpointManager = new CheckpointManagerImpl(blockStore, params);

        var numCheckpoints = 5;
        var latch = new CountDownLatch(numCheckpoints);
        var errors = new ConcurrentLinkedQueue<Throwable>();

        // Create multiple checkpoints concurrently
        var executor = Executors.newFixedThreadPool(numCheckpoints);
        try {
            for (int i = 0; i < numCheckpoints; i++) {
                var height = ULong.valueOf(i + 1);
                var state = createTestStateFile(CHECKPOINT_SIZE_BYTES);

                executor.submit(() -> {
                    try {
                        checkpointManager.createCheckpoint(height, state);
                    } catch (Throwable t) {
                        errors.add(t);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(10, TimeUnit.SECONDS),
                "All checkpoint submissions should complete");

            // Wait for background processing to complete
            for (int i = 0; i < numCheckpoints; i++) {
                var height = ULong.valueOf(i + 1);
                var completed = awaitCheckpointCreation(checkpointManager, height, Duration.ofSeconds(5));
                assertTrue(completed, "Checkpoint " + height + " should complete");
            }

            // Verify no errors occurred
            assertTrue(errors.isEmpty(), "No errors should occur: " + errors);

            // Verify all checkpoints were created
            for (int i = 0; i < numCheckpoints; i++) {
                var height = ULong.valueOf(i + 1);
                var checkpoint = checkpointManager.getCheckpoint(height);
                assertNotNull(checkpoint, "Checkpoint " + height + " should exist");
            }
        } finally {
            executor.shutdown();
        }
    }

    /**
     * Test 4: Latency improvement measurement (async vs sync).
     * Validates that async mode reduces blocking time for transaction processing.
     */
    @Test
    public void testLatencyImprovement() throws Exception {
        var syncParams = buildParameters(false);
        var asyncParams = buildParameters(true);

        var syncManager = new CheckpointManagerImpl(blockStore, syncParams);
        var asyncManager = new CheckpointManagerImpl(blockStore, asyncParams);

        var numIterations = 3;
        var syncLatencies = new long[numIterations];
        var asyncLatencies = new long[numIterations];

        // Measure synchronous latency
        for (int i = 0; i < numIterations; i++) {
            var state = createTestStateFile(CHECKPOINT_SIZE_BYTES);
            var height = ULong.valueOf(i + 1);

            var start = System.nanoTime();
            syncManager.createCheckpoint(height, state);
            syncLatencies[i] = System.nanoTime() - start;
        }

        // Measure asynchronous latency (call return time, not completion)
        for (int i = 0; i < numIterations; i++) {
            var state = createTestStateFile(CHECKPOINT_SIZE_BYTES);
            var height = ULong.valueOf(numIterations + i + 1);

            var start = System.nanoTime();
            asyncManager.createCheckpoint(height, state);
            asyncLatencies[i] = System.nanoTime() - start;
        }

        // Calculate averages
        var avgSyncNanos = average(syncLatencies);
        var avgAsyncNanos = average(asyncLatencies);

        System.out.printf("Average sync latency: %.2f ms%n", avgSyncNanos / 1_000_000.0);
        System.out.printf("Average async call latency: %.2f ms%n", avgAsyncNanos / 1_000_000.0);
        System.out.printf("Latency reduction: %.1f%%%n",
            (1 - avgAsyncNanos / (double) avgSyncNanos) * 100);

        // Async should be significantly faster (at least 50% reduction in call latency)
        assertTrue(avgAsyncNanos < avgSyncNanos * 0.5,
            String.format("Async should be faster: sync=%.2fms async=%.2fms",
                avgSyncNanos / 1_000_000.0, avgAsyncNanos / 1_000_000.0));
    }

    /**
     * Test 5: Configuration parameter for async vs sync mode.
     * Validates that the asyncCheckpointCreation parameter controls behavior.
     */
    @Test
    public void testConfigurationParameter() throws Exception {
        // Test default (false - synchronous)
        var defaultParams = Parameters.newBuilder()
            .build(buildRuntimeParameters());
        assertFalse(defaultParams.asyncCheckpointCreation(),
            "Default should be synchronous for backward compatibility");

        // Test explicit true
        var asyncParams = Parameters.newBuilder()
            .setAsyncCheckpointCreation(true)
            .build(buildRuntimeParameters());
        assertTrue(asyncParams.asyncCheckpointCreation(),
            "Should enable async when explicitly set");

        // Test explicit false
        var syncParams = Parameters.newBuilder()
            .setAsyncCheckpointCreation(false)
            .build(buildRuntimeParameters());
        assertFalse(syncParams.asyncCheckpointCreation(),
            "Should be synchronous when explicitly disabled");
    }

    /**
     * Test 6: Proper cleanup and shutdown.
     * Validates that background threads are properly terminated.
     */
    @Test
    public void testProperCleanup() throws Exception {
        var params = buildParameters(true);
        var checkpointManager = new CheckpointManagerImpl(blockStore, params);

        // Create a checkpoint
        var state = createTestStateFile(CHECKPOINT_SIZE_BYTES);
        checkpointManager.createCheckpoint(ULong.valueOf(1), state);

        // Shutdown should wait for pending operations
        checkpointManager.shutdown();

        // Verify shutdown completed
        assertTrue(checkpointManager.isShutdown(), "Manager should be shut down");

        // Further operations should fail gracefully
        var state2 = createTestStateFile(CHECKPOINT_SIZE_BYTES);
        var thrown = assertThrows(IllegalStateException.class,
            () -> checkpointManager.createCheckpoint(ULong.valueOf(2), state2),
            "Operations after shutdown should fail");
        assertNotNull(thrown);
    }

    /**
     * Test 7: Background checkpoint doesn't block transaction processing.
     * Simulates transaction processing during checkpoint creation.
     * <p>
     * This test verifies that async mode allows the caller to return immediately,
     * even if the checkpoint completes quickly. The key is that createCheckpoint()
     * returns without blocking, not how long the checkpoint takes.
     */
    @Test
    public void testNonBlockingTransactionProcessing() throws Exception {
        var syncParams = buildParameters(false);
        var asyncParams = buildParameters(true);

        var syncManager = new CheckpointManagerImpl(blockStore, syncParams);
        var asyncManager = new CheckpointManagerImpl(blockStore, asyncParams);

        // Measure synchronous blocking time
        var syncState = createTestStateFile(50 * 1024); // 50KB
        var syncStart = System.nanoTime();
        syncManager.createCheckpoint(ULong.valueOf(1), syncState);
        var syncDuration = Duration.ofNanos(System.nanoTime() - syncStart);

        // Measure asynchronous call return time (should be much faster)
        var asyncState = createTestStateFile(50 * 1024); // 50KB
        var asyncStart = System.nanoTime();
        asyncManager.createCheckpoint(ULong.valueOf(1), asyncState);
        var asyncCallDuration = Duration.ofNanos(System.nanoTime() - asyncStart);

        // Wait for async checkpoint to complete
        var asyncCompleted = awaitCheckpointCreation(asyncManager, ULong.valueOf(1), Duration.ofSeconds(5));
        assertTrue(asyncCompleted, "Async checkpoint should complete");

        System.out.printf("Sync blocking time: %d ms%n", syncDuration.toMillis());
        System.out.printf("Async call return time: %d ms%n", asyncCallDuration.toMillis());
        System.out.printf("Non-blocking improvement: %.1f%%%n",
            (1 - asyncCallDuration.toNanos() / (double) syncDuration.toNanos()) * 100);

        // Async call should return much faster than synchronous (at least 50% faster)
        assertTrue(asyncCallDuration.toNanos() < syncDuration.toNanos() * 0.5,
            String.format("Async should return faster: sync=%dms async=%dms",
                syncDuration.toMillis(), asyncCallDuration.toMillis()));
    }

    // Helper methods

    private Parameters buildParameters(boolean async) {
        return Parameters.newBuilder()
            .setAsyncCheckpointCreation(async)
            .setCheckpointSegmentSize(SEGMENT_SIZE)
            .setCrowns(2)
            .build(buildRuntimeParameters());
    }

    private Parameters.RuntimeParameters buildRuntimeParameters() {
        return Parameters.RuntimeParameters.newBuilder()
            .setMember(member)
            .setProcessor(Parameters.RuntimeParameters.NOOP_PROCESSOR)
            .setRestorer(Parameters.RuntimeParameters.NOOP_RESTORER)
            .build();
    }

    private File createTestStateFile(int sizeBytes) throws IOException {
        var file = tempDir.resolve("checkpoint-" + System.nanoTime() + ".dat").toFile();
        try (var fos = new FileOutputStream(file)) {
            var data = new byte[sizeBytes];
            var random = new SecureRandom();
            random.nextBytes(data);
            fos.write(data);
        }
        return file;
    }

    private boolean awaitCheckpointCreation(CheckpointManagerImpl manager, ULong height, Duration timeout)
            throws InterruptedException {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (manager.getCheckpoint(height) != null) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    private double average(long[] values) {
        var sum = 0L;
        for (var value : values) {
            sum += value;
        }
        return sum / (double) values.length;
    }
}
