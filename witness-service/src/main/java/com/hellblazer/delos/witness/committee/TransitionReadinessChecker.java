/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.committee;

import com.hellblazer.delos.witness.WitnessParameters;

import java.util.Objects;

/**
 * Checks if committee is ready to transition to genesis phase (BLS_ONLY).
 * Verifies BFT quorum conditions for safe state transition.
 * <p>
 * <b>Readiness Criteria</b>:
 * <ul>
 *   <li>At least (2f+1) members have registered BLS keys, where f = floor((k-1)/3)</li>
 *   <li>f represents Byzantine fault tolerance threshold</li>
 *   <li>2f+1 ensures a quorum even with f Byzantine failures</li>
 * </ul>
 * <p>
 * <b>BFT Quorum Examples</b>:
 * <ul>
 *   <li>k=4 members → f=1 → need 2f+1=3 keys (75% quorum)</li>
 *   <li>k=7 members → f=2 → need 2f+1=5 keys (71% quorum)</li>
 *   <li>k=10 members → f=3 → need 2f+1=7 keys (70% quorum)</li>
 * </ul>
 * <p>
 * <b>Thread Safety</b>: This class is stateless and thread-safe.
 * All data comes from immutable dependencies (WitnessParameters) or
 * thread-safe storage (CommitteeBLSKeyStore).
 * <p>
 * <b>Usage in Genesis Transition</b>:
 * <pre>
 * var checker = new TransitionReadinessChecker(keyStore, parameters);
 * if (checker.isReadyForTransition()) {
 *     // Safe to initiate BLS_ONLY transition
 *     coordinator.initiateTransition();
 * }
 * </pre>
 *
 * @author hal.hildebrand
 */
public final class TransitionReadinessChecker {

    private final CommitteeBLSKeyStore keyStore;
    private final WitnessParameters parameters;

    /**
     * Create a readiness checker.
     *
     * @param keyStore Committee BLS key storage
     * @param parameters Witness network parameters (provides k and f)
     * @throws NullPointerException if keyStore or parameters is null
     */
    public TransitionReadinessChecker(CommitteeBLSKeyStore keyStore, WitnessParameters parameters) {
        this.keyStore = Objects.requireNonNull(keyStore, "store cannot be null");
        this.parameters = Objects.requireNonNull(parameters, "parameters cannot be null");
    }

    /**
     * Check if committee is ready for phase transition to BLS_ONLY.
     * <p>
     * Verifies that at least (2f+1) members have registered BLS keys,
     * where f = floor((k-1)/3) is the Byzantine fault tolerance threshold.
     * <p>
     * This ensures a quorum can be formed even if f members are Byzantine
     * and f additional members are unavailable.
     *
     * @return true if quorum has registered keys (>= 2f+1), false otherwise
     */
    public boolean isReadyForTransition() {
        var registered = getRegisteredMemberCount();
        var required = getRequiredQuorum();
        return registered >= required;
    }

    /**
     * Get count of members with registered BLS keys.
     * <p>
     * This reflects the current state of the key store and may change
     * as members register or remove keys.
     *
     * @return number of registered members (>= 0)
     */
    public int getRegisteredMemberCount() {
        return keyStore.keyCount();
    }

    /**
     * Get total expected committee size.
     *
     * @return k (total members configured in WitnessParameters)
     */
    public int getTotalMemberCount() {
        return parameters.k();
    }

    /**
     * Get Byzantine fault tolerance threshold.
     * <p>
     * Formula: f = floor((k-1)/3)
     * <p>
     * This represents the maximum number of Byzantine failures the
     * committee can tolerate while maintaining safety and liveness.
     *
     * @return f (Byzantine fault tolerance threshold)
     */
    public int getFaultToleranceThreshold() {
        return parameters.toleranceLevel();
    }

    /**
     * Get required quorum for safe transition.
     * <p>
     * Formula: 2f+1, where f = floor((k-1)/3)
     * <p>
     * This ensures:
     * <ul>
     *   <li>Majority of honest nodes (> 2f nodes total)</li>
     *   <li>Can form quorum even with f Byzantine failures</li>
     *   <li>Can form quorum even with f additional unavailable nodes</li>
     * </ul>
     *
     * @return minimum number of registered members needed for transition
     */
    public int getRequiredQuorum() {
        var f = getFaultToleranceThreshold();
        return 2 * f + 1;
    }

    /**
     * Get the set of registered member identifiers.
     * <p>
     * Returns all committee members that have registered BLS keys.
     * Used for recording transition metadata to CHOAM.
     *
     * @return Set of member identifiers with registered keys (may be empty, never null)
     */
    public java.util.Set<com.hellblazer.delos.stereotomy.identifier.Identifier> getRegisteredMembers() {
        return keyStore.registeredMembers();
    }
}
