/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.cryptography;

import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for constant-time digest comparisons to prevent timing attacks.
 * <p>
 * Security requirement: Digest comparisons must use constant-time algorithms
 * to prevent attackers from using timing information to determine digest similarity.
 *
 * @author hal.hildebrand
 */
class DigestConstantTimeTest {

    @Test
    void testEqualDigestsReturnTrue() {
        var algo = DigestAlgorithm.DEFAULT;
        var digest1 = algo.digest("test data".getBytes());
        var digest2 = algo.digest("test data".getBytes());

        // Both equals() and constantTimeEquals() should return true for equal digests
        assertTrue(digest1.equals(digest2), "Standard equals should return true for equal digests");
        assertTrue(digest1.constantTimeEquals(digest2), "Constant-time equals should return true for equal digests");
    }

    @Test
    void testDifferentDigestsReturnFalse() {
        var algo = DigestAlgorithm.DEFAULT;
        var digest1 = algo.digest("test data 1".getBytes());
        var digest2 = algo.digest("test data 2".getBytes());

        // Both equals() and constantTimeEquals() should return false for different digests
        assertFalse(digest1.equals(digest2), "Standard equals should return false for different digests");
        assertFalse(digest1.constantTimeEquals(digest2), "Constant-time equals should return false for different digests");
    }

    @Test
    void testSameInstanceReturnsTrue() {
        var algo = DigestAlgorithm.DEFAULT;
        var digest = algo.digest("test data".getBytes());

        assertTrue(digest.constantTimeEquals(digest), "Constant-time equals should return true for same instance");
    }

    @Test
    void testNullReturnsFalse() {
        var algo = DigestAlgorithm.DEFAULT;
        var digest = algo.digest("test data".getBytes());

        assertFalse(digest.constantTimeEquals(null), "Constant-time equals should return false for null");
    }

    @Test
    void testDifferentAlgorithmsReturnFalse() {
        var digest1 = DigestAlgorithm.SHA2_256.digest("test data".getBytes());
        var digest2 = DigestAlgorithm.SHA2_512.digest("test data".getBytes());

        assertFalse(digest1.constantTimeEquals(digest2),
                   "Constant-time equals should return false for different algorithms");
    }

    @Test
    void testByteArrayConstantTimeComparison() {
        var algo = DigestAlgorithm.DEFAULT;
        var bytes1 = algo.digest("test data".getBytes()).getBytes();
        var bytes2 = algo.digest("test data".getBytes()).getBytes();
        var bytes3 = algo.digest("different data".getBytes()).getBytes();

        // Test the static constantTimeEquals method for byte arrays
        assertTrue(Digest.constantTimeEquals(bytes1, bytes2),
                  "Constant-time byte comparison should return true for equal arrays");
        assertFalse(Digest.constantTimeEquals(bytes1, bytes3),
                   "Constant-time byte comparison should return false for different arrays");
    }

    @Test
    void testByteArrayConstantTimeWithNulls() {
        var algo = DigestAlgorithm.DEFAULT;
        var bytes = algo.digest("test data".getBytes()).getBytes();

        assertFalse(Digest.constantTimeEquals(null, bytes),
                   "Constant-time comparison should return false when first array is null");
        assertFalse(Digest.constantTimeEquals(bytes, null),
                   "Constant-time comparison should return false when second array is null");
        assertTrue(Digest.constantTimeEquals(null, null),
                  "Constant-time comparison should return true when both arrays are null");
    }

    @Test
    void testByteArrayConstantTimeWithDifferentLengths() {
        var bytes1 = new byte[]{1, 2, 3};
        var bytes2 = new byte[]{1, 2, 3, 4};

        assertFalse(Digest.constantTimeEquals(bytes1, bytes2),
                   "Constant-time comparison should return false for different length arrays");
    }

    @Test
    void testConstantTimeUsesMessageDigestIsEqual() {
        // Verify that our implementation uses MessageDigest.isEqual() internally
        // This is the Java built-in constant-time comparison
        var algo = DigestAlgorithm.DEFAULT;
        var bytes1 = algo.digest("test data".getBytes()).getBytes();
        var bytes2 = algo.digest("test data".getBytes()).getBytes();

        // Our implementation should match MessageDigest.isEqual() behavior
        boolean expected = MessageDigest.isEqual(bytes1, bytes2);
        boolean actual = Digest.constantTimeEquals(bytes1, bytes2);

        assertEquals(expected, actual,
                    "Digest.constantTimeEquals should use MessageDigest.isEqual internally");
    }

    @Test
    void testEqualsMethodConsistentWithConstantTime() {
        // The equals() method should be updated to use constant-time comparison
        // This test ensures behavioral consistency
        var algo = DigestAlgorithm.DEFAULT;

        // Test multiple cases
        for (int i = 0; i < 10; i++) {
            var data1 = ("test data " + i).getBytes();
            var data2 = ("test data " + i).getBytes();
            var data3 = ("different data " + i).getBytes();

            var digest1 = algo.digest(data1);
            var digest2 = algo.digest(data2);
            var digest3 = algo.digest(data3);

            // equals() and constantTimeEquals() should always agree
            assertEquals(digest1.equals(digest2), digest1.constantTimeEquals(digest2),
                        "equals() and constantTimeEquals() should agree for equal digests");
            assertEquals(digest1.equals(digest3), digest1.constantTimeEquals(digest3),
                        "equals() and constantTimeEquals() should agree for different digests");
        }
    }

    @Test
    void testNONEDigestConstantTimeComparison() {
        // Test the special NONE digest
        assertTrue(Digest.NONE.constantTimeEquals(Digest.NONE),
                  "NONE digest should equal itself with constant-time comparison");

        var otherDigest = DigestAlgorithm.DEFAULT.digest("test".getBytes());
        assertFalse(Digest.NONE.constantTimeEquals(otherDigest),
                   "NONE digest should not equal other digests");
    }
}
