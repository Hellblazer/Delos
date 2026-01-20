/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.ethereal.memberships.comm;

import com.hellblazer.delos.ethereal.proto.ContextUpdate;
import com.hellblazer.delos.ethereal.proto.Gossip;
import com.hellblazer.delos.ethereal.proto.Update;
import com.hellblazer.delos.cryptography.Digest;

/**
 * @author hal.hildebrand
 */
public interface GossiperService {

    Update gossip(Gossip request, Digest from);

    void update(ContextUpdate request, Digest from);

}
