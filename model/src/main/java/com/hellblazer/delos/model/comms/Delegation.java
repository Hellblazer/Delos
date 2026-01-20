/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.model.comms;

import com.google.common.util.concurrent.ListenableFuture;
import com.hellblazer.delos.demesne.proto.DelegationUpdate;
import com.hellblazer.delos.cryptography.proto.Biff;
import com.hellblazer.delos.archipelago.Link;

/**
 * @author hal.hildebrand
 */
public interface Delegation extends Link {

    DelegationUpdate gossip(Biff identifers);

    void update(DelegationUpdate update);

}
