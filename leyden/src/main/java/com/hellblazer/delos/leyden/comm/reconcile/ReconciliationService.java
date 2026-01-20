/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.leyden.comm.reconcile;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.leyden.proto.Intervals;
import com.hellblazer.delos.leyden.proto.Update;
import com.hellblazer.delos.leyden.proto.Updating;

/**
 * @author hal.hildebrand
 **/
public interface ReconciliationService {
    Update reconcile(Intervals request, Digest from);

    void update(Updating request, Digest from);
}
