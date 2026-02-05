/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.choam;

import com.hellblazer.delos.choam.Parameters.RuntimeParameters;
import com.hellblazer.delos.choam.support.BoundedPriorityBlockingQueue;
import com.hellblazer.delos.context.StaticContext;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test pending queue bounds configuration and validation.
 *
 * These tests verify that:
 * 1. Queue respects maximum pending blocks capacity
 * 2. Configuration validates pending block bounds
 * 3. Negative or zero values are rejected
 * 4. Extremely large values are rejected
 * 5. Default value is reasonable
 */
public class PendingQueueBoundsTest {

    @Test
    public void testDefaultPendingBlocksValid() throws Exception {
        // Test that default configuration is valid (1000 blocks)
        var context = new StaticContext<Member>(DigestAlgorithm.DEFAULT.getOrigin(), 0.1, Collections.emptyList(), 2);
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var member = new ControlledIdentifierMember(
            new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy).newIdentifier()
        );

        Parameters params = Parameters.newBuilder()
                                     .build(Parameters.RuntimeParameters.newBuilder()
                                                             .setContext(context)
                                                             .setMember(member)
                                                             .setProcessor(Parameters.RuntimeParameters.NOOP_PROCESSOR)
                                                             .setRestorer(Parameters.RuntimeParameters.NOOP_RESTORER)
                                                             .build());

        assertEquals(1000, params.maxPendingBlocks(),
                    "Default maxPendingBlocks should be 1000");
    }

    @Test
    public void testNegativePendingBlocksRejected() throws Exception {
        // Test that negative maxPendingBlocks is rejected
        var context = new StaticContext<Member>(DigestAlgorithm.DEFAULT.getOrigin(), 0.1, Collections.emptyList(), 2);
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var member = new ControlledIdentifierMember(
            new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy).newIdentifier()
        );

        assertThrows(IllegalArgumentException.class, () -> {
            Parameters.newBuilder()
                     .setMaxPendingBlocks(-100)
                     .build(RuntimeParameters.newBuilder()
                                             .setContext(context)
                                             .setMember(member)
                                             .build());
        }, "Negative maxPendingBlocks should be rejected");
    }

    @Test
    public void testZeroPendingBlocksRejected() throws Exception {
        // Test that zero maxPendingBlocks is rejected
        var context = new StaticContext<Member>(DigestAlgorithm.DEFAULT.getOrigin(), 0.1, Collections.emptyList(), 2);
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var member = new ControlledIdentifierMember(
            new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy).newIdentifier()
        );

        assertThrows(IllegalArgumentException.class, () -> {
            Parameters.newBuilder()
                     .setMaxPendingBlocks(0)
                     .build(RuntimeParameters.newBuilder()
                                             .setContext(context)
                                             .setMember(member)
                                             .build());
        }, "Zero maxPendingBlocks should be rejected");
    }

    @Test
    public void testExtremelyLargePendingBlocksRejected() throws Exception {
        // Test that extremely large maxPendingBlocks is rejected (prevent memory exhaustion)
        var context = new StaticContext<Member>(DigestAlgorithm.DEFAULT.getOrigin(), 0.1, Collections.emptyList(), 2);
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var member = new ControlledIdentifierMember(
            new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy).newIdentifier()
        );

        // 1 billion pending blocks would require excessive memory
        assertThrows(IllegalArgumentException.class, () -> {
            Parameters.newBuilder()
                     .setMaxPendingBlocks(Integer.MAX_VALUE)
                     .build(RuntimeParameters.newBuilder()
                                             .setContext(context)
                                             .setMember(member)
                                             .build());
        }, "Extremely large maxPendingBlocks should be rejected");
    }

    @Test
    public void testQueueCapacityEnforced() {
        // Test that the queue actually enforces its capacity limit
        var queue = new BoundedPriorityBlockingQueue<Integer>(3);

        // Should add first 3 elements successfully
        assertTrue(queue.offer(1), "First element should be accepted");
        assertTrue(queue.offer(2), "Second element should be accepted");
        assertTrue(queue.offer(3), "Third element should be accepted");

        // Fourth element should be rejected (queue full)
        assertFalse(queue.offer(4), "Fourth element should be rejected (queue full)");

        // After removing one, should be able to add again
        assertEquals(1, queue.poll());
        assertTrue(queue.offer(4), "Should accept after removing one element");
    }

    @Test
    public void testValidPendingBlocksRange() throws Exception {
        // Test that valid values in reasonable range are accepted
        var context = new StaticContext<Member>(DigestAlgorithm.DEFAULT.getOrigin(), 0.1, Collections.emptyList(), 2);
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var member = new ControlledIdentifierMember(
            new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy).newIdentifier()
        );

        // Test various valid values
        int[] validValues = { 1, 10, 100, 1000, 10000, 100000 };
        for (int value : validValues) {
            Parameters params = Parameters.newBuilder()
                                         .setMaxPendingBlocks(value)
                                         .build(Parameters.RuntimeParameters.newBuilder()
                                                                 .setContext(context)
                                                                 .setMember(member)
                                                                 .setProcessor(Parameters.RuntimeParameters.NOOP_PROCESSOR)
                                                                 .setRestorer(Parameters.RuntimeParameters.NOOP_RESTORER)
                                                                 .build());
            assertEquals(value, params.maxPendingBlocks(),
                        "Valid value " + value + " should be accepted");
        }
    }

}
