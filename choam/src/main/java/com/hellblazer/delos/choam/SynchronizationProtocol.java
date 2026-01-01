/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam;

import com.google.protobuf.InvalidProtocolBufferException;
import com.hellblazer.delos.choam.proto.CertifiedBlock;
import com.hellblazer.delos.choam.support.Bootstrapper.SynchronizedState;
import com.hellblazer.delos.choam.support.CheckpointState;
import com.hellblazer.delos.choam.support.HashedCertifiedBlock;

/**
 * Protocol for synchronizing CHOAM state including bootstrap, recovery, and restoration.
 * Manages the lifecycle of synchronization operations and coordinates with the FSM.
 *
 * @author hal.hildebrand
 */
public interface SynchronizationProtocol {

    /**
     * Wait for synchronization to complete. Called when entering RECOVERING state.
     * Schedules periodic attempts to find an anchor block for synchronization.
     */
    void awaitSynchronization();

    /**
     * Wait for genesis regeneration. Called when entering AWAITING_REGENERATION state.
     * Schedules periodic attempts to discover genesis block.
     */
    void awaitRegeneration();

    /**
     * Cancel any ongoing bootstrap operation.
     */
    void cancelBootstrap();

    /**
     * Cancel any ongoing synchronization operation.
     */
    void cancelSynchronization();

    /**
     * Recover from the given anchor block by bootstrapping the system state.
     * Creates a Bootstrapper and initiates the synchronization process.
     *
     * @param anchor the anchor block to recover from
     */
    void recover(HashedCertifiedBlock anchor);

    /**
     * Restore state from the local store. Loads the last block, genesis, and checkpoint
     * from persistent storage and configures the synchronizer committee.
     *
     * @throws IllegalStateException if restoration fails
     */
    void restore() throws IllegalStateException;

    /**
     * Restore from a specific checkpoint by applying it and then calling restore().
     *
     * @param block      the checkpoint block
     * @param checkpoint the checkpoint state data
     */
    void restoreFrom(HashedCertifiedBlock block, CheckpointState checkpoint);

    /**
     * Synchronize using the provided synchronized state. Processes blocks from genesis
     * or checkpoint up to the current head.
     *
     * @param state the synchronized state containing genesis, checkpoint, and view information
     */
    void synchronize(SynchronizedState state);

    /**
     * Process a certified block during synchronization. Validates and queues the block
     * for processing.
     *
     * @param certifiedBlock the block to process
     * @throws InvalidProtocolBufferException if block parsing fails
     */
    void synchronizedProcess(CertifiedBlock certifiedBlock) throws InvalidProtocolBufferException;

    /**
     * Handle synchronization failure. Determines whether to await synchronization or
     * regenerate genesis based on quorum availability.
     */
    void synchronizationFailed();
}
