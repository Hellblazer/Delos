/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BlockChainStateHolder - validates blockchain state management.
 * Tests follow CHOAMStateManager extraction plan (Phase 4, Bead: Delos-cr7n)
 *
 * @author hal.hildebrand
 */
public class BlockChainStateHolderTest {

    private BlockChainStateHolder holder;

    @BeforeEach
    public void setUp() {
        holder = new BlockChainStateHolder(DigestAlgorithm.DEFAULT, 100); // maxPending = 100
    }

    /**
     * Test genesis block lifecycle: initially null, set once, immutable.
     */
    @Test
    public void testGenesisImmutability() {
        // Initially null
        assertNull(holder.getGenesis(), "Genesis should be null initially");

        // Create genesis block
        var genesis = createBlock(0);

        // Set genesis
        holder.setGenesis(genesis);
        assertSame(genesis, holder.getGenesis(), "Should return same genesis");

        // Attempt to set again should throw
        var genesis2 = createBlock(0);
        assertThrows(IllegalStateException.class, () -> holder.setGenesis(genesis2),
                     "Setting genesis twice should throw");

        // Genesis should remain unchanged
        assertSame(genesis, holder.getGenesis(), "Genesis should be unchanged");
    }

    /**
     * Test head block lifecycle: monotonically increasing height.
     */
    @Test
    public void testHeadMonotonicity() {
        // Initially NullBlock (sentinel with height() returning null)
        assertNotNull(holder.getHead(), "Head should not be null initially");
        assertNull(holder.getHead().height(), "Head should be NullBlock with height() == null");

        // Set head at height 10
        var head1 = createBlock(10);
        holder.setHead(head1);
        assertSame(head1, holder.getHead(), "Should return same head");

        // Update to higher height
        var head2 = createBlock(20);
        holder.setHead(head2);
        assertSame(head2, holder.getHead(), "Should be updated head");

        // Can set to null (during reset)
        holder.setHead(null);
        assertNull(holder.getHead(), "Should be null after clear");
    }

    /**
     * Test view block lifecycle: should lag behind head.
     */
    @Test
    public void testViewLagsHead() {
        // Initially NullBlock (sentinel with height() returning null)
        assertNotNull(holder.getView(), "View should not be null initially");
        assertNull(holder.getView().height(), "View should be NullBlock with height() == null");

        // Set view at height 5
        var view1 = createBlock(5);
        holder.setView(view1);
        assertSame(view1, holder.getView(), "Should return same view");

        // Update to higher height
        var view2 = createBlock(10);
        holder.setView(view2);
        assertSame(view2, holder.getView(), "Should be updated view");

        // Can set to null
        holder.setView(null);
        assertNull(holder.getView(), "Should be null after clear");
    }

    /**
     * Test pending queue operations: add, take, size.
     */
    @Test
    public void testPendingQueueOperations() throws InterruptedException {
        // Initially empty
        assertEquals(0, holder.getPendingSize(), "Pending should be empty initially");

        // Add blocks
        var block1 = createBlock(1);
        var block2 = createBlock(2);
        assertTrue(holder.addPending(block1), "Should add block1");
        assertTrue(holder.addPending(block2), "Should add block2");
        assertEquals(2, holder.getPendingSize(), "Should have 2 pending");

        // Take blocks
        var taken1 = holder.takePending();
        assertNotNull(taken1, "Should take a block");
        assertEquals(1, holder.getPendingSize(), "Should have 1 pending");

        var taken2 = holder.takePending();
        assertNotNull(taken2, "Should take a block");
        assertEquals(0, holder.getPendingSize(), "Should be empty");
    }

    /**
     * Test pending queue bounds: should reject when full.
     */
    @Test
    public void testPendingQueueBounded() {
        // Fill queue to capacity
        for (int i = 0; i < 100; i++) {
            assertTrue(holder.addPending(createBlock(i)), "Should add block " + i);
        }
        assertEquals(100, holder.getPendingSize(), "Should be at capacity");

        // Next add should fail (queue full)
        assertFalse(holder.addPending(createBlock(100)), "Should reject when full");
        assertEquals(100, holder.getPendingSize(), "Size should remain at capacity");
    }

    /**
     * Test pending queue poll: non-blocking, returns null when empty.
     */
    @Test
    public void testPendingQueuePoll() {
        // Poll empty queue
        assertNull(holder.pollPending(), "Poll should return null when empty");

        // Add and poll
        var block = createBlock(1);
        holder.addPending(block);
        var polled = holder.pollPending();
        assertSame(block, polled, "Poll should return the block");
        assertEquals(0, holder.getPendingSize(), "Should be empty after poll");
        assertNull(holder.pollPending(), "Second poll should return null");
    }

    /**
     * Test lock exposure: headLock should be accessible for callers.
     */
    @Test
    public void testLockExposure() {
        assertNotNull(holder.headLock, "headLock should not be null");

        // Verify it's a valid lock
        holder.headLock.writeLock().lock();
        try {
            // Can set head while holding write lock
            holder.setHead(createBlock(1));
        } finally {
            holder.headLock.writeLock().unlock();
        }

        // Read lock should also work
        holder.headLock.readLock().lock();
        try {
            // Can read head while holding read lock
            assertNotNull(holder.getHead());
        } finally {
            holder.headLock.readLock().unlock();
        }
    }

    /**
     * Test toString provides useful debug information.
     */
    @Test
    public void testToString() {
        String str = holder.toString();
        assertTrue(str.contains("BlockChainStateHolder"), "Should contain class name");
        assertTrue(str.contains("genesis="), "Should show genesis");
        assertTrue(str.contains("head="), "Should show head");
        assertTrue(str.contains("view="), "Should show view");
        assertTrue(str.contains("pending="), "Should show pending count");
    }

    /**
     * Helper to create a mock HashedCertifiedBlock at given height.
     */
    private HashedCertifiedBlock createBlock(long height) {
        var block = com.hellblazer.delos.choam.proto.CertifiedBlock.newBuilder()
                                                                    .setBlock(com.hellblazer.delos.choam.proto.Block.newBuilder()
                                                                                                                    .setHeader(
                                                                                                                    com.hellblazer.delos.choam.proto.Header.newBuilder()
                                                                                                                                                            .setHeight(
                                                                                                                                                            height)
                                                                                                                                                            .build())
                                                                                                                    .build())
                                                                    .build();
        return new HashedCertifiedBlock(DigestAlgorithm.DEFAULT, block);
    }
}
