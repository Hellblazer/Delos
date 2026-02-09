/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.choam.support.HashedCertifiedBlock;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.Verifier;
import com.hellblazer.delos.membership.Member;

import java.util.Map;

/**
 * Callback interface for committee construction during view reconfiguration.
 * Abstracts committee creation so ReconfigurationCoordinator doesn't depend on CHOAM.
 * <p>
 * Extracted from CHOAM.java (Phase 3.3).
 * </p>
 *
 * @author hal.hildebrand
 */
public interface ReconfigurationCallbacks {

    /**
     * Create an Associate committee member (block-producing).
     *
     * @param viewChange certified block triggering the view change
     * @param validators committee member validators
     * @param nextView   the next view configuration
     * @return new Associate committee instance
     */
    Committee createAssociate(HashedCertifiedBlock viewChange, Map<Member, Verifier> validators, NextView nextView);

    /**
     * Create a Client committee member (non-producing).
     *
     * @param validators committee member validators
     * @param viewId     view identifier
     * @return new Client committee instance
     */
    Committee createClient(Map<Member, Verifier> validators, Digest viewId);
}
