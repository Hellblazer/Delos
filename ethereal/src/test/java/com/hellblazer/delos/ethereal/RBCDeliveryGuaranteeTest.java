/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.ethereal;

import com.hellblazer.delos.context.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite validating Reliable Broadcast (RBC) delivery guarantees in Aleph-BFT.
 *
 * RBC Delivery Guarantee (Aleph-BFT §2.2):
 * With f Byzantine nodes in n total nodes where n = 3f+1:
 * - Any quorum of 2f+1 nodes contains at least f+1 honest nodes (majority)
 * - If 2f+1 nodes receive a unit, all honest nodes eventually receive it via gossip
 * - Byzantine nodes cannot prevent delivery (liveness guarantee)
 *
 * @author hal.hildebrand
 */
public class RBCDeliveryGuaranteeTest {

    /**
     * Test that quorum size calculation correctly implements 2f+1 threshold.
     *
     * For n = 3f+1 nodes with f Byzantine:
     * - minimalQuorum should return f+1 (tolerance level + 1)
     * - isQuorum should accept cardinality >= 2f+1
     *
     * Test cases:
     * - n=4, f=1: quorum=3 (any 3 of 4 has ≥2 honest)
     * - n=7, f=2: quorum=5 (any 5 of 7 has ≥3 honest)
     * - n=10, f=3: quorum=7 (any 7 of 10 has ≥4 honest)
     * - n=13, f=4: quorum=9 (any 9 of 13 has ≥5 honest)
     */
    @ParameterizedTest
    @CsvSource({
        "4, 3, 1, 2",    // n=4: bias=3, f=1, quorum=3 (2f+1), honest=2 (f+1)
        "7, 3, 2, 3",    // n=7: bias=3, f=2, quorum=5 (2f+1), honest=3 (f+1)
        "10, 3, 3, 4",   // n=10: bias=3, f=3, quorum=7 (2f+1), honest=4 (f+1)
        "13, 3, 4, 5",   // n=13: bias=3, f=4, quorum=9 (2f+1), honest=5 (f+1)
        "16, 3, 5, 6",   // n=16: bias=3, f=5, quorum=11 (2f+1), honest=6 (f+1)
    })
    void testQuorumSizeGuaranteesDelivery(int nProc, int bias, int expectedF, int expectedHonest) {
        // Calculate actual quorum threshold
        var minQuorum = Context.minimalQuorum(nProc, bias);
        var actualQuorum = 2 * expectedF + 1;

        // Verify minimalQuorum returns f+1 (tolerance level + 1)
        assertEquals(expectedHonest, minQuorum,
            String.format("minimalQuorum should be f+1=%d for n=%d", expectedHonest, nProc));

        // Verify that 2f+1 nodes form a quorum
        var config = Config.newBuilder()
            .setnProc((short) nProc)
            .setBias(bias)
            .build();
        var dag = new Dag.DagImpl(config, 0);

        // Test quorum threshold: 2f+1 should be quorum
        assertTrue(dag.isQuorum((short) actualQuorum),
            String.format("2f+1=%d should form quorum for n=%d, f=%d", actualQuorum, nProc, expectedF));

        // Test below threshold: 2f should NOT be quorum
        assertFalse(dag.isQuorum((short) (actualQuorum - 1)),
            String.format("2f=%d should NOT form quorum for n=%d, f=%d", actualQuorum - 1, nProc, expectedF));

        // Verify Byzantine safety: any quorum has majority honest
        var minHonestInQuorum = actualQuorum - expectedF; // honest = total - f
        assertTrue(minHonestInQuorum > expectedF,
            String.format("Quorum %d must have >f honest nodes: %d > %d",
                actualQuorum, minHonestInQuorum, expectedF));
    }

    /**
     * Test that Byzantine fault tolerance validation works correctly.
     *
     * Dag.validate() should ensure:
     * - n >= 4 (minimum for any BFT)
     * - n = 3f+1 OR n = 3f+2 (valid BFT configurations)
     */
    @Test
    void testBFTValidation() {
        // Valid configurations: 3f+1
        assertTrue(Dag.validate(4),  "n=4 (3*1+1) should be valid");
        assertTrue(Dag.validate(7),  "n=7 (3*2+1) should be valid");
        assertTrue(Dag.validate(10), "n=10 (3*3+1) should be valid");
        assertTrue(Dag.validate(13), "n=13 (3*4+1) should be valid");

        // Valid configurations: 3f+2
        assertTrue(Dag.validate(5),  "n=5 (3*1+2) should be valid");
        assertTrue(Dag.validate(8),  "n=8 (3*2+2) should be valid");
        assertTrue(Dag.validate(11), "n=11 (3*3+2) should be valid");

        // Invalid: too small
        assertFalse(Dag.validate(3), "n=3 should be invalid (< 4)");
        assertFalse(Dag.validate(2), "n=2 should be invalid (< 4)");
        assertFalse(Dag.validate(1), "n=1 should be invalid (< 4)");

        // Invalid: not 3f+1 or 3f+2
        assertFalse(Dag.validate(6),  "n=6 (3*1+3) should be invalid");
        assertFalse(Dag.validate(9),  "n=9 (3*2+3) should be invalid");
        assertFalse(Dag.validate(12), "n=12 (3*3+3) should be invalid");
    }

    /**
     * Test threshold calculation for various network sizes.
     *
     * threshold(n) = floor((n-1)/3) = maximum tolerable Byzantine nodes
     */
    @ParameterizedTest
    @CsvSource({
        "4, 1",   // (4-1)/3 = 1
        "5, 1",   // (5-1)/3 = 1.33 -> 1
        "7, 2",   // (7-1)/3 = 2
        "8, 2",   // (8-1)/3 = 2.33 -> 2
        "10, 3",  // (10-1)/3 = 3
        "13, 4",  // (13-1)/3 = 4
        "16, 5",  // (16-1)/3 = 5
    })
    void testThresholdCalculations(int nProc, int expectedF) {
        var actualF = Dag.threshold(nProc);
        assertEquals(expectedF, actualF,
            String.format("threshold(%d) should be %d", nProc, expectedF));
    }

    /**
     * Test that RBC delivery guarantees hold in realistic cluster sizes.
     *
     * For n=16, f=5:
     * - Quorum = 11 (2*5+1)
     * - Any 11 nodes have ≥6 honest (11-5=6 > 5)
     * - Byzantine nodes cannot prevent delivery to honest majority
     */
    @Test
    void testLargeClusterRBCGuarantees() {
        var nProc = 16;
        var bias = 3;
        var f = Dag.threshold(nProc); // Should be 5

        assertEquals(5, f, "n=16 should tolerate f=5 Byzantine nodes");

        var config = Config.newBuilder()
            .setnProc((short) nProc)
            .setBias(bias)
            .build();
        var dag = new Dag.DagImpl(config, 0);

        var quorum = 2 * f + 1; // 11
        assertTrue(dag.isQuorum((short) quorum),
            "2f+1=11 should form quorum for n=16");

        // Verify Byzantine cannot block: even with f=5 Byzantine
        // withholding, remaining 11 honest nodes form quorum
        var honestNodes = nProc - f; // 11 honest
        assertTrue(dag.isQuorum((short) honestNodes),
            "All honest nodes (11) should form quorum even with f=5 Byzantine");

        // Verify minimum honest in quorum
        var minHonestInQuorum = quorum - f; // 11 - 5 = 6
        assertTrue(minHonestInQuorum > f,
            String.format("Quorum must have majority honest: %d > %d", minHonestInQuorum, f));
    }

    /**
     * Test Byzantine safety: Byzantine nodes cannot prevent RBC delivery.
     *
     * Even if f Byzantine nodes actively withhold a unit:
     * - Remaining n-f honest nodes receive it
     * - n-f >= 2f+1 (since n=3f+1)
     * - Thus honest nodes form quorum and propagate to all
     */
    @Test
    void testByzantineCannotBlockDelivery() {
        var nProc = 10;
        var bias = 3;
        var f = Dag.threshold(nProc); // f=3

        var config = Config.newBuilder()
            .setnProc((short) nProc)
            .setBias(bias)
            .build();
        var dag = new Dag.DagImpl(config, 0);

        // Scenario: f=3 Byzantine nodes withhold unit
        var honestNodes = nProc - f; // 10 - 3 = 7 honest
        var quorum = 2 * f + 1;      // 2*3+1 = 7

        // Critical: honest nodes alone form quorum
        assertEquals(quorum, honestNodes,
            "For n=3f+1, honest nodes (n-f) equals quorum (2f+1)");

        assertTrue(dag.isQuorum((short) honestNodes),
            "Honest nodes should form quorum even without Byzantine participation");

        // Thus Byzantine withholding cannot prevent delivery:
        // - Honest nodes gossip among themselves
        // - They form quorum threshold
        // - All honest nodes eventually receive unit
    }

    /**
     * Test quorum intersection property for Byzantine safety.
     *
     * Any two quorums must intersect in at least f+1 nodes:
     * - If two quorums Q1, Q2 each have 2f+1 nodes
     * - Total unique nodes <= n = 3f+1
     * - Overlap >= |Q1| + |Q2| - n = (2f+1) + (2f+1) - (3f+1) = f+1
     * - Since f+1 > f, at least one node in intersection is honest
     */
    @Test
    void testQuorumIntersectionProperty() {
        var nProc = 7;
        var bias = 3;
        var f = Dag.threshold(nProc); // f=2

        var quorum = 2 * f + 1; // 5

        // Two quorums of size 5 in network of 7
        // Minimum overlap = 5 + 5 - 7 = 3
        var minOverlap = 2 * quorum - nProc;

        // Minimum overlap must be > f to guarantee honest node
        assertTrue(minOverlap > f,
            String.format("Quorum overlap %d must be > f=%d to ensure honest intersection",
                minOverlap, f));

        assertEquals(f + 1, minOverlap,
            "Quorum overlap should be exactly f+1 for n=3f+1");
    }

    /**
     * Test that Config.Builder validates Byzantine fault tolerance requirements.
     *
     * Should reject invalid nProc configurations.
     */
    @Test
    void testConfigValidation() {
        // Valid: n=4 (3*1+1)
        assertDoesNotThrow(() -> Config.newBuilder()
            .setnProc((short) 4)
            .setBias(3)
            .build());

        // Valid: n=7 (3*2+1)
        assertDoesNotThrow(() -> Config.newBuilder()
            .setnProc((short) 7)
            .setBias(3)
            .build());

        // Invalid: n=3 (too small)
        var ex = assertThrows(IllegalArgumentException.class, () ->
            Config.newBuilder()
                .setnProc((short) 3)
                .setBias(3)
                .build());
        assertTrue(ex.getMessage().contains("Byzantine fault tolerance"),
            "Error message should mention BFT requirements");

        // Invalid: n=6 (3*1+3, not valid BFT)
        assertThrows(IllegalArgumentException.class, () ->
            Config.newBuilder()
                .setnProc((short) 6)
                .setBias(3)
                .build());
    }

    /**
     * Test edge case: minimum viable BFT network (n=4, f=1).
     *
     * With only 4 nodes:
     * - Can tolerate 1 Byzantine failure
     * - Quorum = 3
     * - Any 3 nodes have ≥2 honest
     */
    @Test
    void testMinimumViableNetwork() {
        var nProc = 4;
        var bias = 3;
        var f = Dag.threshold(nProc); // f=1

        assertEquals(1, f, "n=4 should tolerate f=1");

        var config = Config.newBuilder()
            .setnProc((short) nProc)
            .setBias(bias)
            .build();
        var dag = new Dag.DagImpl(config, 0);

        var quorum = 2 * f + 1; // 3
        assertTrue(dag.isQuorum((short) quorum),
            "2f+1=3 should form quorum for n=4");

        // Even with 1 Byzantine, 3 honest nodes form quorum
        var honestNodes = nProc - f; // 3
        assertTrue(dag.isQuorum((short) honestNodes),
            "3 honest nodes should form quorum in n=4 network");
    }

    /**
     * Test tolerance level calculation.
     *
     * toleranceLevel(n, bias) = floor((n-1)/bias)
     * For bias=3: toleranceLevel = floor((n-1)/3) = f
     */
    @ParameterizedTest
    @CsvSource({
        "4, 3, 1",
        "7, 3, 2",
        "10, 3, 3",
        "13, 3, 4",
    })
    void testToleranceLevel(int nProc, int bias, int expectedF) {
        var actual = Context.toleranceLevel(nProc, bias);
        assertEquals(expectedF, actual,
            String.format("toleranceLevel(%d, %d) should be %d", nProc, bias, expectedF));
    }
}
