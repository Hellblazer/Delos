/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package org.h2.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test determinism of MathUtils random number generation.
 *
 * Critical requirement: All random operations must use SECURE_RANDOM ThreadLocal
 * which is properly seeded by SqlStateMachine.withContext(), NOT ThreadLocalRandom
 * which bypasses deterministic seeding.
 *
 * Related fixes:
 * - Delos-3nsd (Extender.java): Proved java.util.Random is non-deterministic across JVM versions
 * - Delos-qkh6 (this fix): Replace ThreadLocalRandom with SecureRandom ThreadLocal
 */
public class MathUtilsDeterminismTest {

    @BeforeEach
    public void setUp() {
        // Ensure clean ThreadLocal state before each test
        MathUtils.SECURE_RANDOM.remove();
    }

    @AfterEach
    public void tearDown() {
        // Clean up ThreadLocal after each test
        MathUtils.SECURE_RANDOM.remove();
    }

    /**
     * Create a deterministic SecureRandom instance using SHA1PRNG algorithm.
     * This ensures consistent behavior across all JVM versions and platforms.
     */
    private static SecureRandom createDeterministicRandom(byte[] seed) {
        try {
            var random = SecureRandom.getInstance("SHA1PRNG");
            random.setSeed(seed);
            return random;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA1PRNG algorithm not available", e);
        }
    }

    /**
     * Verify that randomBytes() produces deterministic output when using the same seed.
     * This is the PRIMARY requirement for Byzantine fault tolerance - replicas MUST
     * generate identical random sequences given the same initial state.
     */
    @Test
    public void testRandomBytesDeterminism() {
        // Create two SecureRandom instances with identical seeds
        var seed = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

        var random1 = createDeterministicRandom(seed);
        MathUtils.SECURE_RANDOM.set(random1);

        var bytes1 = new byte[32];
        MathUtils.randomBytes(bytes1);

        // Reset ThreadLocal with new instance using same seed
        MathUtils.SECURE_RANDOM.remove();
        var random2 = createDeterministicRandom(seed);
        MathUtils.SECURE_RANDOM.set(random2);

        var bytes2 = new byte[32];
        MathUtils.randomBytes(bytes2);

        // CRITICAL: Same seed MUST produce identical output
        assertArrayEquals(bytes1, bytes2,
            "randomBytes() must produce identical output with same seed (BFT requirement)");
    }

    /**
     * Verify that randomInt() produces deterministic output when using the same seed.
     */
    @Test
    public void testRandomIntDeterminism() {
        var seed = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

        var random1 = createDeterministicRandom(seed);
        MathUtils.SECURE_RANDOM.set(random1);

        // Generate sequence of 100 random integers
        var ints1 = new int[100];
        for (int i = 0; i < 100; i++) {
            ints1[i] = MathUtils.randomInt(1000);
        }

        // Reset and generate again with same seed
        MathUtils.SECURE_RANDOM.remove();
        var random2 = createDeterministicRandom(seed);
        MathUtils.SECURE_RANDOM.set(random2);

        var ints2 = new int[100];
        for (int i = 0; i < 100; i++) {
            ints2[i] = MathUtils.randomInt(1000);
        }

        // CRITICAL: Same seed MUST produce identical sequence
        assertArrayEquals(ints1, ints2,
            "randomInt() must produce identical sequence with same seed (BFT requirement)");
    }

    /**
     * Verify that different seeds produce different output (sanity check).
     */
    @Test
    public void testDifferentSeedsProduceDifferentOutput() {
        var seed1 = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        var seed2 = new byte[]{16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1};

        var random1 = new SecureRandom();
        random1.setSeed(seed1);
        MathUtils.SECURE_RANDOM.set(random1);

        var bytes1 = new byte[32];
        MathUtils.randomBytes(bytes1);

        MathUtils.SECURE_RANDOM.remove();
        var random2 = new SecureRandom();
        random2.setSeed(seed2);
        MathUtils.SECURE_RANDOM.set(random2);

        var bytes2 = new byte[32];
        MathUtils.randomBytes(bytes2);

        assertFalse(Arrays.equals(bytes1, bytes2),
            "Different seeds should produce different output (sanity check)");
    }

    /**
     * Verify that randomBytes() and randomInt() fail appropriately when SECURE_RANDOM
     * is not initialized. This ensures we don't silently fall back to non-deterministic sources.
     */
    @Test
    public void testFailsWhenNotInitialized() {
        MathUtils.SECURE_RANDOM.remove();

        // Should throw NullPointerException when ThreadLocal not initialized
        assertThrows(NullPointerException.class, () -> {
            var bytes = new byte[32];
            MathUtils.randomBytes(bytes);
        }, "randomBytes() should fail when SECURE_RANDOM not initialized");

        assertThrows(NullPointerException.class, () -> {
            MathUtils.randomInt(100);
        }, "randomInt() should fail when SECURE_RANDOM not initialized");
    }

    /**
     * Verify multi-thread determinism: multiple threads using same seed produce identical output.
     * This validates ThreadLocal isolation and determinism under concurrent execution.
     */
    @RepeatedTest(10)
    public void testMultiThreadDeterminism() throws InterruptedException {
        var seed = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

        // Thread 1 generates sequence
        var bytes1 = new byte[32];
        var thread1 = new Thread(() -> {
            var random = createDeterministicRandom(seed);
            MathUtils.SECURE_RANDOM.set(random);
            MathUtils.randomBytes(bytes1);
        });

        // Thread 2 generates sequence with same seed
        var bytes2 = new byte[32];
        var thread2 = new Thread(() -> {
            var random = createDeterministicRandom(seed);
            MathUtils.SECURE_RANDOM.set(random);
            MathUtils.randomBytes(bytes2);
        });

        thread1.start();
        thread2.start();
        thread1.join();
        thread2.join();

        assertArrayEquals(bytes1, bytes2,
            "Multi-thread execution must produce identical output with same seed");
    }

    /**
     * Verify that secureRandomLong() correctly uses SECURE_RANDOM ThreadLocal.
     * This method already uses getSecureRandom() internally - verify it's deterministic.
     */
    @Test
    public void testSecureRandomLongDeterminism() {
        var seed = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

        var random1 = createDeterministicRandom(seed);
        MathUtils.SECURE_RANDOM.set(random1);

        var longs1 = new long[100];
        for (int i = 0; i < 100; i++) {
            longs1[i] = MathUtils.secureRandomLong();
        }

        MathUtils.SECURE_RANDOM.remove();
        var random2 = createDeterministicRandom(seed);
        MathUtils.SECURE_RANDOM.set(random2);

        var longs2 = new long[100];
        for (int i = 0; i < 100; i++) {
            longs2[i] = MathUtils.secureRandomLong();
        }

        assertArrayEquals(longs1, longs2,
            "secureRandomLong() must produce identical sequence with same seed");
    }

    /**
     * Verify that secureRandomInt() correctly uses SECURE_RANDOM ThreadLocal.
     */
    @Test
    public void testSecureRandomIntDeterminism() {
        var seed = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

        var random1 = createDeterministicRandom(seed);
        MathUtils.SECURE_RANDOM.set(random1);

        var ints1 = new int[100];
        for (int i = 0; i < 100; i++) {
            ints1[i] = MathUtils.secureRandomInt(1000);
        }

        MathUtils.SECURE_RANDOM.remove();
        var random2 = createDeterministicRandom(seed);
        MathUtils.SECURE_RANDOM.set(random2);

        var ints2 = new int[100];
        for (int i = 0; i < 100; i++) {
            ints2[i] = MathUtils.secureRandomInt(1000);
        }

        assertArrayEquals(ints1, ints2,
            "secureRandomInt() must produce identical sequence with same seed");
    }

    /**
     * Verify that secureRandomBytes() correctly uses SECURE_RANDOM ThreadLocal.
     */
    @Test
    public void testSecureRandomBytesDeterminism() {
        var seed = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

        var random1 = createDeterministicRandom(seed);
        MathUtils.SECURE_RANDOM.set(random1);

        var bytes1 = MathUtils.secureRandomBytes(32);

        MathUtils.SECURE_RANDOM.remove();
        var random2 = createDeterministicRandom(seed);
        MathUtils.SECURE_RANDOM.set(random2);

        var bytes2 = MathUtils.secureRandomBytes(32);

        assertArrayEquals(bytes1, bytes2,
            "secureRandomBytes() must produce identical output with same seed");
    }
}
