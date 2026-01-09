/*
 * Copyright (c) 2025, Hal Hildebrand. All rights reserved.
 *
 * This software is licensed under the AFFERO GENERAL PUBLIC LICENSE VERSION 3.
 * See the LICENSE file at the root of this project or at https://www.gnu.org/licenses/agpl-3.0.en.html
 */

package com.hellblazer.delos.choam;

import com.hellblazer.delos.choam.fsm.Combine;
import com.hellblazer.delos.choam.support.HashedCertifiedBlock;
import com.hellblazer.delos.context.DelegatedContext;
import com.hellblazer.delos.context.ViewChange;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.membership.Member;
import org.joou.ULong;

/**
 * ConsensusEngine defines the core consensus operations for Byzantine fault-tolerant state machine replication.
 * <p>
 * This interface abstracts consensus protocol operations from lifecycle management and communication concerns,
 * enabling alternative consensus implementations while maintaining compatibility with the existing CHOAM
 * infrastructure.
 * </p>
 *
 * <h2>Design Rationale</h2>
 * <p>
 * The ConsensusEngine interface extracts consensus-specific operations that are independent of:
 * <ul>
 * <li>Lifecycle management (start/stop)</li>
 * <li>Internal FSM transitions (kept as implementation details)</li>
 * <li>Committee-specific logic (separate Committee interface)</li>
 * </ul>
 * </p>
 *
 * <h2>Implementation Notes</h2>
 * <p>
 * CHOAM is the reference implementation of this interface. Alternative consensus protocols (e.g., Raft, Paxos)
 * can implement this interface to provide drop-in replacements for specific deployment scenarios while preserving
 * the deterministic execution guarantees required for Byzantine fault tolerance.
 * </p>
 *
 * <h2>FSM State Exposure</h2>
 * <p>
 * The {@link #getCurrentState()} method exposes internal FSM state and is marked {@link Deprecated} for future
 * refinement. Alternative implementations may ignore this method or provide simplified state representations.
 * Phase 2 consensus refactoring will address this design decision based on concrete alternative implementations.
 * </p>
 *
 * @author hal.hildebrand
 */
public interface ConsensusEngine {

    /**
     * Check if the consensus engine is currently operational and able to process transactions.
     * <p>
     * An engine is considered active when it has:
     * <ul>
     * <li>A valid committee membership</li>
     * <li>A head block (current tip of the chain)</li>
     * <li>FSM in OPERATIONAL state</li>
     * <li>Valid administration context</li>
     * </ul>
     * </p>
     *
     * @return true if the engine is active and operational, false otherwise
     */
    boolean active();

    /**
     * Get the member context for consensus operations.
     * <p>
     * The context provides membership information and communication routing for the distributed consensus protocol.
     * </p>
     *
     * @return the delegated context for member operations
     */
    DelegatedContext<Member> context();

    /**
     * Get the current block height in the replicated log.
     * <p>
     * The height represents the number of committed blocks in the chain. A null return indicates the engine
     * has not yet processed any blocks (uninitialized state).
     * </p>
     *
     * @return the current height, or null if no blocks have been committed
     */
    ULong currentHeight();

    /**
     * Get the current FSM state of the consensus engine.
     * <p>
     * <strong>DEPRECATED:</strong> This method exposes internal FSM state and is marked for removal in version 2.0.
     * The FSM (Finite State Machine) is a CHOAM-specific implementation detail. Alternative consensus engines may not
     * use FSM-based state management.
     * </p>
     * <p>
     * This method exists to acknowledge that CHOAM currently exposes FSM state publicly. Phase 2 consensus refactoring
     * will refine this interface to provide more abstract state queries suitable for multiple consensus implementations.
     * </p>
     *
     * @return the current FSM state transitions object
     * @deprecated FSM state exposure. Marked for refinement in Phase 2 consensus refactoring.
     */
    @Deprecated(forRemoval = true, since = "2.0")
    Combine.Transitions getCurrentState();

    /**
     * Get the unique identifier of this consensus engine instance.
     * <p>
     * The identifier is typically the member ID from the underlying membership context.
     * </p>
     *
     * @return the digest representing this engine's unique identifier
     */
    Digest getId();

    /**
     * Get the session interface for transaction submission and queries.
     * <p>
     * The session provides the client-facing API for interacting with the replicated state machine.
     * </p>
     *
     * @return the session interface for this consensus engine
     */
    Session getSession();

    /**
     * Get the current view identifier.
     * <p>
     * The view ID changes during reconfiguration events (membership changes, view rotations). A null return
     * indicates the engine has not yet established a view.
     * </p>
     *
     * @return the current view digest, or null if no view is established
     */
    Digest getViewId();

    /**
     * Rotate view keys after a view change event.
     * <p>
     * This method updates cryptographic keys and context after membership reconfiguration. It must be called
     * when a ViewChange consensus decision is committed to ensure proper MTLS certificate rotation and
     * communication context updates.
     * </p>
     *
     * @param viewChange the view change event containing new context and diadem (view keys)
     */
    void rotateViewKeys(ViewChange viewChange);

    /**
     * Accept and commit the next certified block in the replicated log.
     * <p>
     * This is the primary entry point for advancing the consensus state machine. The block must be certified
     * (have sufficient signatures from committee members) before being accepted.
     * </p>
     * <p>
     * <strong>Visibility Note:</strong> This method was promoted from private to public as part of the ConsensusEngine
     * interface extraction. It represents a legitimate consensus entry point for block acceptance.
     * </p>
     *
     * @param next the certified block to accept and commit
     */
    void accept(HashedCertifiedBlock next);

    /**
     * Recover consensus state from a given anchor block.
     * <p>
     * This method initiates the synchronization protocol to catch up with the network from a known checkpoint.
     * It is used when a node falls behind or restarts and needs to rebuild state from a trusted anchor block.
     * </p>
     * <p>
     * <strong>Visibility Note:</strong> This method was promoted from private to public as part of the ConsensusEngine
     * interface extraction. It represents a legitimate synchronization entry point.
     * </p>
     *
     * @param anchor the certified block to use as the recovery anchor point
     */
    void recover(HashedCertifiedBlock anchor);

    /**
     * Restore consensus state from persistent storage.
     * <p>
     * This method rebuilds the consensus engine's in-memory state from the block store after a restart. It is
     * called during engine initialization to restore the last known committed state.
     * </p>
     * <p>
     * <strong>Visibility Note:</strong> This method was promoted from private to public as part of the ConsensusEngine
     * interface extraction. It represents a legitimate bootstrap entry point.
     * </p>
     *
     * @throws IllegalStateException if restoration fails due to corrupted state or missing blocks
     */
    void restore() throws IllegalStateException;
}
