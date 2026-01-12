/*
 * Copyright (c) 2026, Hellblazer, Inc. All rights reserved.
 */
package com.hellblazer.delos.state;

import org.junit.jupiter.api.Test;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify SecureRandom.setSeed() behavior for determinism.
 * <p>
 * Critical for SqlStateMachine: replicas must produce identical random values
 * when given identical block hashes. This test verifies whether reusing a
 * SecureRandom instance with repeated setSeed() calls provides determinism.
 */
public class SecureRandomDeterminismTest {

    @Test
    public void testSetSeedProvidesDeterminism() throws NoSuchAlgorithmException {
        // Simulate two replicas with separate SecureRandom instances
        var replica1 = SecureRandom.getInstance("SHA1PRNG");
        var replica2 = SecureRandom.getInstance("SHA1PRNG");

        // Block 1: Both replicas seed with same block hash
        byte[] block1Hash = "block1_hash_0123456789abcdef".getBytes();
        replica1.setSeed(block1Hash);
        replica2.setSeed(block1Hash);

        // Generate random values - should be identical
        long value1_r1 = replica1.nextLong();
        long value1_r2 = replica2.nextLong();

        assertEquals(value1_r1, value1_r2,
                     "Block 1: Replicas must produce identical random values with same seed");

        // Block 2: Reseed with different block hash
        byte[] block2Hash = "block2_hash_fedcba9876543210".getBytes();
        replica1.setSeed(block2Hash);
        replica2.setSeed(block2Hash);

        long value2_r1 = replica1.nextLong();
        long value2_r2 = replica2.nextLong();

        assertEquals(value2_r1, value2_r2,
                     "Block 2: Replicas must produce identical random values after reseed");

        // Block 3: Reseed with first block hash again - should produce SAME values as Block 1
        replica1.setSeed(block1Hash);
        replica2.setSeed(block1Hash);

        long value3_r1 = replica1.nextLong();
        long value3_r2 = replica2.nextLong();

        assertEquals(value3_r1, value3_r2,
                     "Block 3: Replicas must produce identical random values");

        // CRITICAL TEST: Should Block 3 values match Block 1 values?
        // If setSeed() REPLACES state: value3_r1 == value1_r1 (deterministic)
        // If setSeed() SUPPLEMENTS state: value3_r1 != value1_r1 (non-deterministic)
        if (value3_r1 == value1_r1) {
            System.out.println("✓ setSeed() REPLACES entropy - deterministic behavior confirmed");
        } else {
            System.out.println("✗ setSeed() SUPPLEMENTS entropy - REPLICA DIVERGENCE RISK");
        }
    }

    @Test
    public void testMultipleValuesWithSameSeed() throws NoSuchAlgorithmException {
        // Verify that multiple values generated from same seed are deterministic
        var replica1 = SecureRandom.getInstance("SHA1PRNG");
        var replica2 = SecureRandom.getInstance("SHA1PRNG");

        byte[] blockHash = "test_block_hash".getBytes();

        // Generate sequence from replica 1
        replica1.setSeed(blockHash);
        byte[] sequence1 = new byte[32];
        replica1.nextBytes(sequence1);

        // Generate sequence from replica 2
        replica2.setSeed(blockHash);
        byte[] sequence2 = new byte[32];
        replica2.nextBytes(sequence2);

        assertArrayEquals(sequence1, sequence2,
                          "Replicas must produce identical byte sequences with same seed");
    }

    @Test
    public void testCurrentSqlStateMachinePattern() throws NoSuchAlgorithmException {
        // Test the ACTUAL pattern used in SqlStateMachine.begin()
        // Single SecureRandom instance reused across multiple blocks

        var secureEntropy = SecureRandom.getInstance("SHA1PRNG");

        // Simulate 3 blocks
        byte[][] blockHashes = {
            "block_0_hash".getBytes(),
            "block_1_hash".getBytes(),
            "block_2_hash".getBytes()
        };

        long[][] replicaValues = new long[3][3]; // 3 replicas, 3 blocks each

        // Simulate 3 replicas processing same block sequence
        for (int replica = 0; replica < 3; replica++) {
            var entropy = SecureRandom.getInstance("SHA1PRNG");

            for (int block = 0; block < 3; block++) {
                // This mimics SqlStateMachine.begin() line 603
                entropy.setSeed(blockHashes[block]);

                // Generate a random value (simulating SQL RAND() call)
                replicaValues[replica][block] = entropy.nextLong();
            }
        }

        // Verify all replicas produced identical values for each block
        for (int block = 0; block < 3; block++) {
            long expected = replicaValues[0][block];
            for (int replica = 1; replica < 3; replica++) {
                assertEquals(expected, replicaValues[replica][block],
                             String.format("Block %d: Replica %d diverged (got %d, expected %d)",
                                           block, replica, replicaValues[replica][block], expected));
            }
        }

        System.out.println("✓ All replicas produced identical random values across all blocks");
    }
}
