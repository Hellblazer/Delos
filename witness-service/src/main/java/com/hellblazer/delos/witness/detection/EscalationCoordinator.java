/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.detection;

import com.hellblazer.delos.stereotomy.identifier.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinates escalations across key rotation and view changes.
 * <p>
 * Prevents:
 * - Duplicate escalations (same member, same action)
 * - Concurrent escalations (key rotation and view change for same member)
 * - Escalation storms (too many concurrent escalations)
 * </p>
 * <p>
 * Thread-safe: uses concurrent collections for coordination.
 * </p>
 *
 * @author hal.hildebrand
 */
public class EscalationCoordinator {

    private static final Logger log = LoggerFactory.getLogger(EscalationCoordinator.class);

    private final KeyRotationEscalation keyRotationEscalation;
    private final ViewChangeEscalation viewChangeEscalation;
    private final Map<Identifier, ResponseAction> activeEscalations;

    public EscalationCoordinator(
        KeyRotationEscalation keyRotationEscalation,
        ViewChangeEscalation viewChangeEscalation
    ) {
        this.keyRotationEscalation = Objects.requireNonNull(keyRotationEscalation,
                                                             "keyRotationEscalation cannot be null");
        this.viewChangeEscalation = Objects.requireNonNull(viewChangeEscalation,
                                                            "viewChangeEscalation cannot be null");
        this.activeEscalations = new ConcurrentHashMap<>();
    }

    /**
     * Request key rotation for member.
     * <p>
     * Checks for existing escalations before proceeding.
     * Prevents concurrent key rotation and view change for same member.
     * </p>
     *
     * @param memberId Member whose keys should be rotated
     * @param reason   Reason for rotation
     * @return Future completing with rotation result
     */
    public CompletableFuture<KeyRotationEscalation.KeyRotationResult> requestKeyRotation(
        Identifier memberId,
        String reason
    ) {
        Objects.requireNonNull(memberId, "memberId cannot be null");
        Objects.requireNonNull(reason, "reason cannot be null");

        // Check for existing escalation
        var existing = activeEscalations.putIfAbsent(memberId, ResponseAction.REQUEST_KEY_ROTATION);
        if (existing != null) {
            log.info("Escalation already active for {}: {}, skipping key rotation request", memberId, existing);
            return CompletableFuture.completedFuture(
                new KeyRotationEscalation.KeyRotationResult(
                    memberId,
                    false,
                    "Existing escalation: " + existing
                )
            );
        }

        log.info("Coordinating key rotation for {}: {}", memberId, reason);

        return keyRotationEscalation.requestRotation(memberId, reason)
            .whenComplete((result, ex) -> {
                // Remove from active escalations
                activeEscalations.remove(memberId, ResponseAction.REQUEST_KEY_ROTATION);
            });
    }

    /**
     * Request view change to remove Byzantine members.
     * <p>
     * Checks for existing escalations before proceeding.
     * Batches multiple members if appropriate.
     * </p>
     *
     * @param reason           Reason for view change
     * @param byzantineMembers Members to remove
     * @return Future completing with view change result
     */
    public CompletableFuture<ViewChangeEscalation.ViewChangeResult> requestViewChange(
        String reason,
        Set<Identifier> byzantineMembers
    ) {
        Objects.requireNonNull(reason, "reason cannot be null");
        Objects.requireNonNull(byzantineMembers, "byzantineMembers cannot be null");

        if (byzantineMembers.isEmpty()) {
            return CompletableFuture.completedFuture(
                new ViewChangeEscalation.ViewChangeResult(Set.of(), false, "No members to remove")
            );
        }

        // Filter out members with existing escalations
        var eligibleMembers = byzantineMembers.stream()
            .filter(memberId -> activeEscalations.putIfAbsent(memberId, ResponseAction.REQUEST_VIEW_CHANGE) == null)
            .collect(java.util.stream.Collectors.toSet());

        if (eligibleMembers.isEmpty()) {
            log.info("All members have existing escalations, skipping view change request");
            return CompletableFuture.completedFuture(
                new ViewChangeEscalation.ViewChangeResult(
                    byzantineMembers,
                    false,
                    "All members have existing escalations"
                )
            );
        }

        log.warn("Coordinating view change for {}: {}", eligibleMembers, reason);

        return viewChangeEscalation.requestViewChange(reason, eligibleMembers)
            .whenComplete((result, ex) -> {
                // Remove from active escalations
                for (var memberId : eligibleMembers) {
                    activeEscalations.remove(memberId, ResponseAction.REQUEST_VIEW_CHANGE);
                }
            });
    }

    /**
     * Request view change to remove single member (convenience method).
     *
     * @param reason   Reason for view change
     * @param memberId Member to remove
     * @return Future completing with view change result
     */
    public CompletableFuture<ViewChangeEscalation.ViewChangeResult> requestViewChange(
        String reason,
        Identifier memberId
    ) {
        return requestViewChange(reason, Set.of(memberId));
    }

    /**
     * Check if escalation is active for member.
     *
     * @param memberId Member to check
     * @return Active escalation action (null if none)
     */
    public ResponseAction getActiveEscalation(Identifier memberId) {
        return activeEscalations.get(memberId);
    }

    /**
     * Check if any escalation is active for member.
     *
     * @param memberId Member to check
     * @return true if escalation is active
     */
    public boolean hasActiveEscalation(Identifier memberId) {
        return activeEscalations.containsKey(memberId);
    }

    /**
     * Get count of active escalations.
     *
     * @return Number of active escalations
     */
    public int getActiveEscalationCount() {
        return activeEscalations.size();
    }

    /**
     * Reset coordinator (clear all active escalations).
     */
    public void reset() {
        activeEscalations.clear();
        keyRotationEscalation.reset();
        viewChangeEscalation.reset();
    }
}
