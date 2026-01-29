/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.validation;

import com.codahale.metrics.MetricRegistry;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.witness.WitnessContext;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for RuntimeByzantineValidator (Phase 1A-3-C.1).
 * <p>
 * Verifies:
 * - Committee membership validation
 * - Early quorum rejection
 * - Byzantine member rejection
 * - Duplicate detection
 * - State tracking
 */
class RuntimeByzantineValidatorTest {

    @Mock
    private WitnessContext mockWitnessContext;

    @Mock
    private ByzantineWitnessDetector mockByzantineDetector;

    @Mock
    private EventCoordinates mockEvent;

    private RuntimeByzantineValidator validator;
    private Identifier validMember;
    private Identifier invalidMember;
    private Identifier byzantineMember;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        // Create test identifiers
        validMember = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("valid".getBytes()));
        invalidMember = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("invalid".getBytes()));
        byzantineMember = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("byzantine".getBytes()));

        // Configure mock event
        when(mockEvent.getDigest()).thenReturn(DigestAlgorithm.DEFAULT.digest("event1".getBytes()));
        when(mockEvent.getSequenceNumber()).thenReturn(ULong.valueOf(1));

        // Configure mock WitnessContext
        when(mockWitnessContext.isCommitteeMember(validMember, mockEvent)).thenReturn(true);
        when(mockWitnessContext.isCommitteeMember(invalidMember, mockEvent)).thenReturn(false);
        when(mockWitnessContext.isCommitteeMember(byzantineMember, mockEvent)).thenReturn(true);

        // Configure mock Byzantine detector
        when(mockByzantineDetector.isMarkedForExclusion(any())).thenReturn(false);
        when(mockByzantineDetector.shouldShun(any())).thenReturn(false);
        when(mockByzantineDetector.isSuspicious(any())).thenReturn(false);

        // Byzantine member marked for exclusion
        when(mockByzantineDetector.isMarkedForExclusion(byzantineMember)).thenReturn(true);

        validator = new RuntimeByzantineValidator(mockWitnessContext, mockByzantineDetector);
    }

    @Test
    void testAcceptValidMember() {
        var result = validator.validate(mockEvent, validMember);

        assertEquals(ValidationStatus.ACCEPTED, result.status());
        assertTrue(result.status().isAccepted());
        assertFalse(result.status().isRejected());
    }

    @Test
    void testRejectNonCommitteeMember() {
        var result = validator.validate(mockEvent, invalidMember);

        assertEquals(ValidationStatus.REJECTED_NOT_IN_COMMITTEE, result.status());
        assertTrue(result.status().isRejected());
        assertTrue(result.status().isByzantineIndicator());
    }

    @Test
    void testRejectByzantineMember() {
        var result = validator.validate(mockEvent, byzantineMember);

        assertEquals(ValidationStatus.REJECTED_BYZANTINE, result.status());
        assertTrue(result.status().isRejected());
        assertTrue(result.status().isByzantineIndicator());
    }

    @Test
    void testRejectDuplicateSignature() {
        // First signature accepted
        var result1 = validator.validate(mockEvent, validMember);
        assertEquals(ValidationStatus.ACCEPTED, result1.status());

        // Record acceptance
        validator.recordAccepted(mockEvent, validMember);

        // Second signature from same member rejected
        var result2 = validator.validate(mockEvent, validMember);
        assertEquals(ValidationStatus.REJECTED_DUPLICATE, result2.status());
    }

    @Test
    void testRejectAfterQuorumMet() {
        // Record quorum met
        validator.recordQuorumMet(mockEvent);

        // Signature after quorum should be rejected
        var result = validator.validate(mockEvent, validMember);
        assertEquals(ValidationStatus.REJECTED_QUORUM_MET, result.status());
    }

    @Test
    void testAcceptWithLenientConfig() {
        // Create validator with lenient config (no strict committee enforcement)
        var lenientValidator = new RuntimeByzantineValidator(
            mockWitnessContext,
            mockByzantineDetector,
            null,
            RuntimeByzantineValidator.Config.lenient()
        );

        // Non-committee member should be accepted with lenient config
        var result = lenientValidator.validate(mockEvent, invalidMember);
        assertEquals(ValidationStatus.ACCEPTED, result.status());
    }

    @Test
    void testResetOnViewChange() {
        // Record some state
        validator.recordAccepted(mockEvent, validMember);
        validator.recordQuorumMet(mockEvent);

        // Reset on view change
        validator.resetOnViewChange();

        // New signature should be accepted (state cleared)
        var result = validator.validate(mockEvent, validMember);
        assertEquals(ValidationStatus.ACCEPTED, result.status());
    }

    @Test
    void testClearEventState() {
        // Record some state
        validator.recordAccepted(mockEvent, validMember);

        // Clear event state
        validator.clearEventState(mockEvent);

        // New signature should be accepted (state cleared)
        var result = validator.validate(mockEvent, validMember);
        assertEquals(ValidationStatus.ACCEPTED, result.status());
    }

    @Test
    void testStats() {
        // Accept one, reject one Byzantine
        validator.validate(mockEvent, validMember);
        validator.validate(mockEvent, byzantineMember);

        var stats = validator.getStats();
        assertEquals(1, stats.accepted());
        assertEquals(1, stats.rejectedByzantine());
        assertEquals(1, stats.totalRejected());
    }

    @Test
    void testStatsAcceptanceRate() {
        // Accept two
        validator.validate(mockEvent, validMember);

        var anotherMember = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("another".getBytes()));
        when(mockWitnessContext.isCommitteeMember(anotherMember, mockEvent)).thenReturn(true);
        validator.validate(mockEvent, anotherMember);

        // Reject one Byzantine
        validator.validate(mockEvent, byzantineMember);

        var stats = validator.getStats();
        assertEquals(2, stats.accepted());
        assertEquals(1, stats.totalRejected());
        assertEquals(2.0 / 3.0, stats.acceptanceRate(), 0.01);
    }

    @Test
    void testByzantineDetectorShunCheck() {
        // Set up member that should be shunned
        var shunnedMember = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("shunned".getBytes()));
        when(mockWitnessContext.isCommitteeMember(shunnedMember, mockEvent)).thenReturn(true);
        when(mockByzantineDetector.shouldShun(shunnedMember)).thenReturn(true);

        var result = validator.validate(mockEvent, shunnedMember);

        assertEquals(ValidationStatus.REJECTED_BYZANTINE, result.status());
        assertTrue(result.reason().contains("BLS failure threshold exceeded"));
    }

    @Test
    void testNullEventThrowsException() {
        assertThrows(NullPointerException.class, () -> validator.validate(null, validMember));
    }

    @Test
    void testNullMemberThrowsException() {
        assertThrows(NullPointerException.class, () -> validator.validate(mockEvent, null));
    }

    @Test
    void testDefaultConfig() {
        var config = RuntimeByzantineValidator.Config.defaults();

        assertEquals(0.8, config.byzantineScoreThreshold());
        assertTrue(config.strictCommitteeEnforcement());
        assertTrue(config.rejectAfterQuorum());
    }

    @Test
    void testLenientConfig() {
        var config = RuntimeByzantineValidator.Config.lenient();

        assertEquals(0.9, config.byzantineScoreThreshold());
        assertFalse(config.strictCommitteeEnforcement());
        assertFalse(config.rejectAfterQuorum());
    }

    @Test
    void testValidationResultMethods() {
        var accepted = RuntimeByzantineValidator.ValidationResult.accepted();
        assertEquals(ValidationStatus.ACCEPTED, accepted.status());

        var rejected = RuntimeByzantineValidator.ValidationResult.rejectedNotInCommittee(validMember);
        assertEquals(ValidationStatus.REJECTED_NOT_IN_COMMITTEE, rejected.status());
        assertTrue(rejected.reason().contains(validMember.toString()));
    }

    @Test
    void testMultipleEventsIndependent() {
        var event2 = mock(EventCoordinates.class);
        when(event2.getDigest()).thenReturn(DigestAlgorithm.DEFAULT.digest("event2".getBytes()));
        when(event2.getSequenceNumber()).thenReturn(ULong.valueOf(2));
        when(mockWitnessContext.isCommitteeMember(validMember, event2)).thenReturn(true);

        // Accept and record for event 1
        validator.validate(mockEvent, validMember);
        validator.recordAccepted(mockEvent, validMember);

        // Same member should still be accepted for event 2
        var result = validator.validate(event2, validMember);
        assertEquals(ValidationStatus.ACCEPTED, result.status());
    }

    @Test
    void testActiveEventsCountInStats() {
        // Create events
        var event2 = mock(EventCoordinates.class);
        when(event2.getDigest()).thenReturn(DigestAlgorithm.DEFAULT.digest("event2".getBytes()));
        when(event2.getSequenceNumber()).thenReturn(ULong.valueOf(2));
        when(mockWitnessContext.isCommitteeMember(validMember, event2)).thenReturn(true);

        // Validate for two events
        validator.validate(mockEvent, validMember);
        validator.validate(event2, validMember);

        var stats = validator.getStats();
        assertEquals(2, stats.activeEvents());
    }
}
