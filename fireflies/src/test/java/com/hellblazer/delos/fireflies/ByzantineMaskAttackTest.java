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
import com.hellblazer.delos.fireflies.proto.Note;
import com.hellblazer.delos.fireflies.proto.SignedNote;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.KERL;
import com.hellblazer.delos.stereotomy.KeyState;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integration test for Byzantine mask attack prevention (Delos-p0l).
 * <p>
 * This test demonstrates the complete attack scenario where a Byzantine node
 * attempts to evade detection by creating a note with a mask that claims to
 * have accusations (correct cardinality) but with mask bits that don't correspond
 * to actual accusations on the rings.
 * <p>
 * The fix ensures that View.add() rejects notes where the mask shows rings enabled
 * that actually have accusations.
 *
 * @author hal.hildebrand
 */
public class ByzantineMaskAttackTest {

    private static final DigestAlgorithm DIGEST_ALGO = DigestAlgorithm.DEFAULT;

    /**
     * Test the Byzantine attack scenario where a node creates a note with:
     * 1. Correct mask cardinality (passes basic validation)
     * 2. But mask bits that don't correspond to actual accusations
     * 3. This allows the Byzantine node to hide accusations and appear legitimate
     * <p>
     * The fix validates that for each set bit in the mask (enabled ring),
     * there must NOT be an accusation on that ring.
     */
    @Test
    public void testByzantineNodeCannotHideAccusations() {
        // Setup mock context with known parameters
        @SuppressWarnings("unchecked")
        var context = mock(DynamicContext.class);
        when(context.majority()).thenReturn(3);
        when(context.getRingCount()).thenReturn((short) 5);

        // Create Byzantine member ID
        var byzantineMemberId = new Digest(DIGEST_ALGO, new byte[32]);

        // Create a mock participant that has an accusation on ring 0
        var participant = mock(View.Participant.class);
        when(participant.getId()).thenReturn(byzantineMemberId);
        when(participant.isAccusedOn(0)).thenReturn(true);
        when(participant.isAccusedOn(anyInt())).thenAnswer(inv -> ((Integer) inv.getArgument(0)) == 0);

        // Configure context to return the participant
        when(context.getMember(byzantineMemberId)).thenReturn(participant);

        // Create a Byzantine mask that:
        // 1. Has correct cardinality (3 = majority)
        // 2. But enables ring 0 which has an accusation (trying to hide it)
        var byzantineMask = new BitSet(5);
        byzantineMask.set(0);  // Ring 0 has accusation but mask shows enabled (BYZANTINE!)
        byzantineMask.set(1);
        byzantineMask.set(2);  // Total = 3 = majority

        // Attempt validation with the Byzantine mask
        boolean isValid = View.isValidMask(byzantineMask, context, byzantineMemberId);

        // The fix should REJECT this mask
        assertFalse(isValid,
                    "Byzantine mask hiding accusation should be rejected");

        // Now create a correct mask with ring 0 disabled (as it should be)
        var correctMask = new BitSet(5);
        correctMask.set(1);
        correctMask.set(2);
        correctMask.set(3);  // Total = 3 = majority, ring 0 disabled

        // This should be accepted
        boolean correctIsValid = View.isValidMask(correctMask, context, byzantineMemberId);
        assertTrue(correctIsValid,
                   "Correct mask with accused ring disabled should be accepted");
    }

    /**
     * Test that a Byzantine node cannot create a note that appears legitimate
     * by manipulating mask bits while maintaining correct cardinality.
     */
    @Test
    public void testByzantineNoteRejectedInView() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DIGEST_ALGO), entropy);

        // Create Byzantine member
        var byzantineIdentifier = stereotomy.newIdentifier();
        var byzantineId = byzantineIdentifier.getDigest();

        // Create a context and mock participant with accusation
        @SuppressWarnings("unchecked")
        var mockContext = mock(DynamicContext.class);
        when(mockContext.majority()).thenReturn(3);
        when(mockContext.getRingCount()).thenReturn((short) 5);

        var participant = mock(View.Participant.class);
        when(participant.getId()).thenReturn(byzantineId);
        when(participant.isAccusedOn(0)).thenReturn(true);
        when(participant.isAccusedOn(anyInt())).thenAnswer(inv -> ((Integer) inv.getArgument(0)) == 0);

        when(mockContext.getMember(byzantineId)).thenReturn(participant);

        // Create Byzantine mask (enables ring 0 which has accusation)
        var byzantineMask = new BitSet(5);
        byzantineMask.set(0);  // Byzantine: hiding accusation
        byzantineMask.set(1);
        byzantineMask.set(2);

        // Validation should fail
        assertFalse(View.isValidMask(byzantineMask, mockContext, byzantineId),
                    "View should reject Byzantine note with fake mask");
    }

    /**
     * Test that multiple accusations on different rings are all validated.
     */
    @Test
    public void testMultipleAccusationsValidated() {
        @SuppressWarnings("unchecked")
        var mockContext = mock(DynamicContext.class);
        when(mockContext.majority()).thenReturn(3);
        when(mockContext.getRingCount()).thenReturn((short) 5);

        var memberId = new Digest(DIGEST_ALGO, new byte[32]);
        var participant = mock(View.Participant.class);
        when(participant.getId()).thenReturn(memberId);
        // Member has accusations on rings 0, 1, and 2
        when(participant.isAccusedOn(0)).thenReturn(true);
        when(participant.isAccusedOn(1)).thenReturn(true);
        when(participant.isAccusedOn(2)).thenReturn(true);
        when(participant.isAccusedOn(anyInt())).thenAnswer(inv -> {
            int ring = inv.getArgument(0);
            return ring == 0 || ring == 1 || ring == 2;
        });

        when(mockContext.getMember(memberId)).thenReturn(participant);

        // Byzantine mask enables ring 1 (which has accusation)
        var byzantineMask = new BitSet(5);
        byzantineMask.set(1);  // Byzantine: ring 1 has accusation
        byzantineMask.set(3);
        byzantineMask.set(4);

        assertFalse(View.isValidMask(byzantineMask, mockContext, memberId),
                    "Should reject mask that enables any accused ring");

        // Correct mask disables all accused rings (0, 1, 2)
        var correctMask = new BitSet(5);
        correctMask.set(3);
        correctMask.set(4);
        // We need 3 bits but only have 2 non-accused rings available (3, 4)
        // This scenario should also fail because we can't meet majority without using accused rings
        assertFalse(View.isValidMask(correctMask, mockContext, memberId),
                    "Should reject mask with insufficient cardinality");
    }

    /**
     * Test edge case: member with no accusations should pass validation with any valid mask.
     */
    @Test
    public void testNoAccusationsPassesValidation() {
        @SuppressWarnings("unchecked")
        var mockContext = mock(DynamicContext.class);
        when(mockContext.majority()).thenReturn(3);
        when(mockContext.getRingCount()).thenReturn((short) 5);

        var memberId = new Digest(DIGEST_ALGO, new byte[32]);
        var participant = mock(View.Participant.class);
        when(participant.getId()).thenReturn(memberId);
        when(participant.isAccusedOn(anyInt())).thenReturn(false);

        when(mockContext.getMember(memberId)).thenReturn(participant);

        var mask = new BitSet(5);
        mask.set(0);
        mask.set(1);
        mask.set(2);

        assertTrue(View.isValidMask(mask, mockContext, memberId),
                   "Member with no accusations should pass with valid cardinality");
    }
}
