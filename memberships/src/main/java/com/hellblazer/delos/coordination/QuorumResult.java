/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.coordination;

/**
 * Result of a successful BFT quorum operation.
 *
 * @param result           The collected result (Set for collectQuorum, single element for voteQuorum)
 * @param responseCount    Number of successful responses received
 * @param requiredMajority The majority threshold that was met
 * @param <T>              Type of the result
 * @author hal.hildebrand
 */
public record QuorumResult<T>(
    T result,
    int responseCount,
    int requiredMajority
) {
}
