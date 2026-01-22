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

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Escalation handler for view change requests to remove Byzantine members.
 * <p>
 * When a member exhibits critical Byzantine behavior (score >= 0.9) or
 * equivocation/forgery, the orchestrator requests a view change to
 * permanently remove the member from the view.
 * </p>
 * <p>
 * This class:
 * - Tracks in-flight view change requests (prevents duplicates)
 * - Batches multiple Byzantine members into single view change
 * - Provides interface for future integration with Fireflies
 * - Updates member states: QUARANTINED → ESCALATING → SHUNNED
 * </p>
 * <p>
 * Thread-safe: uses concurrent collections for request tracking.
 * </p>
 *
 * @author hal.hildebrand
 */
public class ViewChangeEscalation {

    private static final Logger log = LoggerFactory.getLogger(ViewChangeEscalation.class);

    /**
     * View change result.
     *
     * @param byzantineMembers Members to remove
     * @param success          Whether view change succeeded
     * @param reason           Reason for success/failure
     */
    public record ViewChangeResult(
        Set<Identifier> byzantineMembers,
        boolean success,
        String reason
    ) {
        public ViewChangeResult {
            Objects.requireNonNull(byzantineMembers, "byzantineMembers cannot be null");
        }
    }

    /**
     * Interface for triggering view changes (to be implemented by Fireflies).
     */
    public interface ViewChangeTrigger {
        /**
         * Request view change to remove Byzantine members.
         *
         * @param reason           Reason for view change
         * @param byzantineMembers Members to remove
         * @return Future completing with view change result
         */
        CompletableFuture<ViewChangeResult> requestViewChange(
            String reason,
            Set<Identifier> byzantineMembers
        );
    }

    private final Set<Identifier> inFlightMembers;
    private final ViewChangeTrigger viewChangeTrigger;
    private volatile boolean viewChangeInProgress;

    public ViewChangeEscalation(ViewChangeTrigger viewChangeTrigger) {
        this.viewChangeTrigger = viewChangeTrigger;
        this.inFlightMembers = ConcurrentHashMap.newKeySet();
        this.viewChangeInProgress = false;
    }

    /**
     * Request view change to remove Byzantine members.
     * <p>
     * Batches multiple members into single view change request.
     * Prevents duplicate requests for same members.
     * </p>
     *
     * @param reason           Reason for view change
     * @param byzantineMembers Members to remove
     * @return Future completing with view change result
     */
    public CompletableFuture<ViewChangeResult> requestViewChange(
        String reason,
        Set<Identifier> byzantineMembers
    ) {
        Objects.requireNonNull(reason, "reason cannot be null");
        Objects.requireNonNull(byzantineMembers, "byzantineMembers cannot be null");

        if (byzantineMembers.isEmpty()) {
            return CompletableFuture.completedFuture(
                new ViewChangeResult(Set.of(), false, "No Byzantine members to remove")
            );
        }

        // Filter out members already in flight
        var newMembers = new HashSet<>(byzantineMembers);
        newMembers.removeAll(inFlightMembers);

        if (newMembers.isEmpty()) {
            log.info("View change already in progress for all requested members: {}", byzantineMembers);
            return CompletableFuture.completedFuture(
                new ViewChangeResult(byzantineMembers, false, "View change already in progress for all members")
            );
        }

        // Add to in-flight
        inFlightMembers.addAll(newMembers);
        viewChangeInProgress = true;

        log.warn("Requesting view change to remove Byzantine members {}: {}", newMembers, reason);

        // Delegate to view change trigger
        if (viewChangeTrigger == null) {
            // No trigger configured (testing or future integration)
            log.warn("No ViewChangeTrigger configured, view change request will be logged only");
            inFlightMembers.removeAll(newMembers);
            viewChangeInProgress = false;
            return CompletableFuture.completedFuture(
                new ViewChangeResult(newMembers, false, "No view change trigger configured")
            );
        }

        return viewChangeTrigger.requestViewChange(reason, newMembers)
            .whenComplete((result, ex) -> {
                // Remove from in-flight after completion
                inFlightMembers.removeAll(newMembers);
                if (inFlightMembers.isEmpty()) {
                    viewChangeInProgress = false;
                }

                if (ex != null) {
                    log.error("View change failed for {}: {}", newMembers, ex.getMessage(), ex);
                } else if (result.success()) {
                    log.info("View change succeeded, removed Byzantine members {}: {}", newMembers, result.reason());
                } else {
                    log.warn("View change failed for {}: {}", newMembers, result.reason());
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
    public CompletableFuture<ViewChangeResult> requestViewChange(String reason, Identifier memberId) {
        return requestViewChange(reason, Set.of(memberId));
    }

    /**
     * Check if view change request is in flight for member.
     *
     * @param memberId Member to check
     * @return true if view change in progress for member
     */
    public boolean isViewChangeInProgress(Identifier memberId) {
        return inFlightMembers.contains(memberId);
    }

    /**
     * Check if any view change is in progress.
     *
     * @return true if view change in progress
     */
    public boolean isViewChangeInProgress() {
        return viewChangeInProgress;
    }

    /**
     * Get count of members with in-flight view change requests.
     *
     * @return Number of members pending removal
     */
    public int getInFlightCount() {
        return inFlightMembers.size();
    }

    /**
     * Get set of members with in-flight view change requests.
     *
     * @return Members pending removal
     */
    public Set<Identifier> getInFlightMembers() {
        return Set.copyOf(inFlightMembers);
    }

    /**
     * Clear all in-flight requests (for testing or reset).
     */
    public void reset() {
        inFlightMembers.clear();
        viewChangeInProgress = false;
    }
}
