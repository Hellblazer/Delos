/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ValidationStatus enum (Phase 1A-3-C.1).
 */
class ValidationStatusTest {

    @Test
    void testAcceptedStatus() {
        assertTrue(ValidationStatus.ACCEPTED.isAccepted());
        assertFalse(ValidationStatus.ACCEPTED.isRejected());
        assertFalse(ValidationStatus.ACCEPTED.isByzantineIndicator());
    }

    @Test
    void testBufferedStatus() {
        assertFalse(ValidationStatus.BUFFERED.isAccepted());
        assertFalse(ValidationStatus.BUFFERED.isRejected());
        assertFalse(ValidationStatus.BUFFERED.isByzantineIndicator());
    }

    @Test
    void testRejectedNotInCommittee() {
        var status = ValidationStatus.REJECTED_NOT_IN_COMMITTEE;
        assertFalse(status.isAccepted());
        assertTrue(status.isRejected());
        assertTrue(status.isByzantineIndicator());
    }

    @Test
    void testRejectedDuplicate() {
        var status = ValidationStatus.REJECTED_DUPLICATE;
        assertFalse(status.isAccepted());
        assertTrue(status.isRejected());
        assertFalse(status.isByzantineIndicator());
    }

    @Test
    void testRejectedQuorumMet() {
        var status = ValidationStatus.REJECTED_QUORUM_MET;
        assertFalse(status.isAccepted());
        assertTrue(status.isRejected());
        assertFalse(status.isByzantineIndicator());
    }

    @Test
    void testRejectedInvalidSignature() {
        var status = ValidationStatus.REJECTED_INVALID_SIGNATURE;
        assertFalse(status.isAccepted());
        assertTrue(status.isRejected());
        assertTrue(status.isByzantineIndicator());
    }

    @Test
    void testRejectedByzantine() {
        var status = ValidationStatus.REJECTED_BYZANTINE;
        assertFalse(status.isAccepted());
        assertTrue(status.isRejected());
        assertTrue(status.isByzantineIndicator());
    }

    @Test
    void testRejectedEpochMismatch() {
        var status = ValidationStatus.REJECTED_EPOCH_MISMATCH;
        assertFalse(status.isAccepted());
        assertTrue(status.isRejected());
        assertFalse(status.isByzantineIndicator());
    }

    @Test
    void testRejectedViewMismatch() {
        var status = ValidationStatus.REJECTED_VIEW_MISMATCH;
        assertFalse(status.isAccepted());
        assertTrue(status.isRejected());
        assertFalse(status.isByzantineIndicator());
    }

    @Test
    void testRejectedError() {
        var status = ValidationStatus.REJECTED_ERROR;
        assertFalse(status.isAccepted());
        assertTrue(status.isRejected());
        assertFalse(status.isByzantineIndicator());
    }

    @Test
    void testAllStatusesPresent() {
        // Ensure all expected statuses exist
        assertEquals(10, ValidationStatus.values().length);

        // Check each one
        assertNotNull(ValidationStatus.valueOf("ACCEPTED"));
        assertNotNull(ValidationStatus.valueOf("REJECTED_NOT_IN_COMMITTEE"));
        assertNotNull(ValidationStatus.valueOf("REJECTED_DUPLICATE"));
        assertNotNull(ValidationStatus.valueOf("REJECTED_QUORUM_MET"));
        assertNotNull(ValidationStatus.valueOf("REJECTED_INVALID_SIGNATURE"));
        assertNotNull(ValidationStatus.valueOf("REJECTED_BYZANTINE"));
        assertNotNull(ValidationStatus.valueOf("REJECTED_EPOCH_MISMATCH"));
        assertNotNull(ValidationStatus.valueOf("REJECTED_VIEW_MISMATCH"));
        assertNotNull(ValidationStatus.valueOf("BUFFERED"));
        assertNotNull(ValidationStatus.valueOf("REJECTED_ERROR"));
    }

    @Test
    void testByzantineIndicatorsCount() {
        // Count Byzantine indicators - should be exactly 3
        var byzantineIndicatorCount = 0;
        for (var status : ValidationStatus.values()) {
            if (status.isByzantineIndicator()) {
                byzantineIndicatorCount++;
            }
        }
        assertEquals(3, byzantineIndicatorCount);
    }

    @Test
    void testRejectedStatusesCount() {
        // Count rejected statuses - should be exactly 8
        var rejectedCount = 0;
        for (var status : ValidationStatus.values()) {
            if (status.isRejected()) {
                rejectedCount++;
            }
        }
        assertEquals(8, rejectedCount);
    }
}
