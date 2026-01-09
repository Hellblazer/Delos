/*
 * Copyright (c) 2020, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.bloomFilters.BloomFilter;
import com.hellblazer.delos.choam.proto.Blocks;
import com.hellblazer.delos.choam.proto.CertifiedBlock;
import com.hellblazer.delos.choam.proto.Certification;
import com.hellblazer.delos.choam.proto.Checkpoint;
import com.hellblazer.delos.cryptography.Digest;
import org.h2.mvstore.MVMap;
import org.joou.ULong;

import java.io.File;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Storage abstraction for CHOAM consensus blocks, checkpoint state, and block chain metadata.
 * Provides persistent storage operations for blocks, certifications, hashes, view chains,
 * and checkpoint segments.
 * <p>
 * This interface enables alternative storage implementations (e.g., RocksDB, in-memory stores)
 * while preserving the storage contract required by CHOAM consensus operations.
 * <p>
 * <b>Block Access:</b> Retrieve blocks by height or hash digest
 * <p>
 * <b>Checkpoint Operations:</b> Create, store, and retrieve checkpoint state segments
 * <p>
 * <b>Hash & Certification:</b> Store and lookup block hashes and certification chains
 * <p>
 * <b>Iterators/Replication:</b> Iterate block chains for synchronization and replication
 * <p>
 * <b>Validation:</b> Validate block chain integrity and checkpoint references
 * <p>
 * <b>Maintenance:</b> Garbage collection, rollback, and version management
 *
 * @author hal.hildebrand
 */
public interface BlockStore {

    /**
     * Retrieves a block by its hash digest.
     *
     * @param hash the block hash
     * @return the serialized block bytes, or null if not found
     */
    byte[] block(Digest hash);

    /**
     * Retrieves a block by its height.
     *
     * @param height the block height
     * @return the serialized block bytes, or null if not found
     */
    byte[] block(ULong height);

    /**
     * Returns an iterator over block heights from a starting height down to a target height.
     *
     * @param from the starting height (inclusive)
     * @param to the target height (inclusive)
     * @param max maximum number of blocks to iterate
     * @return iterator over block heights
     */
    Iterator<ULong> blocksFrom(ULong from, ULong to, int max);

    /**
     * Retrieves the certifications for a block at the specified height.
     *
     * @param height the block height
     * @return list of certifications, or null if not found
     */
    List<Certification> certifications(ULong height);

    /**
     * Checks if the view chain is complete from the specified height to genesis.
     *
     * @param from the starting height
     * @return true if the view chain is complete
     */
    boolean completeFrom(ULong from);

    /**
     * Checks if a block exists at the specified height.
     *
     * @param l the block height
     * @return true if the block exists
     */
    boolean containsBlock(ULong l);

    /**
     * Creates a new checkpoint storage map for the specified block height.
     *
     * @param blockHeight the checkpoint block height
     * @return the checkpoint segment map
     */
    MVMap<Integer, byte[]> createCheckpoint(ULong blockHeight);

    /**
     * Fetches blocks for replication, filtering by a Bloom filter.
     *
     * @param blocksBff the Bloom filter of blocks already held by the requester
     * @param replication the builder to populate with blocks
     * @param max maximum number of blocks to fetch
     * @param from the starting height
     * @param to the target height
     * @throws IllegalStateException if fetch fails
     */
    void fetchBlocks(BloomFilter<ULong> blocksBff, Blocks.Builder replication, int max, ULong from, ULong to)
    throws IllegalStateException;

    /**
     * Fetches view chain blocks for replication, filtering by a Bloom filter.
     *
     * @param chainBff the Bloom filter of view chain blocks already held
     * @param replication the builder to populate with blocks
     * @param maxChainCount maximum number of view chain blocks to fetch
     * @param incompleteStart the starting height
     * @param target the target height
     * @throws IllegalStateException if fetch fails
     */
    void fetchViewChain(BloomFilter<ULong> chainBff, Blocks.Builder replication, int maxChainCount,
                        ULong incompleteStart, ULong target) throws IllegalStateException;

    /**
     * Finds the first gap in the block chain from a starting height down to a target.
     *
     * @param from the starting height
     * @param to the target height
     * @return the height of the first gap, or the target height if no gap
     */
    ULong firstGap(ULong from, ULong to);

    /**
     * Performs garbage collection on blocks from a starting height down to a target height.
     * Retains reconfiguration blocks.
     *
     * @param from the starting height
     * @param to the target height
     */
    void gcFrom(ULong from, ULong to);

    /**
     * Retrieves a block with its hash at the specified height.
     *
     * @param height the block height
     * @return the hashed block, or null if not found
     */
    HashedBlock getBlock(ULong height);

    /**
     * Retrieves the raw serialized block bytes at the specified height.
     *
     * @param height the block height
     * @return the block bytes, or null if not found
     */
    byte[] getBlockBits(ULong height);

    /**
     * Retrieves a certified block (block + certifications) at the specified height.
     *
     * @param height the block height
     * @return the certified block, or null if not found
     */
    CertifiedBlock getCertifiedBlock(ULong height);

    /**
     * Returns the abstracted checkpoint segment map for the specified height.
     * Implementations should wrap their native storage (e.g., MVMap) with the
     * CheckpointSegmentMap interface.
     *
     * @param height the checkpoint block height
     * @return checkpoint segment map abstraction
     */
    CheckpointSegmentMap getCheckpointSegments(ULong height);

    /**
     * Retrieves the last stored block.
     *
     * @return the last hashed certified block, or null if empty
     */
    HashedCertifiedBlock getLastBlock();

    /**
     * Retrieves the last view (checkpoint) block.
     *
     * @return the last view block
     */
    HashedCertifiedBlock getLastView();

    /**
     * Retrieves the hash digest for a block at the specified height.
     *
     * @param height the block height
     * @return the block hash, or null if not found
     */
    Digest hash(ULong height);

    /**
     * Returns the complete map of block heights to hash digests.
     *
     * @return map of heights to hashes
     */
    Map<ULong, Digest> hashes();

    /**
     * Finds the last height in the view chain from a starting height.
     *
     * @param height the starting height
     * @return the last height in the view chain
     */
    ULong lastViewChainFrom(ULong height);

    /**
     * Stores a hashed certified block.
     *
     * @param cb the hashed certified block to store
     */
    void put(HashedCertifiedBlock cb);

    /**
     * Stores a checkpoint state file as segments in the checkpoint map.
     *
     * @param blockHeight the checkpoint block height
     * @param state the checkpoint state file
     * @param checkpoint the checkpoint metadata
     * @return the checkpoint segment map
     */
    MVMap<Integer, byte[]> putCheckpoint(ULong blockHeight, File state, Checkpoint checkpoint);

    /**
     * Rolls back storage to a specific version.
     *
     * @param version the target version
     */
    void rollbackTo(long version);

    /**
     * Validates the block chain integrity from a starting height to a target height.
     *
     * @param from the starting height
     * @param to the target height
     * @throws IllegalStateException if validation fails
     */
    void validate(ULong from, ULong to) throws IllegalStateException;

    /**
     * Validates the view chain (reconfiguration chain) from a starting height to genesis.
     *
     * @param from the starting height
     * @throws IllegalStateException if validation fails
     */
    void validateViewChain(ULong from) throws IllegalStateException;

    /**
     * Validates the checkpoint chain from a starting block.
     * Ensures that lastCheckpointHash correctly references valid checkpoint blocks.
     * <p>
     * During bootstrap, prior checkpoints may not be present; this method validates
     * available checkpoint references without requiring the full historical chain.
     *
     * @param from the starting block height
     * @throws IllegalStateException if validation fails
     */
    void validateCheckpointChain(ULong from) throws IllegalStateException;

    /**
     * Returns the current storage version.
     *
     * @return the storage version
     */
    long version();

    /**
     * Returns an iterator over the view chain from a starting height down to a target.
     *
     * @param from the starting height
     * @param to the target height
     * @return iterator over view chain heights
     */
    Iterator<ULong> viewChainFrom(ULong from, ULong to);
}
