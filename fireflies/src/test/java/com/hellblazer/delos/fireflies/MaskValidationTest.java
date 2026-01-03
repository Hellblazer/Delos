/*
 * Copyright (c) 2025, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies;

import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.cryptography.cert.Certificates;
import com.hellblazer.delos.fireflies.View.Participant;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.impl.MemberImpl;
import com.hellblazer.delos.utils.Utils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.BitSet;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Test cases for Byzantine mask validation attack prevention (Delos-p0l).
 * <p>
 * Tests the fix for the vulnerability where Byzantine nodes could create masks hiding real accusations.
 * The enhanced isValidMask() method validates that for existing members, any ring where an accusation
 * exists must be disabled in the mask.
 *
 * @author hal.hildebrand
 */
public class MaskValidationTest {

    private static final byte[] PROTO = new byte[32];

    private DynamicContext<Participant> context;

    @BeforeEach
    public void before() {
        // Create a context with known ring configuration
        // 5 rings, majority = 3
        context = DynamicContext.<Participant>newBuilder()
                               .setCardinality(5)
                               .setpByz(0.2)
                               .setBias(2)
                               .build();
    }

    /**
     * Test that a valid mask with correct cardinality passes basic validation.
     */
    @Test
    public void testValidMaskCardinality() {
        var mask = new BitSet(context.getRingCount());
        // Enable exactly majority rings
        for (var i = 0; i < context.majority(); i++) {
            mask.set(i);
        }

        // Without member ID, should pass basic validation
        assertTrue(View.isValidMask(mask, context, null),
                   "Mask with correct cardinality should be valid");
    }

    /**
     * Test that a mask with wrong cardinality is rejected.
     */
    @Test
    public void testInvalidMaskCardinality() {
        // Too few enabled
        var tooFew = new BitSet(context.getRingCount());
        for (var i = 0; i < context.majority() - 1; i++) {
            tooFew.set(i);
        }
        assertFalse(View.isValidMask(tooFew, context, null),
                    "Mask with too few enabled rings should be rejected");

        // Too many enabled
        var tooMany = new BitSet(context.getRingCount());
        for (var i = 0; i < context.majority() + 1; i++) {
            tooMany.set(i);
        }
        assertFalse(View.isValidMask(tooMany, context, null),
                    "Mask with too many enabled rings should be rejected");
    }

    /**
     * Test that a mask exceeding ring count is rejected.
     */
    @Test
    public void testMaskExceedsRingCount() {
        var mask = new BitSet();
        // Enable rings beyond the valid range
        for (var i = 0; i < context.majority(); i++) {
            mask.set(context.getRingCount() + i);
        }
        assertFalse(View.isValidMask(mask, context, null),
                    "Mask with bits beyond ring count should be rejected");
    }

    /**
     * Test that when a member has an accusation on a ring, that ring must be disabled.
     * This is the core Byzantine attack prevention test.
     */
    @Test
    public void testAccusationMustDisableRing() {
        // Create a mock participant with an accusation on ring 0
        var participant = mock(Participant.class);
        var memberId = new Digest(DigestAlgorithm.DEFAULT, PROTO);
        when(participant.getId()).thenReturn(memberId);
        when(participant.isAccusedOn(0)).thenReturn(true);
        when(participant.isAccusedOn(anyInt())).thenAnswer(inv -> inv.getArgument(0).equals(0));

        // Add participant to context
        @SuppressWarnings("unchecked")
        var mockContext = mock(DynamicContext.class);
        when(mockContext.getMember(memberId)).thenReturn(participant);
        when(mockContext.majority()).thenReturn(3);
        when(mockContext.getRingCount()).thenReturn((short) 5);

        // Create a mask with ring 0 enabled (Byzantine - hiding the accusation)
        var byzantineMask = new BitSet(5);
        byzantineMask.set(0);  // Ring 0 has accusation but mask shows enabled
        byzantineMask.set(1);
        byzantineMask.set(2);  // Total = 3 = majority

        // Should be REJECTED because ring 0 has accusation but is enabled in mask
        assertFalse(View.isValidMask(byzantineMask, mockContext, memberId),
                    "Should reject mask that keeps accused ring enabled");

        // Now create a correct mask with ring 0 disabled
        var correctMask = new BitSet(5);
        // Skip ring 0 (accused), enable rings 1, 2, 3
        correctMask.set(1);
        correctMask.set(2);
        correctMask.set(3);  // Total = 3 = majority

        // Should be ACCEPTED because ring 0 is correctly disabled
        assertTrue(View.isValidMask(correctMask, mockContext, memberId),
                   "Should accept mask that correctly disables accused ring");
    }

    /**
     * Test that member ID can be null for new member validation (backwards compatibility).
     */
    @Test
    public void testNullMemberIdSkipsAccusationCheck() {
        var mask = new BitSet(context.getRingCount());
        for (var i = 0; i < context.majority(); i++) {
            mask.set(i);
        }

        // Null member ID should skip accusation validation (for new members)
        assertTrue(View.isValidMask(mask, context, null),
                   "Null member ID should skip accusation validation");
    }

    /**
     * Test that unknown member ID is handled gracefully.
     */
    @Test
    public void testUnknownMemberId() {
        var unknownId = new Digest(DigestAlgorithm.DEFAULT, new byte[32]);
        var mask = new BitSet(context.getRingCount());
        for (var i = 0; i < context.majority(); i++) {
            mask.set(i);
        }

        // Unknown member (not in context) should pass basic validation
        assertTrue(View.isValidMask(mask, context, unknownId),
                   "Unknown member should pass if mask meets basic requirements");
    }

    /**
     * Test the two-parameter method delegates to three-parameter method.
     */
    @Test
    public void testTwoParameterMethodDelegation() {
        var mask = new BitSet(context.getRingCount());
        for (var i = 0; i < context.majority(); i++) {
            mask.set(i);
        }

        // Both should return the same result
        boolean twoParam = View.isValidMask(mask, context);
        boolean threeParam = View.isValidMask(mask, context, null);

        assertEquals(twoParam, threeParam,
                     "Two-parameter method should delegate to three-parameter with null ID");
    }
}
