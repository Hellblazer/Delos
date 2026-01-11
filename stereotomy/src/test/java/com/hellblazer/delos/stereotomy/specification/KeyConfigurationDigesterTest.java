package com.hellblazer.delos.stereotomy.specification;

import static com.hellblazer.delos.cryptography.SigningThreshold.group;
import static com.hellblazer.delos.cryptography.SigningThreshold.unweighted;
import static com.hellblazer.delos.cryptography.SigningThreshold.weighted;
import static com.hellblazer.delos.stereotomy.identifier.spec.KeyConfigurationDigester.signingThresholdRepresentation;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.SigningThreshold;
import com.hellblazer.delos.stereotomy.identifier.spec.KeyConfigurationDigester;
import com.hellblazer.delos.utils.Hex;

public class KeyConfigurationDigesterTest {
    private static KeyPair key1;
    private static KeyPair key2;
    private static KeyPair key3;
    private static KeyPair key4;
    private static KeyPair key5;
    private static final DigestAlgorithm DIGEST_ALGORITHM = DigestAlgorithm.DEFAULT;

    @BeforeAll
    public static void setup() throws Exception {
        var gen = KeyPairGenerator.getInstance("EdDSA");
        key1 = gen.generateKeyPair();
        key2 = gen.generateKeyPair();
        key3 = gen.generateKeyPair();
        key4 = gen.generateKeyPair();
        key5 = gen.generateKeyPair();
    }

    @Test
    public void test__signingThresholdRepresentation__unweighted() {
        assertArrayEquals("1".getBytes(UTF_8), signingThresholdRepresentation(unweighted(1)));

        assertArrayEquals(Hex.hexNoPad(16).getBytes(UTF_8), signingThresholdRepresentation(unweighted(16)));

    }

    @Test
    public void test__signingThresholdRepresentation__weighted() {
        assertArrayEquals("1".getBytes(UTF_8), signingThresholdRepresentation(weighted("1")));

        assertArrayEquals("1,2,3".getBytes(UTF_8), signingThresholdRepresentation(weighted("1", "2", "3")));

        assertArrayEquals("1,2,3&4,5,6".getBytes(UTF_8),
                          signingThresholdRepresentation(weighted(group("1", "2", "3"), group("4", "5", "6"))));

        assertArrayEquals("1/2,1/3,1/4".getBytes(UTF_8), signingThresholdRepresentation(weighted("1/2", "1/3", "1/4")));

        assertArrayEquals("1,1/2,1/3&1,1/4,1/5,1/6".getBytes(UTF_8),
                          signingThresholdRepresentation(weighted(group("1", "1/2", "1/3"),
                                                                  group("1", "1/4", "1/5", "1/6"))));

    }

    // SECURITY TESTS: CRIT-1 - Pre-Rotation Key Order Permutation Attack
    // XOR is commutative, allowing attacker to reorder keys and violate weighted thresholds

    @Test
    public void testXorPermutationVulnerability_TwoKeys() {
        // ARRANGE: Create digest with two keys in one order
        var keys1 = List.of(key1.getPublic(), key2.getPublic());
        var digest1 = KeyConfigurationDigester.digest(unweighted(2), keys1, DIGEST_ALGORITHM);

        // ACT: Create digest with same keys in reversed order
        var keys2 = List.of(key2.getPublic(), key1.getPublic());
        var digest2 = KeyConfigurationDigester.digest(unweighted(2), keys2, DIGEST_ALGORITHM);

        // ASSERT: Due to XOR vulnerability, these are currently EQUAL
        // After fix, they MUST be DIFFERENT to prevent permutation attack
        assertNotEquals(digest1, digest2,
            "XOR permutation vulnerability: Same keys in different order produce same digest");
    }

    @Test
    public void testXorPermutationVulnerability_ThreeKeys() {
        var keys_123 = List.of(key1.getPublic(), key2.getPublic(), key3.getPublic());
        var keys_321 = List.of(key3.getPublic(), key2.getPublic(), key1.getPublic());

        var digest_123 = KeyConfigurationDigester.digest(unweighted(2), keys_123, DIGEST_ALGORITHM);
        var digest_321 = KeyConfigurationDigester.digest(unweighted(2), keys_321, DIGEST_ALGORITHM);

        assertNotEquals(digest_123, digest_321,
            "Three keys in different order must produce different digests");
    }

    @Test
    public void testXorPermutationVulnerability_AllPermutations() {
        var keys = List.of(key1.getPublic(), key2.getPublic(), key3.getPublic());

        // Generate some permutations
        var digests = new ArrayList<com.hellblazer.delos.cryptography.Digest>();

        // Original order
        digests.add(KeyConfigurationDigester.digest(unweighted(2), keys, DIGEST_ALGORITHM));

        // Reversed
        var reversed = new ArrayList<>(keys);
        Collections.reverse(reversed);
        digests.add(KeyConfigurationDigester.digest(unweighted(2), reversed, DIGEST_ALGORITHM));

        // Rotated
        var rotated = new ArrayList<>(keys);
        var first = rotated.remove(0);
        rotated.add(first);
        digests.add(KeyConfigurationDigester.digest(unweighted(2), rotated, DIGEST_ALGORITHM));

        // All three should be different - permutation must affect digest
        assertEquals(3, digests.stream().distinct().count(),
            "Different key orderings must produce different digests");
    }

    @Test
    public void testWeightedThresholdOrderSensitivity() {
        // CRIT-1 vulnerability particularly impacts weighted thresholds
        // Attacker could reorder keys to change their effective weights

        var keys_key1_first = List.of(key1.getPublic(), key2.getPublic());
        var keys_key2_first = List.of(key2.getPublic(), key1.getPublic());

        // Weighted: key1=2/3 weight, key2=1/3 weight
        var weighted_threshold = weighted("2/3", "1/3");

        var digest1 = KeyConfigurationDigester.digest(weighted_threshold, keys_key1_first, DIGEST_ALGORITHM);
        var digest2 = KeyConfigurationDigester.digest(weighted_threshold, keys_key2_first, DIGEST_ALGORITHM);

        assertNotEquals(digest1, digest2,
            "Weighted threshold must be order-sensitive to enforce weight assignment");
    }

    @Test
    public void testOrderSensitivityWithFiveKeys() {
        var keys = List.of(key1.getPublic(), key2.getPublic(), key3.getPublic(), key4.getPublic(),
                          key5.getPublic());

        // Collect digests from multiple permutations
        var digests = new ArrayList<com.hellblazer.delos.cryptography.Digest>();
        digests.add(KeyConfigurationDigester.digest(unweighted(3), keys, DIGEST_ALGORITHM));

        // Reverse
        var reversed = new ArrayList<>(keys);
        Collections.reverse(reversed);
        digests.add(KeyConfigurationDigester.digest(unweighted(3), reversed, DIGEST_ALGORITHM));

        // Last element first
        var rotated = new ArrayList<>(keys);
        var last = rotated.remove(rotated.size() - 1);
        rotated.add(0, last);
        digests.add(KeyConfigurationDigester.digest(unweighted(3), rotated, DIGEST_ALGORITHM));

        // Swap first two
        var swapped = new ArrayList<>(keys);
        Collections.swap(swapped, 0, 1);
        digests.add(KeyConfigurationDigester.digest(unweighted(3), swapped, DIGEST_ALGORITHM));

        // All permutations must produce different digests
        assertEquals(4, digests.stream().distinct().count(),
            "All key permutations must produce unique digests");
    }

}
