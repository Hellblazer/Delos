/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.cryptography.bls;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test-First Development: Tests for BLS test infrastructure.
 * <p>
 * These tests define the expected behavior of BLSTestFixtures before implementation.
 * Following TDD RED → GREEN → REFACTOR methodology.
 */
class BLSTestFixturesTest {

    @Test
    void deterministicRandomProducesSameSequence() {
        // RED: Write test that defines expected behavior
        // Given: Two SecureRandom instances with same seed
        var seed = 42L;
        var random1 = BLSTestFixtures.deterministicRandom(seed);
        var random2 = BLSTestFixtures.deterministicRandom(seed);

        // When: Generate random bytes
        var bytes1 = new byte[32];
        var bytes2 = new byte[32];
        random1.nextBytes(bytes1);
        random2.nextBytes(bytes2);

        // Then: Sequences should be identical
        assertArrayEquals(bytes1, bytes2,
            "Deterministic random with same seed should produce identical sequences");
    }

    @Test
    void generateCommitteeReturnsExpectedSize() {
        // RED: Define expected committee generation behavior
        // Given: Committee size and seed
        var committeeSize = 5;
        var seed = 123L;

        // When: Generate committee
        var committee = BLSTestFixtures.generateCommittee(committeeSize, seed);

        // Then: Should return list of expected size with valid key pairs
        assertNotNull(committee, "Committee should not be null");
        assertEquals(committeeSize, committee.size(),
            "Committee should have exactly " + committeeSize + " members");

        // Verify each key pair is valid
        committee.forEach(keyPair -> {
            assertNotNull(keyPair, "Key pair should not be null");
            assertNotNull(keyPair.getSecretKey(), "Secret key should not be null");
            assertNotNull(keyPair.getPublicKey(), "Public key should not be null");
        });
    }

    @Test
    void randomMessageGeneratesVariousLengths() {
        // RED: Define expected message generation behavior
        // Given: Random instance
        var random = new SecureRandom();

        // When: Generate multiple messages
        var message1 = BLSTestFixtures.randomMessage(random);
        var message2 = BLSTestFixtures.randomMessage(random);
        var message3 = BLSTestFixtures.randomMessage(random);

        // Then: Messages should not be null and vary in content
        assertNotNull(message1, "Message should not be null");
        assertNotNull(message2, "Message should not be null");
        assertNotNull(message3, "Message should not be null");

        assertTrue(message1.length > 0, "Message should not be empty");
        assertTrue(message2.length > 0, "Message should not be empty");
        assertTrue(message3.length > 0, "Message should not be empty");

        // At least one message should differ (extremely high probability)
        var allSame = java.util.Arrays.equals(message1, message2) &&
                      java.util.Arrays.equals(message2, message3);
        assertFalse(allSame, "Random messages should vary");
    }

    @Test
    void committeeIsDeterministic() {
        // RED: Verify deterministic committee generation
        // Given: Same seed used twice
        var seed = 999L;
        var size = 3;

        // When: Generate two committees with same seed
        var committee1 = BLSTestFixtures.generateCommittee(size, seed);
        var committee2 = BLSTestFixtures.generateCommittee(size, seed);

        // Then: Committees should be identical
        assertEquals(committee1.size(), committee2.size());
        for (int i = 0; i < size; i++) {
            var kp1 = committee1.get(i);
            var kp2 = committee2.get(i);
            assertEquals(kp1.getSecretKey().toBytes(), kp2.getSecretKey().toBytes(),
                "Secret keys should match for same seed at index " + i);
            assertEquals(kp1.getPublicKey().toBytesCompressed(),
                kp2.getPublicKey().toBytesCompressed(),
                "Public keys should match for same seed at index " + i);
        }
    }
}
