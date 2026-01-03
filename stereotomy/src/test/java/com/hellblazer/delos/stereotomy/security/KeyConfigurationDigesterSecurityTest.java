/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy.security;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.cryptography.SigningThreshold;
import com.hellblazer.delos.stereotomy.identifier.spec.KeyConfigurationDigester;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Security tests for CRIT-1: XOR Commutativity Attack
 * <p>
 * These tests verify that key ordering affects the configuration digest,
 * preventing attackers from reordering keys to bypass weighted threshold enforcement.
 * <p>
 * VULNERABILITY: The current implementation uses XOR to combine key digests,
 * which is commutative: XOR(A,B) = XOR(B,A). This allows an attacker to
 * rotate to keys in a different order than pre-committed in the inception event.
 * <p>
 * ATTACK SCENARIO:
 * 1. Alice creates inception with nextKeys=[KeyA, KeyB, KeyC] with weighted threshold [1/2, 1/4, 1/4]
 * 2. Due to XOR commutativity, [KeyC, KeyB, KeyA] produces the same digest
 * 3. Attacker rotates to [KeyC, KeyB, KeyA] with threshold [1/4, 1/4, 1/2]
 * 4. KeyC now has weight 1/2 instead of 1/4 - threshold enforcement is bypassed
 * <p>
 * These tests will FAIL until the vulnerability is fixed by replacing XOR
 * with an order-dependent hash function.
 *
 * @author hal.hildebrand
 */
public class KeyConfigurationDigesterSecurityTest {

    private SecureRandom       secureRandom;
    private DigestAlgorithm    digestAlgo;
    private SignatureAlgorithm sigAlgo;

    @BeforeEach
    public void setup() throws Exception {
        secureRandom = SecureRandom.getInstance("SHA1PRNG");
        secureRandom.setSeed(new byte[] { 0x42, 0x13, 0x37 }); // Deterministic for test reproducibility

        sigAlgo = SignatureAlgorithm.DEFAULT;
        digestAlgo = DigestAlgorithm.DEFAULT;
    }

    /**
     * Test that different key orders produce different digests.
     * <p>
     * This test creates three distinct keys [A, B, C] and computes digests
     * for both the original order and the reversed order [C, B, A].
     * <p>
     * EXPECTED: The digests should be DIFFERENT (order matters)
     * CURRENT BEHAVIOR: The digests are IDENTICAL due to XOR commutativity
     * <p>
     * This test will FAIL until the vulnerability is fixed.
     */
    @Test
    void differentKeyOrdersShouldProduceDifferentDigests() {
        // Create 3 distinct keys
        var keyPairA = sigAlgo.generateKeyPair(secureRandom);
        var keyPairB = sigAlgo.generateKeyPair(secureRandom);
        var keyPairC = sigAlgo.generateKeyPair(secureRandom);

        var keyA = keyPairA.getPublic();
        var keyB = keyPairB.getPublic();
        var keyC = keyPairC.getPublic();

        // Create two lists with keys in different orders
        var keysOriginalOrder = List.of(keyA, keyB, keyC);
        var keysReversedOrder = List.of(keyC, keyB, keyA);

        // Use simple unweighted threshold for this test
        var threshold = SigningThreshold.unweighted(2);

        // Compute digests for both orderings
        var digestOriginal = KeyConfigurationDigester.digest(threshold, keysOriginalOrder, digestAlgo);
        var digestReversed = KeyConfigurationDigester.digest(threshold, keysReversedOrder, digestAlgo);

        // EXPECTED BEHAVIOR: Digests should be DIFFERENT because order matters
        // ACTUAL BEHAVIOR: Digests are IDENTICAL due to XOR commutativity vulnerability
        // This assertion will FAIL, demonstrating the vulnerability
        assertNotEquals(digestOriginal, digestReversed,
                        "SECURITY VULNERABILITY: Different key orders produced identical digests! " +
                        "This allows attackers to reorder keys and bypass threshold enforcement. " +
                        "Original: " + keysOriginalOrder + ", Reversed: " + keysReversedOrder);
    }

    /**
     * Test the complete attack scenario where weighted threshold enforcement is bypassed.
     * <p>
     * ATTACK FLOW:
     * 1. Alice creates inception event with:
     *    - nextKeys = [KeyA, KeyB, KeyC]
     *    - threshold = [1/2, 1/4, 1/4] (KeyA requires 1/2 weight to sign)
     * 2. Attacker observes the nextKeyConfigurationDigest
     * 3. Attacker rotates to [KeyC, KeyB, KeyA] with threshold [1/4, 1/4, 1/2]
     * 4. Due to XOR commutativity, the digest matches!
     * 5. Now KeyC has 1/2 weight instead of 1/4 - threshold bypassed
     * <p>
     * This test will FAIL until the vulnerability is fixed.
     */
    @Test
    void weightedThresholdCannotBeBypassedByKeyReordering() {
        // Setup: Create 3 keys for weighted threshold
        var keyPairA = sigAlgo.generateKeyPair(secureRandom);
        var keyPairB = sigAlgo.generateKeyPair(secureRandom);
        var keyPairC = sigAlgo.generateKeyPair(secureRandom);

        var keyA = keyPairA.getPublic();
        var keyB = keyPairB.getPublic();
        var keyC = keyPairC.getPublic();

        // Original configuration: KeyA gets 1/2 weight (dominant), B and C get 1/4 each
        var keysLegitimate = List.of(keyA, keyB, keyC);
        var thresholdLegitimate = SigningThreshold.weighted("1/2", "1/4", "1/4");

        // Compute digest for legitimate configuration
        var digestLegitimate = KeyConfigurationDigester.digest(thresholdLegitimate, keysLegitimate, digestAlgo);

        // ATTACK: Reorder keys to give KeyC the 1/2 weight instead
        // Attacker wants: [KeyC, KeyB, KeyA] with threshold [1/2, 1/4, 1/4]
        // This would make KeyC dominant instead of KeyA
        var keysAttack = List.of(keyC, keyB, keyA);
        var thresholdAttack = SigningThreshold.weighted("1/2", "1/4", "1/4");

        // Compute digest for attack configuration
        var digestAttack = KeyConfigurationDigester.digest(thresholdAttack, keysAttack, digestAlgo);

        // EXPECTED BEHAVIOR: Digests should be DIFFERENT
        // The attacker's reordering should be detected because the key order changed
        //
        // ACTUAL BEHAVIOR: Due to XOR commutativity, the digests might match
        // even though the weighted threshold enforcement is completely different!
        //
        // This assertion will FAIL, demonstrating the weighted threshold bypass vulnerability
        assertNotEquals(digestLegitimate, digestAttack,
                        "CRITICAL SECURITY VULNERABILITY: Attacker bypassed weighted threshold enforcement! " +
                        "Legitimate config: " + keysLegitimate + " with weights " + thresholdLegitimate + ". " +
                        "Attack config: " + keysAttack + " with weights " + thresholdAttack + ". " +
                        "Both produced digest: " + digestLegitimate + ". " +
                        "KeyC gained dominant weight without detection!");
    }

    /**
     * Test that any permutation of keys produces different digests.
     * <p>
     * This is a comprehensive test that checks multiple permutations
     * to ensure the digest function is sensitive to ordering.
     * <p>
     * This test will FAIL until the vulnerability is fixed.
     */
    @Test
    void allKeyPermutationsShouldProduceDifferentDigests() {
        // Create 3 distinct keys
        var keyPairA = sigAlgo.generateKeyPair(secureRandom);
        var keyPairB = sigAlgo.generateKeyPair(secureRandom);
        var keyPairC = sigAlgo.generateKeyPair(secureRandom);

        var keyA = keyPairA.getPublic();
        var keyB = keyPairB.getPublic();
        var keyC = keyPairC.getPublic();

        var threshold = SigningThreshold.unweighted(2);

        // Generate all 6 permutations of 3 keys
        var permutations = List.of(
            List.of(keyA, keyB, keyC),
            List.of(keyA, keyC, keyB),
            List.of(keyB, keyA, keyC),
            List.of(keyB, keyC, keyA),
            List.of(keyC, keyA, keyB),
            List.of(keyC, keyB, keyA)
        );

        // Compute digests for all permutations
        var digests = new ArrayList<Digest>();
        for (var permutation : permutations) {
            var digest = KeyConfigurationDigester.digest(threshold, permutation, digestAlgo);
            digests.add(digest);
        }

        // EXPECTED: All 6 digests should be different (order-sensitive)
        // ACTUAL: Due to XOR commutativity, many or all will be identical
        // This assertion will FAIL
        for (int i = 0; i < digests.size(); i++) {
            for (int j = i + 1; j < digests.size(); j++) {
                assertNotEquals(digests.get(i), digests.get(j),
                                String.format("VULNERABILITY: Permutations %d and %d produced identical digests! " +
                                              "Permutation %d: %s, Permutation %d: %s",
                                              i, j, i, permutations.get(i), j, permutations.get(j)));
            }
        }
    }

    /**
     * Test that digests are deterministic for the same key order.
     * <p>
     * This test verifies that the digest function is deterministic
     * (same input always produces same output), which should remain
     * true even after fixing the XOR vulnerability.
     * <p>
     * This test should PASS both before and after the fix.
     */
    @Test
    void sameKeyOrderShouldProduceSameDigest() {
        var keyPairA = sigAlgo.generateKeyPair(secureRandom);
        var keyPairB = sigAlgo.generateKeyPair(secureRandom);
        var keyPairC = sigAlgo.generateKeyPair(secureRandom);

        var keys = List.of(keyPairA.getPublic(), keyPairB.getPublic(), keyPairC.getPublic());
        var threshold = SigningThreshold.weighted("1/2", "1/4", "1/4");

        // Compute digest twice for the same configuration
        var digest1 = KeyConfigurationDigester.digest(threshold, keys, digestAlgo);
        var digest2 = KeyConfigurationDigester.digest(threshold, keys, digestAlgo);

        // This should always pass - determinism is required
        assertEquals(digest1, digest2,
                     "Digest function must be deterministic: same input should produce same output");
    }

    /**
     * Test that different thresholds with same keys produce different digests.
     * <p>
     * This verifies that the threshold configuration is properly included
     * in the digest computation.
     * <p>
     * This test should PASS both before and after the fix.
     */
    @Test
    void differentThresholdsShouldProduceDifferentDigests() {
        var keyPairA = sigAlgo.generateKeyPair(secureRandom);
        var keyPairB = sigAlgo.generateKeyPair(secureRandom);
        var keyPairC = sigAlgo.generateKeyPair(secureRandom);

        var keys = List.of(keyPairA.getPublic(), keyPairB.getPublic(), keyPairC.getPublic());

        var threshold1 = SigningThreshold.weighted("1/2", "1/4", "1/4");
        var threshold2 = SigningThreshold.weighted("1/3", "1/3", "1/3");

        var digest1 = KeyConfigurationDigester.digest(threshold1, keys, digestAlgo);
        var digest2 = KeyConfigurationDigester.digest(threshold2, keys, digestAlgo);

        // Different thresholds should produce different digests
        assertNotEquals(digest1, digest2,
                        "Different threshold configurations should produce different digests");
    }

    /**
     * Demonstrates the XOR commutativity property directly.
     * <p>
     * This test shows that XOR(A, B, C) = XOR(C, B, A) by computing
     * the XOR of digests in different orders.
     * <p>
     * This test documents the root cause of the vulnerability.
     */
    @Test
    void demonstrateXorCommutativity() {
        var keyPairA = sigAlgo.generateKeyPair(secureRandom);
        var keyPairB = sigAlgo.generateKeyPair(secureRandom);
        var keyPairC = sigAlgo.generateKeyPair(secureRandom);

        // Get byte-level digests of the keys
        var digestA = digestAlgo.digest(keyPairA.getPublic().getEncoded());
        var digestB = digestAlgo.digest(keyPairB.getPublic().getEncoded());
        var digestC = digestAlgo.digest(keyPairC.getPublic().getEncoded());

        // XOR in original order: A ⊕ B ⊕ C
        var xorABC = digestA.xor(digestB).xor(digestC);

        // XOR in reversed order: C ⊕ B ⊕ A
        var xorCBA = digestC.xor(digestB).xor(digestA);

        // XOR is commutative - these will be equal
        assertEquals(xorABC, xorCBA,
                     "XOR is commutative: A⊕B⊕C = C⊕B⊕A. This is the root cause of the vulnerability.");

        // For comparison: sequential hash is NOT commutative
        var hashABC = digestAlgo.digest(
            digestAlgo.digest(digestA.getBytes(), digestB.getBytes()).getBytes(),
            digestC.getBytes()
        );
        var hashCBA = digestAlgo.digest(
            digestAlgo.digest(digestC.getBytes(), digestB.getBytes()).getBytes(),
            digestA.getBytes()
        );

        // Sequential hash respects order
        assertNotEquals(hashABC, hashCBA,
                        "Sequential hashing is NOT commutative: H(H(A,B),C) ≠ H(H(C,B),A). " +
                        "This would be the correct approach.");
    }
}
