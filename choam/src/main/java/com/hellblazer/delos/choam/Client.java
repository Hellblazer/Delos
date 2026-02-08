/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.Verifier;
import com.hellblazer.delos.membership.Member;

import java.util.Map;

/**
 * A client member of the current committee without block production capabilities.
 * Clients participate in validation but do not produce blocks.
 * <p>
 * Extracted from CHOAM.java (Phase 3.2).
 * </p>
 * <p>
 * Note: This class exists primarily as a type tag to distinguish client committee
 * members from associate members. A future improvement could replace this with a
 * boolean flag or method on the Committee interface.
 * </p>
 *
 * @author hal.hildebrand
 */
class Client extends Administration {

    /**
     * Construct a Client committee member.
     *
     * @param choam      CHOAM coordinator instance
     * @param validators committee member validators
     * @param viewId     view identifier
     */
    public Client(CHOAM choam, Map<Member, Verifier> validators, Digest viewId) {
        super(choam, validators, viewId);
    }
}
