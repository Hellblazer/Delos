package com.hellblazer.delos.cryptography.bls;

import tech.pegasys.teku.bls.BLSKeyPair;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Test fixtures and utilities for BLS cryptography testing.
 * <p>
 * Provides deterministic test data generation for reproducible testing:
 * - Seed-based random number generation
 * - Committee generation with fixed seeds
 * - Random message generation for testing
 * <p>
 * All methods use deterministic seeds to ensure test reproducibility.
 */
public final class BLSTestFixtures {

    private BLSTestFixtures() {
        // Utility class - no instantiation
    }

    /**
     * Create a SecureRandom instance with deterministic seed for testing.
     * <p>
     * WARNING: For testing only. Never use seeded random for production keys.
     * Uses SHA1PRNG algorithm for deterministic behavior.
     *
     * @param seed Deterministic seed value
     * @return SecureRandom initialized with seed
     */
    public static SecureRandom deterministicRandom(long seed) {
        try {
            // Use SHA1PRNG for deterministic behavior
            var random = SecureRandom.getInstance("SHA1PRNG");
            // Convert long seed to byte array
            var seedBytes = new byte[8];
            for (int i = 0; i < 8; i++) {
                seedBytes[i] = (byte) (seed >>> (i * 8));
            }
            random.setSeed(seedBytes);
            return random;
        } catch (java.security.NoSuchAlgorithmException e) {
            // Fallback to default SecureRandom if SHA1PRNG not available
            var random = new SecureRandom();
            random.setSeed(seed);
            return random;
        }
    }

    /**
     * Generate a committee of BLS key pairs with deterministic seed.
     * <p>
     * Useful for creating reproducible test scenarios with multiple validators.
     *
     * @param size Committee size (number of validators)
     * @param seed Deterministic seed for reproducibility
     * @return List of BLS key pairs
     * @throws IllegalArgumentException if size <= 0
     */
    public static List<BLSKeyPair> generateCommittee(int size, long seed) {
        if (size <= 0) {
            throw new IllegalArgumentException("Committee size must be positive, got: " + size);
        }

        var random = deterministicRandom(seed);
        var committee = new ArrayList<BLSKeyPair>(size);

        for (int i = 0; i < size; i++) {
            committee.add(BLSKeyPair.random(random));
        }

        return committee;
    }

    /**
     * Generate a random message for BLS signing tests.
     * <p>
     * Message length varies between 16 and 256 bytes to test various scenarios.
     *
     * @param random Random source for message generation
     * @return Random message as byte array
     * @throws NullPointerException if random is null
     */
    public static byte[] randomMessage(Random random) {
        if (random == null) {
            throw new NullPointerException("Random source must not be null");
        }

        // Random length between 16 and 256 bytes
        var length = 16 + random.nextInt(241);
        var messageBytes = new byte[length];
        random.nextBytes(messageBytes);

        return messageBytes;
    }

    /**
     * Generate a random message of fixed size for testing.
     * Uses deterministic random to ensure reproducible tests.
     *
     * @param size Size in bytes of the message
     * @return Random message as byte array of specified size
     * @throws IllegalArgumentException if size <= 0
     */
    public static byte[] randomMessage(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("Message size must be positive, got: " + size);
        }
        var random = deterministicRandom(0x42);
        var messageBytes = new byte[size];
        random.nextBytes(messageBytes);
        return messageBytes;
    }

    /**
     * Standard test message used in Ethereum 2.0 spec examples.
     */
    public static final String ETHEREUM_TEST_MESSAGE = "Ethereum 2.0 BLS test vector";

    /**
     * Get Ethereum 2.0 test message as byte array.
     */
    public static byte[] ethereumTestMessage() {
        return ETHEREUM_TEST_MESSAGE.getBytes();
    }
}
