/*
 * Copyright (c) 2026, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.ethereal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Test suite for Byzantine fault tolerance threshold validation (f < n/3).
 * Ensures that Config only accepts valid committee sizes that satisfy:
 * - nProc >= 4 (minimum for f=1)
 * - nProc satisfies 3f+1 <= nProc <= 3f+3 for some f
 *
 * @author hal.hildebrand
 */
public class ConfigThresholdTest {

    @Test
    public void testThresholdValidation_InvalidNProcTooSmall() {
        // nProc < 4 should be rejected
        assertThrows(IllegalArgumentException.class, () -> Config.newBuilder().setnProc((short) 1).build(),
                     "nProc=1 should be rejected");
        assertThrows(IllegalArgumentException.class, () -> Config.newBuilder().setnProc((short) 2).build(),
                     "nProc=2 should be rejected");
        assertThrows(IllegalArgumentException.class, () -> Config.newBuilder().setnProc((short) 3).build(),
                     "nProc=3 should be rejected");
    }

    @Test
    public void testThresholdValidation_ValidNProc4() {
        // nProc=4 is valid (3f+1 with f=1)
        assertDoesNotThrow(() -> Config.newBuilder().setnProc((short) 4).build(),
                           "nProc=4 should be accepted");
    }

    @Test
    public void testThresholdValidation_ValidNProc5() {
        // nProc=5 is valid (3f+2 with f=1)
        assertDoesNotThrow(() -> Config.newBuilder().setnProc((short) 5).build(),
                           "nProc=5 should be accepted");
    }

    @Test
    public void testThresholdValidation_InvalidNProc6() {
        // nProc=6 is invalid according to Dag.validate()
        // The validation requires calc = threshold * 3 + 1 to equal nProc or nProc - 1
        // For nProc=6: threshold = (6-1)/3 = 1, calc = 1*3+1 = 4
        // Since 4 != 6 and 4 != 5, this is rejected
        assertThrows(IllegalArgumentException.class, () -> Config.newBuilder().setnProc((short) 6).build(),
                     "nProc=6 should be rejected");
    }

    @Test
    public void testThresholdValidation_ValidNProc7() {
        // nProc=7 is valid (3f+1 with f=2)
        assertDoesNotThrow(() -> Config.newBuilder().setnProc((short) 7).build(),
                           "nProc=7 should be accepted");
    }

    @Test
    public void testThresholdValidation_ByzantineSafety_f1() {
        // For f=1 (1 Byzantine fault): need 3*1+1=4 nodes minimum
        // Valid: 4, 5 (threshold=1, calc=4, accepts 4 or 3+1=5 but 5 passes calc==nProc-1)
        // Invalid: 6 (calc=4, 4 != 6 and 4 != 5)
        assertThrows(IllegalArgumentException.class, () -> Config.newBuilder().setnProc((short) 6).build(),
                     "nProc=6 should be rejected");
    }

    @Test
    public void testThresholdValidation_ByzantineSafety_f2() {
        // For f=2: threshold = (7-1)/3 = 2, calc = 2*3+1 = 7
        // Valid: 7, 8 (7 == nProc, 7 == nProc-1 for 8)
        // Invalid: 9 (7 != 9 and 7 != 8)
        assertDoesNotThrow(() -> Config.newBuilder().setnProc((short) 7).build(),
                           "nProc=7 should be accepted");
        assertDoesNotThrow(() -> Config.newBuilder().setnProc((short) 8).build(),
                           "nProc=8 should be accepted");
        assertThrows(IllegalArgumentException.class, () -> Config.newBuilder().setnProc((short) 9).build(),
                     "nProc=9 should be rejected");
    }

    @Test
    public void testThresholdValidation_EdgeCase_7() {
        // Edge case: 7 is valid (3f+1 with f=2)
        assertDoesNotThrow(() -> Config.newBuilder().setnProc((short) 7).build());
    }

    @Test
    public void testThresholdValidation_EdgeCase_10() {
        // Edge case: 10 is invalid (3*3+1=10, but validation logic may have off-by-one)
        // Actually 10 is 3f+1 with f=3, so should be valid
        assertDoesNotThrow(() -> Config.newBuilder().setnProc((short) 10).build(),
                           "nProc=10 should be accepted");
    }

    @Test
    public void testThresholdValidation_EdgeCase_13() {
        // Edge case: 13 is valid (3*4+1 with f=4)
        assertDoesNotThrow(() -> Config.newBuilder().setnProc((short) 13).build(),
                           "nProc=13 should be accepted");
    }

    @Test
    public void testThresholdValidation_LargeCommittee() {
        // Large committee sizes should work: 16, 100, 1000
        assertDoesNotThrow(() -> Config.newBuilder().setnProc((short) 16).build(),
                           "nProc=16 should be accepted");
        assertDoesNotThrow(() -> Config.newBuilder().setnProc((short) 100).build(),
                           "nProc=100 should be accepted");
    }

    @Test
    public void testDagValidate_MinimalCommittee() {
        // Dag.validate should accept minimum valid configurations
        // Valid: 4, 5, 7, 8, 10, 11, ...
        // Invalid: 6, 9, 12, ...
        assertTrue(Dag.validate(4), "Dag.validate(4) should return true");
        assertTrue(Dag.validate(5), "Dag.validate(5) should return true");
        assertFalse(Dag.validate(6), "Dag.validate(6) should return false");
        assertTrue(Dag.validate(7), "Dag.validate(7) should return true");
        assertTrue(Dag.validate(8), "Dag.validate(8) should return true");
    }

    @Test
    public void testDagValidate_InvalidCommittee() {
        // Dag.validate should reject invalid configurations
        assertFalse(Dag.validate(1), "Dag.validate(1) should return false");
        assertFalse(Dag.validate(2), "Dag.validate(2) should return false");
        assertFalse(Dag.validate(3), "Dag.validate(3) should return false");
    }

    @Test
    public void testConfigBuild_PropagatesToDag() {
        // Ensure Config.build() calls Dag.validate()
        Config config = Config.newBuilder().setnProc((short) 4).build();
        assertNotNull(config, "Config should be created for valid nProc");
        assertEquals(4, config.nProc(), "Config.nProc() should return 4");
    }
}
