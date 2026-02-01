/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.membership.byzantine;

import com.hellblazer.delos.stereotomy.identifier.Identifier;

import java.util.concurrent.CompletableFuture;

/**
 * Handler for Byzantine detection response actions.
 * <p>
 * Implementations bridge the ByzantineIntelligenceCoordinator to layer-specific
 * response mechanisms (e.g., Fireflies shunning, KERI key rotation).
 * </p>
 * <p>
 * <b>Fix 1 (v3.2): Async API</b><br>
 * Uses CompletableFuture to match actual infrastructure APIs. The Fireflies
 * shunning API is {@code FirefliesViewAdapter.shunMember(Identifier) -> CompletableFuture<Void>},
 * so this interface follows the same pattern.
 * </p>
 * <p>
 * <b>Implementation Notes</b>:
 * <ul>
 *   <li>Implementations should be thread-safe for concurrent calls</li>
 *   <li>Long-running operations should not block the calling thread</li>
 *   <li>Failures should be reported via exceptionally(), not thrown</li>
 *   <li>Metrics recording should happen within the async chain</li>
 * </ul>
 * </p>
 *
 * @author hal.hildebrand
 * @see MemberRiskProfile
 */
public interface ResponseHandler {

    /**
     * Handle critical Byzantine detection (score >= critical threshold).
     * <p>
     * Called when a member's aggregated risk profile exceeds the critical
     * threshold. Implementations should trigger immediate protective action
     * such as shunning or quarantine.
     * </p>
     * <p>
     * <b>Idempotence</b>: Implementations SHOULD be idempotent. If the same
     * member is reported multiple times, subsequent calls should return
     * {@code false} (no action taken) rather than failing or re-applying
     * the action.
     * </p>
     * <p>
     * The returned future completes with:
     * <ul>
     *   <li>{@code true} if action was successfully taken</li>
     *   <li>{@code false} if action was skipped (e.g., already shunned)</li>
     *   <li>Exceptionally if the action failed</li>
     * </ul>
     * </p>
     *
     * @param memberId Member exhibiting critical Byzantine behavior
     * @param profile  Aggregated risk profile with cross-layer signals
     * @return Future completing with true if action taken, false if skipped
     */
    CompletableFuture<Boolean> handleCritical(Identifier memberId, MemberRiskProfile profile);

    /**
     * Handle warning-level Byzantine detection (score >= warning threshold).
     * <p>
     * Called when a member's aggregated risk profile exceeds the warning
     * threshold but not the critical threshold. Implementations should
     * log, alert, or increase monitoring, but typically not take action.
     * </p>
     *
     * @param memberId Member exhibiting suspicious behavior
     * @param profile  Aggregated risk profile with cross-layer signals
     * @return Future completing when warning is processed
     */
    CompletableFuture<Void> handleWarning(Identifier memberId, MemberRiskProfile profile);

    /**
     * No-op response handler for testing or disabled detection.
     *
     * @return Handler that logs but takes no action
     */
    static ResponseHandler noOp() {
        return new ResponseHandler() {
            @Override
            public CompletableFuture<Boolean> handleCritical(Identifier memberId, MemberRiskProfile profile) {
                return CompletableFuture.completedFuture(false);
            }

            @Override
            public CompletableFuture<Void> handleWarning(Identifier memberId, MemberRiskProfile profile) {
                return CompletableFuture.completedFuture(null);
            }
        };
    }
}
