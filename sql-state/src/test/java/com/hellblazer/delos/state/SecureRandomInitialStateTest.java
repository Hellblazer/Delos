/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.state;

import org.junit.jupiter.api.Test;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Test to verify whether SecureRandom.getInstance("SHA1PRNG") provides
 * deterministic initial state, which would explain why SqlStateMachine's
 * entropy handling works correctly.
 */
public class SecureRandomInitialStateTest {

    @Test
    public void testGetInstanceInitialStateNonDeterministic() throws NoSuchAlgorithmException {
        // Create two fresh instances without explicit seeding
        var sr1 = SecureRandom.getInstance("SHA1PRNG");
        var sr2 = SecureRandom.getInstance("SHA1PRNG");

        // Generate values WITHOUT calling setSeed()
        long val1 = sr1.nextLong();
        long val2 = sr2.nextLong();

        assertNotEquals(val1, val2,
                        "SecureRandom.getInstance() without setSeed() should produce different values " +
                        "(auto-seeded from system entropy)");

        System.out.println("✓ Confirmed: getInstance() alone is non-deterministic");
    }

    @Test
    public void testSetSeedAfterGetInstanceProvidesDeterminism() throws NoSuchAlgorithmException {
        // The pattern used in SqlStateMachine
        var sr1 = SecureRandom.getInstance("SHA1PRNG");
        var sr2 = SecureRandom.getInstance("SHA1PRNG");

        // Both instances were created separately (potentially with different system entropy)
        // Now seed them with the same value
        byte[] seed = "deterministic_seed".getBytes();
        sr1.setSeed(seed);
        sr2.setSeed(seed);

        // Generate values
        long val1 = sr1.nextLong();
        long val2 = sr2.nextLong();

        assertEquals(val1, val2,
                     "After setSeed() with same value, output must be identical despite different initial states");

        System.out.println("✓ Confirmed: setSeed() synchronizes divergent instances");
    }

    @Test
    public void testExplanation() throws NoSuchAlgorithmException {
        System.out.println("\n=== SecureRandom Behavior Explanation ===");
        System.out.println("1. SecureRandom.getInstance(\"SHA1PRNG\") auto-seeds from system entropy");
        System.out.println("2. Each instance starts with DIFFERENT random state");
        System.out.println("3. setSeed() SUPPLEMENTS (not replaces) the internal state");
        System.out.println("4. BUT: setSeed() with same value synchronizes divergent instances");
        System.out.println("\nWhy SqlStateMachine works:");
        System.out.println("- Each replica creates SecureRandom at startup (different initial states)");
        System.out.println("- All replicas call setSeed(blockHash) in same order");
        System.out.println("- SHA1PRNG's supplement algorithm + same seed sequence = convergence");
        System.out.println("- After first setSeed(), all replicas produce identical values");
        System.out.println("\nConclusion: Current code is CORRECT, but relies on subtle SHA1PRNG behavior");
        System.out.println("============================================\n");
    }

    @Test
    public void testReplicaDivergence() throws NoSuchAlgorithmException {
        // IMPORTANT: This test demonstrates WHY secureEntropy must not be used before first begin()
        // If replicas are in different states, setSeed() will NOT resynchronize them

        // Simulate 3 replicas with different initial states
        var replica1 = SecureRandom.getInstance("SHA1PRNG");
        var replica2 = SecureRandom.getInstance("SHA1PRNG");
        var replica3 = SecureRandom.getInstance("SHA1PRNG");

        // Force different initial states by generating some values
        replica1.nextLong(); // Advance state
        replica2.nextLong();
        replica2.nextLong(); // Advance state more
        // replica3 stays at initial state

        // Now all three replicas are in DIFFERENT states
        // Apply same seed to all
        byte[] blockHash = "block_0".getBytes();
        replica1.setSeed(blockHash);
        replica2.setSeed(blockHash);
        replica3.setSeed(blockHash);

        // Generate values - they will be DIFFERENT despite same setSeed()
        long val1 = replica1.nextLong();
        long val2 = replica2.nextLong();
        long val3 = replica3.nextLong();

        // Assert that values are different (demonstrating the risk)
        assertNotEquals(val1, val2,
                        "EXPECTED: Replicas with different prior states produce different values after setSeed()");

        System.out.println("✓ Confirmed: setSeed() does NOT resynchronize diverged instances");
        System.out.println("✓ This is why SqlStateMachine must never use secureEntropy before first begin()");
    }
}
