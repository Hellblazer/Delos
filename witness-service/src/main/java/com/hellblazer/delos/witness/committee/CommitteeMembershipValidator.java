/*
 * Copyright (c) 2024, Salesforce.com, Inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.witness.committee;

import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.witness.WitnessContext;
import com.hellblazer.delos.witness.integration.WitnessKerlIntegration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Validates committee membership against Fireflies and KERL identifiers.
 * <p>
 * Verification algorithm:
 * 1. Member exists in Fireflies membership
 * 2. Member has valid KERL KeyState
 * 3. Committee = Fireflies member ∩ verified KERL identifiers
 * </p>
 * <p>
 * Caching:
 * - 500ms TTL (phase-aligned with collection epochs)
 * - Invalidated on view changes
 * </p>
 */
public class CommitteeMembershipValidator {

    private static final Logger log = LoggerFactory.getLogger(CommitteeMembershipValidator.class);
    private static final Duration CACHE_TTL = Duration.ofMillis(500);

    private final WitnessContext witnessContext;
    private final WitnessKerlIntegration kerlIntegration;
    private final ConcurrentHashMap<Long, CachedValidation> validationCache;

    /**
     * Create committee membership validator.
     *
     * @param witnessContext   Witness context for Fireflies membership
     * @param kerlIntegration  KERL integration for KeyState verification
     */
    public CommitteeMembershipValidator(WitnessContext witnessContext, WitnessKerlIntegration kerlIntegration) {
        this.witnessContext = witnessContext;
        this.kerlIntegration = kerlIntegration;
        this.validationCache = new ConcurrentHashMap<>();
    }

    /**
     * Validate committee membership against Fireflies and KERL.
     * <p>
     * Returns intersection of:
     * - Current Fireflies members
     * - Members with valid KERL KeyState at current epoch
     * </p>
     *
     * @param members Candidate members to validate
     * @return Validated identifiers with confirmed KERL KeyState
     */
    public Set<Identifier> validateCommitteeMembership(Set<Member> members) {
        var currentEpoch = witnessContext.getEpoch();

        // Check cache first (500ms TTL)
        var cached = getCachedValidation(currentEpoch);
        if (cached != null) {
            log.debug("Using cached committee validation for epoch={}", currentEpoch);
            return cached;
        }

        // Get current Fireflies members
        var firefliesMembers = witnessContext.getCurrentMembers();

        // Validate each member against KERL
        var validatedIdentifiers = members.stream()
            .map(Member::getId)
            .map(witnessContext::toIdentifier)
            .filter(firefliesMembers::contains) // Must be in Fireflies
            .filter(id -> verifyKerlIdentifier(id, currentEpoch)) // Must have valid KERL KeyState
            .collect(Collectors.toSet());

        log.debug("Validated {} of {} members for epoch={}",
                  validatedIdentifiers.size(), members.size(), currentEpoch);

        // Cache result
        cacheValidation(currentEpoch, validatedIdentifiers);

        return validatedIdentifiers;
    }

    /**
     * Verify identifier has valid KERL KeyState at epoch.
     * Handles key rotation by accepting sequence >= epoch.
     *
     * @param identifier Identifier to verify
     * @param epoch      Current epoch
     * @return true if valid KeyState found
     */
    private boolean verifyKerlIdentifier(Identifier identifier, long epoch) {
        var keyState = kerlIntegration.verifyIdentifier(identifier, epoch);
        if (keyState.isEmpty()) {
            log.debug("No KERL KeyState for identifier={} at epoch={}", identifier, epoch);
            return false;
        }

        // Handle key rotation: accept if sequence >= epoch
        var sequenceNumber = keyState.get().getSequenceNumber().longValue();
        if (sequenceNumber < epoch) {
            log.debug("KERL KeyState sequence {} < epoch {} for identifier={}",
                      sequenceNumber, epoch, identifier);
            return false;
        }

        return true;
    }

    /**
     * Get cached validation if within TTL.
     *
     * @param epoch Epoch to check
     * @return Cached validation or null if expired/missing
     */
    private Set<Identifier> getCachedValidation(long epoch) {
        var cached = validationCache.get(epoch);
        if (cached == null) {
            return null;
        }

        var now = System.currentTimeMillis();
        if (now > cached.expiryTime) {
            validationCache.remove(epoch);
            return null;
        }

        return cached.validatedIdentifiers;
    }

    /**
     * Cache validation result with 500ms TTL.
     *
     * @param epoch                 Epoch
     * @param validatedIdentifiers  Validated identifiers
     */
    private void cacheValidation(long epoch, Set<Identifier> validatedIdentifiers) {
        var expiryTime = System.currentTimeMillis() + CACHE_TTL.toMillis();
        validationCache.put(epoch, new CachedValidation(Set.copyOf(validatedIdentifiers), expiryTime));
    }

    /**
     * Invalidate cache on view change.
     * Called from view change propagation (Phase A.5).
     */
    public void invalidateCacheOnViewChange() {
        var size = validationCache.size();
        validationCache.clear();
        log.debug("Invalidated committee validation cache, removed {} entries", size);
    }

    /**
     * Get cache size for monitoring.
     *
     * @return Current cache size
     */
    public int getCacheSize() {
        return validationCache.size();
    }

    /**
     * Cached validation result.
     */
    private record CachedValidation(Set<Identifier> validatedIdentifiers, long expiryTime) {
    }
}
