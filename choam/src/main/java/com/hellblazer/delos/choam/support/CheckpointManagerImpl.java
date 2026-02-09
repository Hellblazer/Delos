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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default implementation of CheckpointManager for CHOAM consensus.
 * <p>
 * This implementation manages checkpoint state using:
 * - An atomic reference to the current checkpoint block
 * - A cache of recent checkpoints for replication
 * - Integration with BlockStore for persistent checkpoint storage
 *
 * @author hal.hildebrand
 */
public class CheckpointManagerImpl implements CheckpointManager {
    private static final Logger log = LoggerFactory.getLogger(CheckpointManagerImpl.class);

    private final Map<ULong, CheckpointState>            cachedCheckpoints = new ConcurrentHashMap<>();
    private final AtomicReference<HashedCertifiedBlock>  checkpoint;
    private final BlockStore                             blockStore;
    private final Parameters                             params;

    public CheckpointManagerImpl(BlockStore blockStore, Parameters params) {
        this.blockStore = blockStore;
        this.params = params;
        this.checkpoint = new AtomicReference<>(new NullBlock(params.digestAlgorithm()));
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

    @Override
    public Checkpoint createCheckpointAndGet(ULong height, File state) {
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

    @Override
    public CheckpointState getCheckpointState(ULong height) {
        return cachedCheckpoints.get(height);
    }

    @Override
    public void restoreFromCheckpoint(HashedCertifiedBlock checkpointBlock, CheckpointState state) {
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
