/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.choam.proto.Reconfigure;
import com.hellblazer.delos.choam.proto.SignedViewMember;
import com.hellblazer.delos.choam.support.HashedCertifiedBlock;
import com.hellblazer.delos.cryptography.Digest;

/**
 * Coordinates consensus state across committee transitions and view changes.
 * Manages the lifecycle of Committee instances and handles the state machine
 * transitions between different committee types (Formation, Associate, Client, Synchronizer).
 *
 * This component encapsulates consensus coordination logic that was previously
 * embedded in CHOAM, providing a clean separation between committee-level
 * consensus coordination and the overall CHOAM protocol flow.
 *
 * @author hal.hildebrand
 */
public interface ConsensusCoordinator {

    /**
     * Get the current committee instance.
     *
     * @return the current Committee, or null if no committee is active
     */
    Committee getCurrentCommittee();

    /**
     * Set the current committee instance.
     *
     * @param committee the new Committee to activate
     */
    void setCommittee(Committee committee);

    /**
     * Process a view reconfiguration, transitioning to a new committee.
     * Creates either an Associate or Client committee based on whether this
     * member is a validator in the new view.
     *
     * @param hash the hash identifying this reconfiguration
     * @param reconfigure the reconfiguration details including new validators
     * @param head the current head block
     */
    void reconfigure(Digest hash, Reconfigure reconfigure, HashedCertifiedBlock head);

    /**
     * Handle a join request from a member attempting to join a view.
     * Delegates to the current committee if one exists.
     *
     * @param nextView the signed view member attempting to join
     * @param from the digest of the member making the request
     */
    void join(SignedViewMember nextView, Digest from);

    /**
     * Generate and store new consensus keys for the next view.
     * This rotates the cryptographic keys used for consensus participation.
     */
    void rotateViewKeys();

    /**
     * Get the next view ID that will be used in the upcoming reconfiguration.
     *
     * @return the Digest identifying the next view, or null if not set
     */
    Digest getNextViewId();

    /**
     * Set the next view ID for an upcoming reconfiguration.
     *
     * @param id the Digest identifying the next view
     */
    void setNextViewId(Digest id);

    /**
     * Get the current view ID.
     *
     * @return the Digest of the current view, or null if no view is active
     */
    Digest getViewId();

    /**
     * Get the current view change block.
     *
     * @return the HashedCertifiedBlock representing the current view
     */
    HashedCertifiedBlock getView();

    /**
     * Set the current view change block.
     *
     * @param view the HashedCertifiedBlock representing the new view
     */
    void setView(HashedCertifiedBlock view);

    /**
     * Get the next view information including consensus keys.
     *
     * @return the nextView record containing member info and consensus key pair
     */
    CHOAM.nextView getNextView();

    /**
     * Set the transitions FSM reference.
     * Must be called during initialization before reconfigure() is used.
     *
     * @param transitions the FSM transitions instance
     */
    void setTransitions(com.hellblazer.delos.choam.fsm.Combine.Transitions transitions);

    /**
     * Set the session reference.
     * Must be called during initialization before reconfigure() is used.
     *
     * @param session the Session instance
     */
    void setSession(Session session);
}
