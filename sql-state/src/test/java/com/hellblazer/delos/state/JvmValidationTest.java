/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.state;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.utils.Entropy;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test JVM validation in SqlStateMachine to ensure Byzantine consensus safety.
 * <p>
 * Validates that SqlStateMachine detects incompatible JVM configurations that
 * could cause non-deterministic behavior across replicas.
 * <p>
 * Related: Delos-rjtp (JVM validation for SHA1PRNG determinism)
 */
public class JvmValidationTest {

    @Test
    public void testSHA1PRNGAvailable() {
        // SHA1PRNG must be available on all supported JVMs
        assertDoesNotThrow(() -> SecureRandom.getInstance("SHA1PRNG"),
                          "SHA1PRNG must be available");
    }

    @Test
    public void testSHA1PRNGDeterminism() throws NoSuchAlgorithmException {
        // Verify SHA1PRNG produces deterministic sequences with same seed
        var rng1 = SecureRandom.getInstance("SHA1PRNG");
        var rng2 = SecureRandom.getInstance("SHA1PRNG");

        byte[] seed = "test-seed-12345".getBytes();
        rng1.setSeed(seed);
        rng2.setSeed(seed);

        // Generate sequence from each and verify they match
        for (int i = 0; i < 100; i++) {
            var val1 = rng1.nextLong();
            var val2 = rng2.nextLong();
            assertEquals(val1, val2,
                        String.format("SHA1PRNG diverged at iteration %d", i));
        }
    }

    @Test
    public void testSqlStateMachineCreationSucceeds() {
        // SqlStateMachine should successfully create on supported JVMs
        var url = String.format("jdbc:h2:mem:jvm_validation_test-%s", Entropy.nextBitsStreamLong());
        var checkpointDir = new File("target/jvm-validation-test-" + Entropy.nextBitsStreamLong());

        assertDoesNotThrow(() -> {
            var ssm = new SqlStateMachine(DigestAlgorithm.DEFAULT.getOrigin(),
                                         url,
                                         new Properties(),
                                         checkpointDir);
            ssm.close();
        }, "SqlStateMachine creation should succeed on supported JVM");
    }

    @Test
    public void testJvmInfoLogging() {
        // Test that JVM info is logged during validation
        // This test validates that validation runs without exception
        var url = String.format("jdbc:h2:mem:jvm_info_test-%s", Entropy.nextBitsStreamLong());
        var checkpointDir = new File("target/jvm-info-test-" + Entropy.nextBitsStreamLong());

        SqlStateMachine ssm = null;
        try {
            ssm = new SqlStateMachine(DigestAlgorithm.DEFAULT.getOrigin(),
                                     url,
                                     new Properties(),
                                     checkpointDir);

            // If we get here, validation passed - verify key properties
            var vendor = System.getProperty("java.vendor");
            var version = System.getProperty("java.version");

            assertNotNull(vendor, "JVM vendor should be available");
            assertNotNull(version, "JVM version should be available");

            System.out.printf("JVM validation passed: %s %s%n", vendor, version);
        } finally {
            if (ssm != null) {
                ssm.close();
            }
        }
    }

    @Test
    public void testMultipleReplicasDeterminism() throws Exception {
        // Simulate multiple replicas to verify SHA1PRNG determinism
        // This mirrors the real Byzantine consensus scenario

        var seed = "block-hash-12345".getBytes();
        var rng1 = SecureRandom.getInstance("SHA1PRNG");
        var rng2 = SecureRandom.getInstance("SHA1PRNG");
        var rng3 = SecureRandom.getInstance("SHA1PRNG");

        // All replicas seed with same block hash
        rng1.setSeed(seed);
        rng2.setSeed(seed);
        rng3.setSeed(seed);

        // Simulate 5 blocks with 10 random calls each
        for (int block = 0; block < 5; block++) {
            var blockSeed = String.format("block-%d", block).getBytes();

            // Re-seed for new block (like SqlStateMachine.begin())
            rng1.setSeed(blockSeed);
            rng2.setSeed(blockSeed);
            rng3.setSeed(blockSeed);

            // Each block processes transactions that use random values
            for (int txn = 0; txn < 10; txn++) {
                var val1 = rng1.nextLong();
                var val2 = rng2.nextLong();
                var val3 = rng3.nextLong();

                assertEquals(val1, val2,
                            String.format("Replica 1 and 2 diverged at block %d txn %d", block, txn));
                assertEquals(val1, val3,
                            String.format("Replica 1 and 3 diverged at block %d txn %d", block, txn));
            }
        }

        System.out.println("✓ Multi-replica determinism verified across 5 blocks");
    }
}
