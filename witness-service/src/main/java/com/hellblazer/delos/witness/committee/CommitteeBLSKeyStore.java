/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.committee;

import com.hellblazer.delos.cryptography.bls.BLSPublicKey;
import com.hellblazer.delos.stereotomy.identifier.Identifier;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Storage for committee member BLS public keys.
 * <p>
 * Thread-safe storage implementation for concurrent access during receipt validation.
 * Supports registration, retrieval, and removal of BLS keys for committee members.
 * <p>
 * <b>Storage Strategy (Phase 1B-3)</b>:
 * <ul>
 *   <li><b>Primary Implementation</b>: InMemoryCommitteeBLSKeyStore (in-memory, cleared on restart)</li>
 *   <li><b>Production Path</b>: CHOAM-backed storage (deferred to Phase 1C)</li>
 *   <li><b>Rationale</b>: Committee keys are ephemeral per session. Members re-register keys
 *       after node restart. CHOAM persistence can be added later for faster startup in large networks.</li>
 * </ul>
 * <p>
 * <b>Validation Note</b>: This interface stores keys WITHOUT performing validation.
 * Proof of Possession validation happens elsewhere (ProofOfPossessionValidator).
 * The {@code registerKey()} method assumes registrations have already been validated.
 *
 * @author hal.hildebrand
 */
public interface CommitteeBLSKeyStore {

    /**
     * Register a new BLS public key for a committee member.
     * <p>
     * Stores the registration if no key already exists for this member.
     * This method does NOT perform PoP validation - that must be done
     * by the caller before invoking this method.
     *
     * @param registration Key registration with PoP (assumed pre-validated)
     * @return true if registration was stored, false if key already exists for this member
     * @throws NullPointerException if registration is null
     */
    boolean registerKey(BLSKeyRegistration registration);

    /**
     * Get the BLS public key for a committee member.
     * <p>
     * Returns the registered key if one exists, or empty if no key is registered.
     *
     * @param memberId Committee member identifier
     * @return Optional containing the public key if registered, empty otherwise
     * @throws NullPointerException if memberId is null
     */
    Optional<BLSPublicKey> getPublicKey(Identifier memberId);

    /**
     * Get BLS public keys for multiple committee members.
     * <p>
     * Filters out members without registered keys. The returned list contains
     * only keys for members that have registered keys.
     *
     * @param memberIds Set of committee member identifiers
     * @return List of public keys for members with registered keys (may be empty)
     * @throws NullPointerException if memberIds is null
     */
    List<BLSPublicKey> getPublicKeys(Set<Identifier> memberIds);

    /**
     * Check if a member has a registered BLS key.
     * <p>
     * This is more efficient than calling {@code getPublicKey().isPresent()}
     * when only existence needs to be checked.
     *
     * @param memberId Committee member identifier
     * @return true if key is registered, false otherwise
     * @throws NullPointerException if memberId is null
     */
    boolean hasKey(Identifier memberId);

    /**
     * Remove a member's BLS key.
     * <p>
     * Used for key rotation or member removal. After removal, the member
     * can register a new key.
     *
     * @param memberId Committee member identifier
     * @return true if key was removed, false if no key was registered
     * @throws NullPointerException if memberId is null
     */
    boolean removeKey(Identifier memberId);

    /**
     * Get the count of registered keys.
     * <p>
     * Returns the total number of members with registered BLS keys.
     *
     * @return Number of registered BLS keys (>= 0)
     */
    int keyCount();

    /**
     * Get all registered member identifiers.
     * <p>
     * Used for transition readiness checks to determine which committee
     * members have registered BLS keys.
     *
     * @return Set of member IDs with registered keys (may be empty, never null)
     */
    Set<Identifier> registeredMembers();
}
