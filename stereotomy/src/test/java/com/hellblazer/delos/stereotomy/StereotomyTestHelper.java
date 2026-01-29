/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.stereotomy;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;

import java.security.SecureRandom;

/**
 * Test helper for creating deterministic Stereotomy instances.
 *
 * Provides factory methods for creating identifiers, key stores, and KERLs
 * with deterministic seeds for reproducible test execution.
 *
 * @author hal.hildebrand
 */
public class StereotomyTestHelper {
    private final SecureRandom  entropy;
    private final MemKeyStore   keyStore;
    private final MemKERL       kerl;
    private final StereotomyImpl stereotomy;

    /**
     * Create a new test helper with the specified seed.
     *
     * @param seed Seed bytes for SecureRandom (enables deterministic test execution)
     * @throws Exception if SecureRandom initialization fails
     */
    public StereotomyTestHelper(byte[] seed) throws Exception {
        this.entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(seed);
        this.keyStore = new MemKeyStore();
        this.kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        this.stereotomy = new StereotomyImpl(keyStore, kerl, entropy);
    }

    /**
     * Get the underlying Stereotomy instance.
     *
     * @return the StereotomyImpl instance
     */
    public StereotomyImpl getStereotomy() {
        return stereotomy;
    }

    /**
     * Get the underlying key store.
     *
     * @return the MemKeyStore instance
     */
    public MemKeyStore getKeyStore() {
        return keyStore;
    }

    /**
     * Get the underlying KERL.
     *
     * @return the MemKERL instance
     */
    public MemKERL getKerl() {
        return kerl;
    }

    /**
     * Get the entropy source.
     *
     * @return the SecureRandom instance
     */
    public SecureRandom getEntropy() {
        return entropy;
    }

    /**
     * Create a new controlled identifier.
     *
     * @return a new ControlledIdentifier
     */
    public ControlledIdentifier createIdentifier() {
        return stereotomy.newIdentifier();
    }

    /**
     * Perform a key rotation on the given identifier.
     *
     * @param id the identifier to rotate
     * @return void (mutation only, returns null)
     */
    public Void rotateIdentifier(ControlledIdentifier id) {
        return id.rotate();
    }

    /**
     * Get the last event coordinates for an identifier.
     *
     * @param id the identifier
     * @return the last event coordinates
     */
    public EventCoordinates getLastEvent(ControlledIdentifier id) {
        return id.getLastEvent();
    }
}
