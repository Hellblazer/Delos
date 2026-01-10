/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.choam.proto.Checkpoint;
import com.hellblazer.delos.choam.support.CheckpointState;
import com.hellblazer.delos.choam.support.HashedCertifiedBlock;
import org.joou.ULong;

import java.io.File;

/**
 * Manages checkpoint state, creation, and recovery operations for the CHOAM consensus protocol.
 * <p>
 * Checkpoints provide snapshots of replicated state at specific block heights, enabling:
 * - Efficient state transfer during synchronization
 * - Garbage collection of old blocks
 * - Fast recovery from failures
 * <p>
 * This component is responsible for:
 * - Tracking the current checkpoint reference
 * - Creating new checkpoints from application state
 * - Assembling checkpoint segments for replication
 * - Restoring state from checkpoint data
 * <p>
 * Thread Safety: Implementations must be thread-safe for concurrent checkpoint queries
 * and creation operations.
 *
 * @author hal.hildebrand
 */
public interface CheckpointManager {

    /**
     * Returns the height of the last committed checkpoint.
     *
     * @return the height of the last checkpoint, or null if no checkpoint exists
     */
    ULong lastCheckpoint();

    /**
     * Retrieves the checkpoint at the specified height.
     *
     * @param height the block height of the checkpoint
     * @return the checkpoint at the given height, or null if not found
     */
    Checkpoint getCheckpoint(ULong height);

    /**
     * Creates a new checkpoint from the provided state file at the given height.
     * <p>
     * This operation:
     * 1. Segments the state file according to configured segment size
     * 2. Generates a HexBloom crown for segment validation
     * 3. Stores the checkpoint and segments in the block store
     * 4. Updates the current checkpoint reference
     * <p>
     * This method is typically called by the consensus protocol when checkpoint conditions
     * are met (e.g., after N blocks have been processed).
     *
     * @param height the block height for this checkpoint
     * @param state  the state file to checkpoint (deleted after successful checkpoint)
     */
    void createCheckpoint(ULong height, File state);

    /**
     * Creates a checkpoint assembler for replicating checkpoint segments to other nodes.
     * <p>
     * The assembler is used during synchronization to efficiently transfer checkpoint
     * data in segments, with validation via Bloom filters.
     *
     * @param height the checkpoint height to assemble
     * @return a CheckpointAssembler for the specified checkpoint, or null if not found
     */
    CheckpointState getCheckpointState(ULong height);

    /**
     * Restores system state from a checkpoint at the specified height.
     * <p>
     * This operation:
     * 1. Retrieves the checkpoint and its segments from the block store
     * 2. Reconstructs the state file from segments
     * 3. Invokes the application's restore callback
     * 4. Updates internal checkpoint references
     * <p>
     * This method is called during recovery and synchronization operations.
     *
     * @param checkpoint the checkpoint block containing checkpoint metadata
     * @param state      the checkpoint state including segments
     */
    void restoreFromCheckpoint(HashedCertifiedBlock checkpoint, CheckpointState state);

    /**
     * Returns the current checkpoint block.
     *
     * @return the current checkpoint block
     */
    HashedCertifiedBlock currentCheckpoint();
}
