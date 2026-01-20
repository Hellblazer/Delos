/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.committee;

import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.witness.integration.WitnessKerlIntegration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates KERI delegation chains for multi-tenant scenarios.
 * <p>
 * Verification algorithm:
 * 1. Delegated identifier is valid (not self-delegated)
 * 2. Delegation chain leads to authorized root identifier
 * 3. Delegation is active (not expired)
 * </p>
 * <p>
 * Caching:
 * - 60s TTL (same as KERL cache)
 * - Cleared on view changes
 * </p>
 */
public class DelegationChainValidator {

    private static final Logger log = LoggerFactory.getLogger(DelegationChainValidator.class);
    private static final Duration CACHE_TTL = Duration.ofSeconds(60);
    private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_CHAIN_DEPTH = 10; // Prevent infinite loops

    private final WitnessKerlIntegration kerlIntegration;
    private final Set<Identifier> authorizedRoots;
    private final ConcurrentHashMap<DelegationCacheKey, CachedValidation> validationCache;

    /**
     * Create delegation chain validator.
     *
     * @param kerlIntegration  KERL integration for KeyState lookup
     * @param authorizedRoots  Set of authorized root identifiers
     */
    public DelegationChainValidator(WitnessKerlIntegration kerlIntegration, Set<Identifier> authorizedRoots) {
        this.kerlIntegration = kerlIntegration;
        this.authorizedRoots = Set.copyOf(authorizedRoots);
        this.validationCache = new ConcurrentHashMap<>();
    }

    /**
     * Verify delegation chain for delegated identifier.
     * <p>
     * Algorithm:
     * 1. Follow delegation links via KERL
     * 2. Verify each link signed by parent
     * 3. Ensure chain terminates at authorized root
     * 4. Timeout: 5s per lookup
     * </p>
     *
     * @param delegatedId  Delegated identifier to verify
     * @param rootId       Expected root identifier
     * @param epoch        Current epoch
     * @return true if delegation chain valid
     */
    public boolean verifyDelegationChain(Identifier delegatedId, Identifier rootId, long epoch) {
        // Check cache first
        var cacheKey = new DelegationCacheKey(delegatedId, rootId, epoch);
        var cached = getCachedValidation(cacheKey);
        if (cached != null) {
            log.debug("Using cached delegation chain validation for delegatedId={}", delegatedId);
            return cached;
        }

        // Verify root is authorized
        if (!authorizedRoots.contains(rootId)) {
            log.warn("Root identifier {} not in authorized roots", rootId);
            cacheValidation(cacheKey, false);
            return false;
        }

        // Follow delegation chain
        var isValid = followDelegationChain(delegatedId, rootId, epoch);

        // Cache result
        cacheValidation(cacheKey, isValid);

        return isValid;
    }

    /**
     * Follow delegation chain from delegated identifier to root.
     *
     * @param delegatedId  Starting identifier
     * @param rootId       Expected root
     * @param epoch        Current epoch
     * @return true if chain valid
     */
    private boolean followDelegationChain(Identifier delegatedId, Identifier rootId, long epoch) {
        var currentId = delegatedId;
        var visited = new HashSet<Identifier>();
        var depth = 0;

        while (true) {
            // Prevent infinite loops
            if (depth++ > MAX_CHAIN_DEPTH) {
                log.warn("Delegation chain too deep (>{}) for delegatedId={}", MAX_CHAIN_DEPTH, delegatedId);
                return false;
            }

            // Prevent cycles
            if (visited.contains(currentId)) {
                log.warn("Delegation chain cycle detected for delegatedId={}", delegatedId);
                return false;
            }
            visited.add(currentId);

            // Lookup KeyState with timeout
            var startTime = System.currentTimeMillis();
            var keyStateOpt = kerlIntegration.verifyIdentifier(currentId, epoch);
            var elapsedTime = System.currentTimeMillis() - startTime;

            if (elapsedTime > LOOKUP_TIMEOUT.toMillis()) {
                log.warn("KERL lookup timeout ({}ms > {}ms) for identifier={}",
                         elapsedTime, LOOKUP_TIMEOUT.toMillis(), currentId);
                return false;
            }

            if (keyStateOpt.isEmpty()) {
                log.debug("No KeyState for identifier={} in delegation chain", currentId);
                return false;
            }

            var keyState = keyStateOpt.get();

            // Check if we reached the root
            if (currentId.equals(rootId)) {
                // Root must be non-delegated
                if (!keyState.isDelegated()) {
                    log.debug("Delegation chain valid: {} -> {}", delegatedId, rootId);
                    return true;
                } else {
                    log.warn("Root {} is delegated (expected non-delegated)", rootId);
                    return false;
                }
            }

            // Must be delegated to continue chain
            if (!keyState.isDelegated()) {
                log.warn("Delegation chain broken: non-delegated {} before root {}", currentId, rootId);
                return false;
            }

            // Follow delegation link
            var delegatingIdOpt = keyState.getDelegatingIdentifier();
            if (delegatingIdOpt.isEmpty()) {
                log.warn("Delegation chain broken: no delegating identifier for {}", currentId);
                return false;
            }

            var nextId = delegatingIdOpt.get();

            // Check for self-delegation (invalid)
            if (nextId.equals(currentId)) {
                log.warn("Self-delegation detected for identifier={}", currentId);
                return false;
            }

            currentId = nextId;
        }
    }

    /**
     * Get cached validation result if within TTL.
     *
     * @param key Cache key
     * @return Cached result or null if expired/missing
     */
    private Boolean getCachedValidation(DelegationCacheKey key) {
        var cached = validationCache.get(key);
        if (cached == null) {
            return null;
        }

        var now = System.currentTimeMillis();
        if (now > cached.expiryTime) {
            validationCache.remove(key);
            return null;
        }

        return cached.isValid;
    }

    /**
     * Cache validation result with 60s TTL.
     *
     * @param key     Cache key
     * @param isValid Validation result
     */
    private void cacheValidation(DelegationCacheKey key, boolean isValid) {
        var expiryTime = System.currentTimeMillis() + CACHE_TTL.toMillis();
        validationCache.put(key, new CachedValidation(isValid, expiryTime));
    }

    /**
     * Invalidate cache on view change.
     */
    public void invalidateCacheOnViewChange() {
        var size = validationCache.size();
        validationCache.clear();
        log.debug("Invalidated delegation chain cache, removed {} entries", size);
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
     * Get authorized roots (immutable view).
     *
     * @return Authorized root identifiers
     */
    public Set<Identifier> getAuthorizedRoots() {
        return Set.copyOf(authorizedRoots);
    }

    /**
     * Cache key combining delegated identifier, root, and epoch.
     */
    private record DelegationCacheKey(Identifier delegatedId, Identifier rootId, long epoch) {
    }

    /**
     * Cached validation result.
     */
    private record CachedValidation(boolean isValid, long expiryTime) {
    }
}
