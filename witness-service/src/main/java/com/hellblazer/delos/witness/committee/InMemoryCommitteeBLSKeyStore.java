/*
 * Copyright (c) 2025 Hal Hildebrand. All rights reserved.
 */

package com.hellblazer.delos.witness.committee;

import com.hellblazer.delos.cryptography.bls.BLSPublicKey;
import com.hellblazer.delos.stereotomy.identifier.Identifier;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of CommitteeBLSKeyStore using ConcurrentHashMap.
 * <p>
 * Thread-safe storage for concurrent access during receipt validation. Uses
 * ConcurrentHashMap for lock-free reads and atomic updates without blocking.
 * <p>
 * <b>Characteristics</b>:
 * <ul>
 *   <li>Lock-free reads (getPublicKey, hasKey)</li>
 *   <li>Atomic updates (registerKey, removeKey)</li>
 *   <li>Virtual thread compatible (no blocking operations)</li>
 *   <li>Keys cleared on JVM restart (ephemeral storage)</li>
 * </ul>
 * <p>
 * <b>Phase 1B-3 Implementation</b>:
 * This is the primary implementation for Phase 1B-3. Committee keys are
 * ephemeral per session - members re-register keys after node restart.
 * CHOAM-backed persistence can be added in Phase 1C for faster startup
 * in large networks.
 *
 * @author hal.hildebrand
 */
public class InMemoryCommitteeBLSKeyStore implements CommitteeBLSKeyStore {

    /**
     * Storage map: memberId -> publicKey
     * <p>
     * Using ConcurrentHashMap for thread-safe access without synchronized blocks.
     * putIfAbsent provides atomic compare-and-set semantics for registration.
     */
    private final ConcurrentHashMap<Identifier, BLSPublicKey> keys;

    /**
     * Create an empty in-memory key store.
     */
    public InMemoryCommitteeBLSKeyStore() {
        this.keys = new ConcurrentHashMap<>();
    }

    /**
     * Create an in-memory key store with specified initial capacity.
     * <p>
     * Use this constructor when you know the expected committee size
     * to avoid rehashing during initialization.
     *
     * @param initialCapacity Expected number of committee members
     * @throws IllegalArgumentException if initialCapacity is negative
     */
    public InMemoryCommitteeBLSKeyStore(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("initialCapacity must be >= 0, got: " + initialCapacity);
        }
        this.keys = new ConcurrentHashMap<>(initialCapacity);
    }

    @Override
    public boolean registerKey(BLSKeyRegistration registration) {
        Objects.requireNonNull(registration, "registration cannot be null");

        // putIfAbsent is atomic - returns null if key was inserted, existing value otherwise
        var existing = keys.putIfAbsent(registration.memberId(), registration.publicKey());
        return existing == null;
    }

    @Override
    public Optional<BLSPublicKey> getPublicKey(Identifier memberId) {
        Objects.requireNonNull(memberId, "memberId cannot be null");
        return Optional.ofNullable(keys.get(memberId));
    }

    @Override
    public List<BLSPublicKey> getPublicKeys(Set<Identifier> memberIds) {
        Objects.requireNonNull(memberIds, "memberIds cannot be null");

        var result = new ArrayList<BLSPublicKey>(memberIds.size());
        for (var memberId : memberIds) {
            var publicKey = keys.get(memberId);
            if (publicKey != null) {
                result.add(publicKey);
            }
        }
        return result;
    }

    @Override
    public boolean hasKey(Identifier memberId) {
        Objects.requireNonNull(memberId, "memberId cannot be null");
        return keys.containsKey(memberId);
    }

    @Override
    public boolean removeKey(Identifier memberId) {
        Objects.requireNonNull(memberId, "memberId cannot be null");
        // remove returns null if key wasn't present
        return keys.remove(memberId) != null;
    }

    @Override
    public int keyCount() {
        return keys.size();
    }

    @Override
    public Set<Identifier> registeredMembers() {
        // keySet() returns a concurrent view - safe to iterate
        return Set.copyOf(keys.keySet());
    }

    /**
     * Clear all registered keys.
     * <p>
     * This is useful for testing or when resetting committee state.
     * In production, this would only be called during controlled shutdown.
     */
    public void clear() {
        keys.clear();
    }

    @Override
    public String toString() {
        return "InMemoryCommitteeBLSKeyStore[keyCount=" + keys.size() + "]";
    }
}
