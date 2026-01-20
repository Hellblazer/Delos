/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.validation;

import com.hellblazer.delos.stereotomy.identifier.Identifier;

import java.util.concurrent.CompletableFuture;

/**
 * Integration interface for Fireflies shunning mechanism.
 * <p>
 * Provides callback methods for Byzantine witness detector to coordinate
 * with Fireflies membership layer for Byzantine member shunning.
 * </p>
 * <p>
 * Implementation responsibilities:
 * - Communicate shunning decisions to Fireflies gossip layer
 * - Track shunned member state across witness committee
 * - Handle asynchronous shunning operations
 * </p>
 */
public interface FirefliesShunningIntegration {

    /**
     * Mark a member for shunning in Fireflies membership layer.
     * <p>
     * This method triggers Fireflies gossip protocol to propagate
     * shunning decision across witness committee.
     * </p>
     *
     * @param memberId Member identifier to shun
     * @return CompletableFuture completing when shunning is propagated
     */
    CompletableFuture<Void> markMemberForShunning(Identifier memberId);

    /**
     * Check if a member is currently shunned.
     * <p>
     * Used to query current shunning state before processing receipts.
     * </p>
     *
     * @param memberId Member identifier to check
     * @return true if member is shunned, false otherwise
     */
    boolean isShunned(Identifier memberId);
}
