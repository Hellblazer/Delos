/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.ethereal;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.ethereal.proto.Gossip;
import com.hellblazer.delos.ethereal.proto.Update;

/**
 * @author hal.hildebrand
 */
public interface Processor {

    /**
     * First phase request. Answer the gossip for the current state of the receiver
     *
     * @param context - the digest id of the context for routing
     * @return the Gossip
     */
    Gossip gossip(Digest context);

    /**
     * First phase reply. Answer the Update from the receiver's state, based on the suppled Have
     *
     * @param gossip - the state contained by the partner
     * @return the Update based on the current state of the receiver and the have state of the partner
     */
    Update gossip(Gossip gossip);

    /**
     * Second phase, update the receiver state from the supplied update. Return an update based on the current state and
     * the haves of the supplied update
     *
     * @param update - the Update from the partner
     * @return the Update from the current state of the receiver and the have state of the supplied update
     */
    Update update(Update update);

    /**
     * Final phase; update the commit, prevote and unit state from the supplied update
     *
     * @param update - the Update from our partner
     */
    void updateFrom(Update update);
}
