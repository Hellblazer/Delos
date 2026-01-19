/*
 * Copyright (c) 2024, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.witness;

import com.hellblazer.delos.choam.ViewCoordinator;
import com.hellblazer.delos.choam.ViewState;
import com.hellblazer.delos.choam.proto.Reconfigure;
import com.hellblazer.delos.choam.support.HashedCertifiedBlock;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WitnessViewAdapter: Adapter between CHOAM ViewCoordinator and witness service.
 * <p>
 * Implements CHOAM's two-phase reconfigure pattern to integrate view changes
 * from CHOAM consensus into witness receipt collection state.
 * <p>
 * TWO-PHASE PATTERN INTEGRATION:
 * <p>
 * Phase 1 (LOCKED): prepareReconfigure() - Called while CHOAM holds viewStateLock
 * - Computes new witness state deterministically
 * - No side effects, no callbacks executed
 * - Returns prepared state with callbacks for Phase 2
 * <p>
 * Phase 2 (UNLOCKED): completeReconfigure() - Called AFTER CHOAM releases viewStateLock
 * - Applies state transition atomically
 * - Executes callbacks to update witness state
 * - Safe for callbacks to acquire witness internal locks
 * <p>
 * LOCK ORDERING CONSTRAINTS (AUDIT CONDITION):
 * <p>
 * CRITICAL: This adapter ensures deadlock-free integration by enforcing strict lock ordering:
 * <p>
 * 1. CHOAM viewStateLock: Held ONLY during prepareReconfigure()
 * 2. Witness internal locks: Acquired ONLY in callbacks (after CHOAM releases viewStateLock)
 * 3. Execution order: CHOAM completeReconfigure() → witness callback → witness state update
 * 4. No reentrancy: Witness callbacks NEVER call CHOAM methods that acquire viewStateLock
 * <p>
 * DEADLOCK PREVENTION:
 * <pre>
 * Thread 1 (CHOAM reconfigure):
 *   1. Acquire CHOAM viewStateLock
 *   2. Call prepareReconfigure() (deterministic computation)
 *   3. Release CHOAM viewStateLock
 *   4. Call completeReconfigure() (execute callbacks)
 *   5. Callbacks acquire witness locks (safe: CHOAM lock released)
 *
 * Thread 2 (Witness operations):
 *   1. Acquire witness internal locks (viewLock, drainLock)
 *   2. Update witness state
 *   3. Release witness locks
 *   4. NEVER attempts to acquire CHOAM viewStateLock
 * </pre>
 * <p>
 * INVARIANTS:
 * - prepareReconfigure() is deterministic (same inputs → same outputs)
 * - completeReconfigure() is called WITHOUT CHOAM viewStateLock
 * - Callbacks execute WITHOUT CHOAM viewStateLock
 * - Callbacks may safely acquire witness internal locks
 * - No callback reentrancy into CHOAM locked methods
 * <p>
 * Architecture reference: /Users/hal.hildebrand/git/Delos/.pm/designs/phase1a3/PHASE_1A3_ARCHITECTURE.md
 * Section: Phase A - Task A.2
 *
 * @author hal.hildebrand
 */
public class WitnessViewAdapter implements ViewCoordinator {

    private static final Logger log = LoggerFactory.getLogger(WitnessViewAdapter.class);

    private final WitnessCHOAM witnessCHOAM;
    private final WitnessContext witnessContext;
    private final DigestAlgorithm digestAlgorithm;

    /**
     * Create adapter between CHOAM ViewCoordinator and witness service.
     *
     * @param witnessCHOAM    Witness CHOAM state machine
     * @param witnessContext  Witness context for epoch and member tracking
     * @param digestAlgorithm Algorithm for digest operations
     */
    public WitnessViewAdapter(WitnessCHOAM witnessCHOAM,
                             WitnessContext witnessContext,
                             DigestAlgorithm digestAlgorithm) {
        this.witnessCHOAM = witnessCHOAM;
        this.witnessContext = witnessContext;
        this.digestAlgorithm = digestAlgorithm;
    }

    /**
     * Phase 1 (LOCKED): Prepare reconfiguration deterministically.
     * <p>
     * CRITICAL: This method is called while CHOAM holds viewStateLock.
     * It MUST be deterministic and have NO side effects.
     * <p>
     * LOCK ORDERING: CHOAM viewStateLock is HELD during this call.
     * Witness locks are NOT acquired here.
     *
     * @param snapshot    Current view state snapshot
     * @param hash        View reconfiguration hash
     * @param reconfigure Reconfiguration message
     * @return Prepared state with callbacks for Phase 2
     */
    @Override
    public ReconfigurationPrepared prepareReconfigure(ViewState.Snapshot snapshot,
                                                     Digest hash,
                                                     Reconfigure reconfigure) {
        log.debug("Preparing reconfiguration: hash={}, viewId={}", hash, snapshot.getViewId());

        // Deterministic computation: compute new state without side effects
        // Store callback for execution in Phase 2 (UNLOCKED)
        return new WitnessReconfigurationPrepared(snapshot, hash, reconfigure);
    }

    /**
     * Phase 2 (UNLOCKED): Complete reconfiguration and execute callbacks.
     * <p>
     * CRITICAL: This method is called AFTER CHOAM releases viewStateLock.
     * Callbacks may safely acquire witness internal locks.
     * <p>
     * LOCK ORDERING: CHOAM viewStateLock is NOT held during this call.
     * Witness locks may be acquired in callbacks.
     *
     * @param prepared Prepared state from prepareReconfigure()
     */
    @Override
    public void completeReconfigure(ReconfigurationPrepared prepared) {
        log.debug("Completing reconfiguration");

        // Execute callbacks WITHOUT holding CHOAM viewStateLock
        // This is safe: callbacks may acquire witness internal locks
        prepared.executeCallbacks();

        log.debug("Reconfiguration completed");
    }

    /**
     * Prepared reconfiguration state with witness callbacks.
     * <p>
     * Encapsulates state transition data and callbacks to execute in Phase 2.
     * Callbacks are deterministically computed in prepareReconfigure() and
     * executed in completeReconfigure() WITHOUT holding CHOAM viewStateLock.
     */
    private class WitnessReconfigurationPrepared implements ReconfigurationPrepared {

        private final ViewState.Snapshot snapshot;
        private final Digest hash;
        private final Reconfigure reconfigure;

        WitnessReconfigurationPrepared(ViewState.Snapshot snapshot,
                                      Digest hash,
                                      Reconfigure reconfigure) {
            this.snapshot = snapshot;
            this.hash = hash;
            this.reconfigure = reconfigure;
        }

        /**
         * Execute callbacks (called in Phase 2 WITHOUT CHOAM viewStateLock).
         * <p>
         * LOCK ORDERING: CHOAM viewStateLock is NOT held.
         * Callbacks may safely acquire witness internal locks (viewLock, drainLock).
         */
        @Override
        public void executeCallbacks() {
            log.debug("Executing witness view change callbacks");

            // Create HashedCertifiedBlock from reconfigure message
            var block = createViewBlock();

            // Execute witness callback (may acquire witness internal locks)
            // SAFE: CHOAM viewStateLock is released before this call
            witnessCHOAM.onViewChange(block);

            log.debug("Witness view change callbacks executed");
        }

        /**
         * Get prepared snapshot (for testing/validation).
         */
        @Override
        public ViewState.Snapshot getPreparedSnapshot() {
            return snapshot;
        }

        /**
         * Create HashedCertifiedBlock for witness view change.
         * <p>
         * Uses reconfigure message to construct block with height and hash.
         */
        private HashedCertifiedBlock createViewBlock() {
            // Extract height from reconfigure message (checkpoint target as height proxy)
            long height = reconfigure.getCheckpointTarget();

            // Create minimal proto block structure
            var header = com.hellblazer.delos.choam.proto.Header.newBuilder()
                .setHeight(height)
                .build();

            var block = com.hellblazer.delos.choam.proto.Block.newBuilder()
                .setHeader(header)
                .build();

            var certifiedBlock = com.hellblazer.delos.choam.proto.CertifiedBlock.newBuilder()
                .setBlock(block)
                .build();

            return new HashedCertifiedBlock(digestAlgorithm, certifiedBlock);
        }
    }
}
