/*
 * Copyright (c) 2025, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.witness.validation;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for FirefliesShunningIntegration.
 * <p>
 * Validates:
 * - Single member shunning propagates to Fireflies View
 * - Concurrent shunning requests handled correctly
 * - Fireflies callback failures don't block Byzantine detector
 * - Shunned member appears in View's shunned member list
 * - Thread-safe integration for concurrent access
 * - Non-blocking async pattern
 * </p>
 */
class FirefliesShunningIntegrationTest {

    private FirefliesShunningIntegration integration;
    private MockFirefliesView mockView;
    private Identifier memberId1;
    private Identifier memberId2;
    private Identifier memberId3;

    @BeforeEach
    void setUp() {
        mockView = new MockFirefliesView();
        integration = new FirefliesShunningIntegrationImpl(mockView);

        // Create test member identifiers
        memberId1 = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("member1".getBytes()));
        memberId2 = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("member2".getBytes()));
        memberId3 = new SelfAddressingIdentifier(DigestAlgorithm.DEFAULT.digest("member3".getBytes()));
    }

    /**
     * Test 1: Single member shunning propagates to Fireflies.
     */
    @Test
    void testSingleMemberShunning() throws Exception {
        // Act: Mark member for shunning
        var future = integration.markMemberForShunning(memberId1);

        // Assert: Future completes successfully
        assertNotNull(future, "Future should not be null");
        future.get(1, TimeUnit.SECONDS); // Should complete quickly

        // Verify: View was called
        assertEquals(1, mockView.getShunCallCount(), "View.shunMember() should be called once");
        assertTrue(mockView.isShunned(memberId1), "Member should be marked as shunned");

        // Verify: Integration reports member as shunned
        assertTrue(integration.isShunned(memberId1), "Integration should report member as shunned");
    }

    /**
     * Test 2: Multiple concurrent shunning requests handled correctly.
     */
    @Test
    void testConcurrentShunningRequests() throws Exception {
        // Arrange: Prepare concurrent shunning requests
        var latch = new CountDownLatch(3);
        var futures = new CompletableFuture<?>[3];
        var errors = new ConcurrentHashMap<Integer, Throwable>();

        // Act: Trigger 3 concurrent shunning requests
        Thread t1 = new Thread(() -> {
            try {
                futures[0] = integration.markMemberForShunning(memberId1);
                futures[0].get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                errors.put(0, e);
            } finally {
                latch.countDown();
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                futures[1] = integration.markMemberForShunning(memberId2);
                futures[1].get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                errors.put(1, e);
            } finally {
                latch.countDown();
            }
        });

        Thread t3 = new Thread(() -> {
            try {
                futures[2] = integration.markMemberForShunning(memberId3);
                futures[2].get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                errors.put(2, e);
            } finally {
                latch.countDown();
            }
        });

        t1.start();
        t2.start();
        t3.start();

        // Assert: All threads complete without errors
        assertTrue(latch.await(5, TimeUnit.SECONDS), "All shunning requests should complete");
        assertTrue(errors.isEmpty(), "No errors should occur: " + errors);

        // Verify: All members shunned
        assertEquals(3, mockView.getShunCallCount(), "View.shunMember() should be called 3 times");
        assertTrue(integration.isShunned(memberId1), "Member 1 should be shunned");
        assertTrue(integration.isShunned(memberId2), "Member 2 should be shunned");
        assertTrue(integration.isShunned(memberId3), "Member 3 should be shunned");
    }

    /**
     * Test 3: Fireflies callback failure doesn't block detector.
     */
    @Test
    void testFirefliesCallbackFailureDoesNotBlock() throws Exception {
        // Arrange: Configure View to fail on shunning
        mockView.setFailOnShun(true);

        // Act: Mark member for shunning (should fail but not throw)
        var future = integration.markMemberForShunning(memberId1);

        // Assert: Future completes exceptionally
        assertNotNull(future, "Future should not be null");
        try {
            future.get(1, TimeUnit.SECONDS);
            fail("Future should complete exceptionally");
        } catch (Exception e) {
            // Expected - View operation failed
            assertTrue(e.getCause() instanceof RuntimeException, "Expected RuntimeException from View");
        }

        // Verify: Integration still operational - can shun other members
        mockView.setFailOnShun(false);
        var future2 = integration.markMemberForShunning(memberId2);
        future2.get(1, TimeUnit.SECONDS); // Should succeed

        assertTrue(integration.isShunned(memberId2), "Integration should still work after error");
    }

    /**
     * Test 4: Shunned member appears in View's shunned list.
     */
    @Test
    void testShunnedMemberAppearsInViewShunnedList() throws Exception {
        // Act: Mark member for shunning
        var future = integration.markMemberForShunning(memberId1);
        future.get(1, TimeUnit.SECONDS);

        // Assert: View reports member as shunned
        assertTrue(mockView.isShunned(memberId1), "View should report member as shunned");

        // Verify: Gossip propagation (in real system, shunned set would propagate)
        var shunnedMembers = mockView.getShunnedMembers();
        assertTrue(shunnedMembers.contains(toDigest(memberId1)), "Shunned member should be in View's shunned set");
        assertEquals(1, shunnedMembers.size(), "Only one member should be shunned");
    }

    /**
     * Test 5: Non-blocking async pattern.
     */
    @Test
    void testNonBlockingAsyncPattern() {
        // Arrange: Track call timing
        var startTime = System.nanoTime();

        // Act: Mark 5 members for shunning rapidly
        var futures = new CompletableFuture<?>[5];
        for (int i = 0; i < 5; i++) {
            var memberId = new SelfAddressingIdentifier(
                DigestAlgorithm.DEFAULT.digest(("member" + i).getBytes())
            );
            futures[i] = integration.markMemberForShunning(memberId);
        }

        var callDuration = System.nanoTime() - startTime;

        // Assert: All calls returned quickly (non-blocking)
        assertTrue(callDuration < TimeUnit.MILLISECONDS.toNanos(100),
                   "All 5 calls should return in <100ms (was " + TimeUnit.NANOSECONDS.toMillis(callDuration) + "ms)");

        // Verify: All futures eventually complete
        for (var future : futures) {
            assertNotNull(future, "Future should not be null");
            assertDoesNotThrow(() -> future.get(2, TimeUnit.SECONDS), "Future should complete");
        }
    }

    /**
     * Test 6: Idempotent shunning (same member twice).
     */
    @Test
    void testIdempotentShunning() throws Exception {
        // Act: Shun same member twice
        var future1 = integration.markMemberForShunning(memberId1);
        future1.get(1, TimeUnit.SECONDS);

        var future2 = integration.markMemberForShunning(memberId1);
        future2.get(1, TimeUnit.SECONDS);

        // Assert: Only one shunned entry
        assertTrue(integration.isShunned(memberId1), "Member should be shunned");
        assertEquals(2, mockView.getShunCallCount(), "View.shunMember() called twice (idempotent)");

        var shunnedMembers = mockView.getShunnedMembers();
        assertEquals(1, shunnedMembers.size(), "Only one shunned entry should exist");
    }

    // Helper methods

    private Digest toDigest(Identifier identifier) {
        if (identifier instanceof SelfAddressingIdentifier sai) {
            return sai.getDigest();
        }
        throw new IllegalArgumentException("Cannot convert identifier to digest: " + identifier);
    }

    /**
     * Mock Fireflies View for testing.
     * <p>
     * Simulates View.shunMember() behavior without full Fireflies infrastructure.
     * Tracks shunned members and call counts for test verification.
     * </p>
     */
    private static class MockFirefliesView implements FirefliesShunningIntegrationImpl.FirefliesViewAdapter {
        private final Set<Digest> shunnedMembers = ConcurrentHashMap.newKeySet();
        private final AtomicInteger shunCallCount = new AtomicInteger(0);
        private volatile boolean failOnShun = false;

        @Override
        public CompletableFuture<Void> shunMember(Identifier memberId) {
            shunCallCount.incrementAndGet();

            if (failOnShun) {
                return CompletableFuture.failedFuture(
                    new RuntimeException("Mock View failure for testing")
                );
            }

            // Convert Identifier to Digest for storage
            var digest = ((SelfAddressingIdentifier) memberId).getDigest();
            shunnedMembers.add(digest);

            return CompletableFuture.completedFuture(null);
        }

        public boolean isShunned(Identifier memberId) {
            var digest = ((SelfAddressingIdentifier) memberId).getDigest();
            return shunnedMembers.contains(digest);
        }

        public Set<Digest> getShunnedMembers() {
            return Set.copyOf(shunnedMembers);
        }

        public int getShunCallCount() {
            return shunCallCount.get();
        }

        public void setFailOnShun(boolean fail) {
            this.failOnShun = fail;
        }
    }
}
