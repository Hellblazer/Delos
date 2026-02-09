/*
 * Copyright (c) 2026, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam.support;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.choam.CheckpointManager;
import com.hellblazer.delos.choam.Parameters;
import com.hellblazer.delos.choam.proto.Checkpoint;
import com.hellblazer.delos.choam.support.HashedCertifiedBlock.NullBlock;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.HexBloom;
import org.h2.mvstore.MVMap;
import org.joou.ULong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default implementation of CheckpointManager for CHOAM consensus.
 * <p>
 * This implementation manages checkpoint state using:
 * - An atomic reference to the current checkpoint block
 * - A cache of recent checkpoints for replication
 * - Integration with BlockStore for persistent checkpoint storage
 * - Optional background thread for asynchronous checkpoint creation
 * <p>
 * Thread Safety:
 * - All public methods are thread-safe
 * - Background checkpoint creation uses a single-threaded executor for ordering
 * - Shutdown ensures graceful termination of background operations
 *
 * @author hal.hildebrand
 */
public class CheckpointManagerImpl implements CheckpointManager {
    private static final Logger log = LoggerFactory.getLogger(CheckpointManagerImpl.class);

    private final Map<ULong, CheckpointState>            cachedCheckpoints = new ConcurrentHashMap<>();
    private final AtomicReference<HashedCertifiedBlock>  checkpoint;
    private final BlockStore                             blockStore;
    private final Parameters                             params;
    private final ExecutorService                        checkpointExecutor;
    private final AtomicBoolean                          shutdown          = new AtomicBoolean(false);

    public CheckpointManagerImpl(BlockStore blockStore, Parameters params) {
        this.blockStore = blockStore;
        this.params = params;
        this.checkpoint = new AtomicReference<>(new NullBlock(params.digestAlgorithm()));

        // Create single-threaded executor for background checkpoints if async mode enabled
        if (params.asyncCheckpointCreation()) {
            this.checkpointExecutor = Executors.newSingleThreadExecutor(r -> {
                var thread = new Thread(r, "checkpoint-creator-" + params.member().getId());
                thread.setDaemon(false); // Non-daemon to allow graceful completion
                return thread;
            });
            log.info("Background checkpoint creation enabled on: {}", params.member().getId());
        } else {
            this.checkpointExecutor = null;
            log.debug("Synchronous checkpoint creation (default) on: {}", params.member().getId());
        }
    }

    @Override
    public ULong lastCheckpoint() {
        var cp = checkpoint.get();
        return cp == null ? null : cp.height();
    }

    @Override
    public Checkpoint getCheckpoint(ULong height) {
        var state = cachedCheckpoints.get(height);
        return state == null ? null : state.checkpoint;
    }

    @Override
    public void createCheckpoint(ULong height, File state) {
        if (shutdown.get()) {
            throw new IllegalStateException("CheckpointManager is shut down on: " + params.member().getId());
        }

        if (params.asyncCheckpointCreation() && checkpointExecutor != null) {
            // Submit to background thread (non-blocking)
            checkpointExecutor.submit(() -> createCheckpointInternal(height, state));
            log.debug("Submitted checkpoint creation for height: {} to background thread on: {}",
                     height, params.member().getId());
        } else {
            // Execute synchronously (existing behavior)
            createCheckpointInternal(height, state);
        }
    }

    @Override
    public Checkpoint createCheckpointAndGet(ULong height, File state) {
        if (shutdown.get()) {
            throw new IllegalStateException("CheckpointManager is shut down on: " + params.member().getId());
        }

        if (params.asyncCheckpointCreation() && checkpointExecutor != null) {
            // For createCheckpointAndGet, we need to return the checkpoint synchronously
            // even in async mode, so we submit and wait (but still use background thread)
            try {
                var future = checkpointExecutor.submit(() -> createCheckpointInternalAndGet(height, state));
                return future.get(); // Wait for completion
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while creating checkpoint at height: {} on: {}", height, params.member().getId(), e);
                return null;
            } catch (ExecutionException e) {
                log.error("Failed to create checkpoint at height: {} on: {}", height, params.member().getId(), e);
                return null;
            }
        } else {
            // Execute synchronously (existing behavior)
            return createCheckpointInternalAndGet(height, state);
        }
    }

    @Override
    public CheckpointState getCheckpointState(ULong height) {
        return cachedCheckpoints.get(height);
    }

    @Override
    public void restoreFromCheckpoint(HashedCertifiedBlock checkpointBlock, CheckpointState state) {
        // Defensive validation of checkpoint height to prevent corruption
        ULong newHeight = checkpointBlock.height();

        // Validate height is not null
        if (newHeight == null) {
            throw new IllegalArgumentException("Checkpoint height cannot be null");
        }

        // Validate height is positive (>0)
        if (newHeight.longValue() == 0) {
            throw new IllegalArgumentException("Checkpoint height must be positive, got: " + newHeight);
        }

        // Validate height is monotonically increasing
        var currentCheckpoint = checkpoint.get();
        ULong currentHeight = currentCheckpoint.height();

        // Allow first checkpoint from genesis (currentHeight is null)
        if (currentHeight != null) {
            if (newHeight.compareTo(currentHeight) <= 0) {
                throw new IllegalStateException(
                    "Checkpoint height " + newHeight + " is not greater than current checkpoint height " +
                    currentHeight + " on member: " + params.member().getId()
                );
            }
        }

        // Validation passed, proceed with restore
        cachedCheckpoints.put(checkpointBlock.height(), state);
        evictOldCheckpoints();
        params.restorer().accept(checkpointBlock, state);
        checkpoint.set(checkpointBlock);
        log.info("Restored from checkpoint: {} height: {} on: {}", checkpointBlock.hash, checkpointBlock.height(),
                 params.member().getId());
    }

    @Override
    public HashedCertifiedBlock currentCheckpoint() {
        return checkpoint.get();
    }

    /**
     * Updates the checkpoint reference.
     * Called by CHOAM when processing checkpoint blocks.
     */
    public void updateCheckpoint(HashedCertifiedBlock newCheckpoint) {
        checkpoint.set(newCheckpoint);
    }

    /**
     * Package-private method to cache a checkpoint state.
     * Called during checkpoint assembly operations.
     */
    void cacheCheckpoint(ULong height, CheckpointState state) {
        cachedCheckpoints.put(height, state);
        evictOldCheckpoints();
    }

    /**
     * Shuts down the checkpoint manager, waiting for pending checkpoint operations to complete.
     * After shutdown, no new checkpoint operations can be started.
     *
     * @throws InterruptedException if interrupted while waiting for shutdown
     */
    public void shutdown() throws InterruptedException {
        if (shutdown.compareAndSet(false, true)) {
            log.info("Shutting down checkpoint manager on: {}", params.member().getId());

            if (checkpointExecutor != null) {
                checkpointExecutor.shutdown();
                if (!checkpointExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.warn("Checkpoint executor did not terminate within timeout, forcing shutdown on: {}",
                            params.member().getId());
                    checkpointExecutor.shutdownNow();
                }
            }

            log.info("Checkpoint manager shut down on: {}", params.member().getId());
        }
    }

    /**
     * Returns true if this checkpoint manager has been shut down.
     *
     * @return true if shut down
     */
    public boolean isShutdown() {
        return shutdown.get();
    }

    /**
     * Evicts the oldest checkpoints when the cache exceeds maxCachedCheckpoints.
     * Keeps the most recent checkpoints (highest height values).
     * <p>
     * Thread-safe: Uses synchronization to prevent TOCTOU race conditions
     * between size check and eviction operations.
     */
    private void evictOldCheckpoints() {
        var maxCached = params.maxCachedCheckpoints();

        // Synchronize to prevent race condition between size check and eviction
        synchronized (cachedCheckpoints) {
            if (cachedCheckpoints.size() <= maxCached) {
                return;
            }

            // Find checkpoints to evict (keep the newest ones)
            var toEvict = cachedCheckpoints.keySet()
                                           .stream()
                                           .sorted(Comparator.naturalOrder())
                                           .limit(cachedCheckpoints.size() - maxCached)
                                           .toList();

            for (var height : toEvict) {
                cachedCheckpoints.remove(height);
                log.debug("Evicted checkpoint at height: {} from cache (max: {}) on: {}",
                          height, maxCached, params.member().getId());
            }

            if (!toEvict.isEmpty()) {
                log.info("Evicted {} old checkpoint(s) from cache, kept {} on: {}",
                         toEvict.size(), cachedCheckpoints.size(), params.member().getId());
            }
        }
    }

    /**
     * Internal method to create a checkpoint (void return).
     * This is the actual implementation that performs the I/O and computation.
     *
     * @param height the checkpoint height
     * @param state  the state file to checkpoint
     */
    private void createCheckpointInternal(ULong height, File state) {
        var cp = checkpoint.get();
        Checkpoint chkpt = buildCheckpoint(params.digestAlgorithm(), state, params.checkpointSegmentSize(), cp.hash,
                                           params.crowns(), params.member().getId());
        if (chkpt == null) {
            throw new IllegalStateException("Failed to create checkpoint at height: " + height);
        }

        MVMap<Integer, byte[]> stored = blockStore.putCheckpoint(height, state, chkpt);
        try {
            cachedCheckpoints.put(height, new CheckpointState(chkpt, stored));
            evictOldCheckpoints();
            log.info("Created checkpoint at height: {} on: {}", height, params.member().getId());
        } finally {
            if (!state.delete()) {
                log.warn("Failed to delete checkpoint state file: {} on: {}",
                         state.getAbsolutePath(), params.member().getId());
            }
        }
    }

    /**
     * Internal method to create a checkpoint and return it.
     * This is the actual implementation that performs the I/O and computation.
     *
     * @param height the checkpoint height
     * @param state  the state file to checkpoint
     * @return the created checkpoint, or null if creation fails
     */
    private Checkpoint createCheckpointInternalAndGet(ULong height, File state) {
        var cp = checkpoint.get();
        Checkpoint chkpt = buildCheckpoint(params.digestAlgorithm(), state, params.checkpointSegmentSize(), cp.hash,
                                           params.crowns(), params.member().getId());
        if (chkpt == null) {
            return null;
        }

        MVMap<Integer, byte[]> stored = blockStore.putCheckpoint(height, state, chkpt);
        try {
            cachedCheckpoints.put(height, new CheckpointState(chkpt, stored));
            evictOldCheckpoints();
            log.info("Created checkpoint at height: {} on: {}", height, params.member().getId());
            return chkpt;
        } finally {
            if (!state.delete()) {
                log.warn("Failed to delete checkpoint state file: {} on: {}",
                         state.getAbsolutePath(), params.member().getId());
            }
        }
    }

    /**
     * Creates a checkpoint protobuf from a state file.
     * <p>
     * This method segments the state file, computes a HexBloom crown for validation,
     * and returns the checkpoint metadata.
     *
     * @param algo        the digest algorithm
     * @param state       the state file to checkpoint
     * @param segmentSize the size of each checkpoint segment
     * @param initial     the initial digest for the HexBloom
     * @param crowns      the number of crowns in the HexBloom
     * @param id          the member ID (for logging)
     * @return the checkpoint protobuf, or null if creation fails
     */
    private static Checkpoint buildCheckpoint(DigestAlgorithm algo, File state, int segmentSize, Digest initial,
                                              int crowns, Digest id) {
        assert segmentSize > 0 : "segment size must be > 0 : " + segmentSize;
        long length = 0;
        if (state != null) {
            length = state.length();
        }
        int count = (int) (length / segmentSize);
        if (length != 0 && (long) count * segmentSize < length) {
            count++;
        }
        var accumulator = new HexBloom.HexAccumulator(count, crowns, initial);
        Checkpoint.Builder builder = Checkpoint.newBuilder()
                                                .setCount(count)
                                                .setByteSize(length)
                                                .setSegmentSize(segmentSize);

        if (state != null) {
            byte[] buff = new byte[segmentSize];
            try (FileInputStream fis = new FileInputStream(state)) {
                for (int read = fis.read(buff); read > 0; read = fis.read(buff)) {
                    ByteString segment = ByteString.copyFrom(buff, 0, read);
                    accumulator.add(algo.digest(segment));
                }
            } catch (IOException e) {
                log.error("Invalid checkpoint!", e);
                return null;
            }
        }
        var crown = accumulator.build();
        log.info("Checkpoint length: {} segment size: {} count: {} crown: {} initial: {} on: {}", length, segmentSize,
                 builder.getCount(), crown.compactWrapped(), initial, id);
        var cp = builder.setCrown(crown.toHexBloome()).build();

        var deserialized = HexBloom.from(cp.getCrown());
        log.info("Deserialized checkpoint crown: {} initial: {} on: {}", deserialized.compactWrapped(), initial, id);
        return cp;
    }
}
