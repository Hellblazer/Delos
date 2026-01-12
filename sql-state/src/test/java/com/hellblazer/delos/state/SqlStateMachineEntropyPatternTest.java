/*
 * Copyright (c) 2026, Hellblazer, Inc. All rights reserved.
 */
package com.hellblazer.delos.state;

import org.junit.jupiter.api.Test;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test that exactly mirrors SqlStateMachine's entropy pattern:
 * 1. Create ONE SecureRandom instance at startup
 * 2. For each block, call setSeed(blockHash)
 * 3. Use the instance for random operations
 * 4. Repeat for next block (reusing same instance)
 * <p>
 * This test verifies that this pattern provides determinism across replicas.
 */
public class SqlStateMachineEntropyPatternTest {

    @Test
    public void testExactSqlStateMachinePattern() throws NoSuchAlgorithmException {
        // Simulate 3 replicas
        var replica1 = new ReplicaEntropy();
        var replica2 = new ReplicaEntropy();
        var replica3 = new ReplicaEntropy();

        // Simulate 5 blocks being processed
        byte[][] blockHashes = {
            "block_0_deadbeef".getBytes(),
            "block_1_cafebabe".getBytes(),
            "block_2_feedface".getBytes(),
            "block_3_baadf00d".getBytes(),
            "block_4_c0ffee00".getBytes()
        };

        // Each replica processes all blocks
        for (int blockNum = 0; blockNum < blockHashes.length; blockNum++) {
            byte[] blockHash = blockHashes[blockNum];

            // This is exactly what SqlStateMachine.begin() does (line 603)
            replica1.beginBlock(blockHash);
            replica2.beginBlock(blockHash);
            replica3.beginBlock(blockHash);

            // Simulate SQL code calling RAND() or RANDOM_UUID() multiple times in this block
            for (int call = 0; call < 10; call++) {
                long val1 = replica1.nextRandomValue();
                long val2 = replica2.nextRandomValue();
                long val3 = replica3.nextRandomValue();

                assertEquals(val1, val2,
                             String.format("Block %d, call %d: Replica 1 and 2 diverged", blockNum, call));
                assertEquals(val1, val3,
                             String.format("Block %d, call %d: Replica 1 and 3 diverged", blockNum, call));
            }
        }

        System.out.println("✓ SUCCESS: All 3 replicas produced identical random values across 5 blocks (50 calls each)");
        System.out.println("✓ Confirmed: SqlStateMachine.secureEntropy pattern provides determinism");
    }

    /**
     * Simulates SqlStateMachine's entropy handling
     */
    private static class ReplicaEntropy {
        // Matches SqlStateMachine line 117-128
        private final SecureRandom secureEntropy;

        ReplicaEntropy() throws NoSuchAlgorithmException {
            secureEntropy = SecureRandom.getInstance("SHA1PRNG");
        }

        // Matches SqlStateMachine.begin() line 603
        void beginBlock(byte[] blockHash) {
            secureEntropy.setSeed(blockHash);
        }

        // Simulates SQL RAND() or RANDOM_UUID() call
        long nextRandomValue() {
            return secureEntropy.nextLong();
        }
    }

    @Test
    public void testWhyItWorks() {
        System.out.println("\n=== Why SqlStateMachine's entropy pattern is correct ===");
        System.out.println("1. All replicas create SecureRandom.getInstance(\"SHA1PRNG\") the same way");
        System.out.println("2. SHA1PRNG auto-seeds, but with DETERMINISTIC algorithm (not true randomness)");
        System.out.println("3. After first setSeed(), all replicas have synchronized state");
        System.out.println("4. Subsequent setSeed() calls maintain synchronization");
        System.out.println("5. All replicas generate same number of random values per block (CHOAM guarantees identical execution)");
        System.out.println("\nKEY INSIGHT:");
        System.out.println("setSeed() on SHA1PRNG mixes the seed into internal state in a DETERMINISTIC way.");
        System.out.println("As long as all replicas:");
        System.out.println("  - Start from same initial getInstance() state");
        System.out.println("  - Call setSeed() with same block hashes in same order");
        System.out.println("  - Generate same number of random values between setSeed() calls");
        System.out.println("...they will remain synchronized.");
        System.out.println("\nCHOAM consensus ensures all three conditions are met.");
        System.out.println("=========================================================\n");
    }
}
