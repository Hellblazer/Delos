/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.validation;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Production implementation of FirefliesShunningIntegration.
 * <p>
 * Bridges ByzantineWitnessDetector to Fireflies View for Byzantine member shunning:
 * - Receives shunning requests from ByzantineWitnessDetector
 * - Forwards to Fireflies View.shunMember() method
 * - Tracks shunned members for query operations
 * - Non-blocking async pattern with CompletableFuture
 * - Thread-safe for concurrent shunning requests
 * </p>
 * <p>
 * <b>Error Handling:</b>
 * - Logs Fireflies failures at WARN level
 * - Returns exceptionally completed futures on errors
 * - Does not block Byzantine detector on View failures
 * - Detector continues tracking other members
 * </p>
 * <p>
 * <b>Threading:</b>
 * - markMemberForShunning() returns immediately
 * - Actual View operation runs asynchronously
 * - Thread-safe shunned member tracking with ConcurrentHashMap
 * </p>
 */
public class FirefliesShunningIntegrationImpl implements FirefliesShunningIntegration {

    private static final Logger log = LoggerFactory.getLogger(FirefliesShunningIntegrationImpl.class);

    private final FirefliesViewAdapter viewAdapter;
    private final Set<Digest> localShunnedMembers;

    /**
     * Create integration with Fireflies View adapter.
     * <p>
     * The adapter wraps the actual Fireflies View and provides
     * a shunMember() method for Byzantine member shunning.
     *
     * @param viewAdapter Fireflies View adapter
     * @throws NullPointerException if viewAdapter is null
     */
    public FirefliesShunningIntegrationImpl(FirefliesViewAdapter viewAdapter) {
        this.viewAdapter = Objects.requireNonNull(viewAdapter, "viewAdapter cannot be null");
        this.localShunnedMembers = ConcurrentHashMap.newKeySet();
    }

    /**
     * Mark a member for shunning in Fireflies membership layer.
     * <p>
     * <b>Flow:</b>
     * 1. Convert Identifier to Digest
     * 2. Call Fireflies View.shunMember() asynchronously
     * 3. Track shunned member locally on success
     * 4. Log errors without blocking caller
     * <p>
     * <b>Non-blocking:</b> Returns immediately with CompletableFuture.
     * Actual View operation happens asynchronously.
     * <p>
     * <b>Error Handling:</b> Exceptions from View are logged and returned
     * as exceptionally completed futures. Caller can handle via exceptionally()
     * or ignore to continue processing.
     *
     * @param memberId Member identifier to shun
     * @return CompletableFuture completing when shunning propagates, or exceptionally on error
     * @throws NullPointerException if memberId is null
     */
    @Override
    public CompletableFuture<Void> markMemberForShunning(Identifier memberId) {
        Objects.requireNonNull(memberId, "memberId cannot be null");

        log.info("Marking member for shunning: {}", memberId);

        // Convert Identifier to Digest for tracking
        var digest = toDigest(memberId);

        // Call Fireflies View asynchronously
        return viewAdapter.shunMember(memberId)
            .whenComplete((result, error) -> {
                if (error != null) {
                    log.warn("Failed to mark member for shunning in Fireflies View: {}", memberId, error);
                } else {
                    // Track locally on success
                    localShunnedMembers.add(digest);
                    log.info("Successfully marked member for shunning: {}", memberId);
                }
            });
    }

    /**
     * Check if a member is currently shunned.
     * <p>
     * Queries local shunned member cache populated by successful markMemberForShunning() calls.
     * <p>
     * <b>Note:</b> This reflects the local view of shunning. In a distributed system,
     * other nodes may have different shunned member sets until gossip propagates.
     *
     * @param memberId Member identifier to check
     * @return true if member is shunned locally, false otherwise
     * @throws NullPointerException if memberId is null
     */
    @Override
    public boolean isShunned(Identifier memberId) {
        Objects.requireNonNull(memberId, "memberId cannot be null");
        var digest = toDigest(memberId);
        return localShunnedMembers.contains(digest);
    }

    /**
     * Convert Identifier to Digest.
     * <p>
     * Handles conversion from stereotomy Identifier to membership Digest.
     * Currently only supports SelfAddressingIdentifier (SAI).
     *
     * @param identifier Identifier to convert
     * @return Digest extracted from identifier
     * @throws IllegalArgumentException if identifier is not a SelfAddressingIdentifier
     */
    private Digest toDigest(Identifier identifier) {
        if (identifier instanceof SelfAddressingIdentifier sai) {
            return sai.getDigest();
        }
        throw new IllegalArgumentException("Cannot convert identifier to digest: " + identifier);
    }

    /**
     * Adapter interface for Fireflies View.
     * <p>
     * Provides abstraction over Fireflies View for testing and integration.
     * Production code wraps the actual View; tests provide mock implementation.
     */
    public interface FirefliesViewAdapter {
        /**
         * Shun a member in Fireflies membership.
         * <p>
         * Implementation should:
         * - Call View.gc(Participant) to garbage collect the member
         * - Add member to View's shunned set
         * - Propagate shunning via gossip protocol
         *
         * @param memberId Member to shun
         * @return CompletableFuture completing when shunning propagates
         */
        CompletableFuture<Void> shunMember(Identifier memberId);
    }
}
