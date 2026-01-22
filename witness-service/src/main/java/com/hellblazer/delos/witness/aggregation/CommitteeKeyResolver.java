/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.witness.aggregation;

import com.hellblazer.delos.cryptography.bls.BLSPublicKey;

import java.util.List;

/**
 * Resolves committee public keys by epoch.
 * <p>
 * Decouples validation from WitnessContext, enabling:
 * - Unit testing with mock resolvers
 * - Caching strategies
 * - Alternative key sources
 * <p>
 * Phase 1C-2-B: Multi-committee aggregate validation support.
 *
 * @author hal.hildebrand
 */
@FunctionalInterface
public interface CommitteeKeyResolver {

    /**
     * Resolve committee public keys by epoch.
     *
     * @param epoch Fireflies epoch identifying the committee
     * @return List of committee member public keys (ordered by member index)
     * or null if epoch unknown
     */
    List<BLSPublicKey> getCommitteeKeys(long epoch);
}
