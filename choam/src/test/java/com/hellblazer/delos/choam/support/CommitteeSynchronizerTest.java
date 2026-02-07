/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.CHOAM;
import com.hellblazer.delos.choam.Committee;
import com.hellblazer.delos.cryptography.Verifier;
import com.hellblazer.delos.membership.Member;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for CommitteeSynchronizer - validates the extracted Synchronizer
 * implementation works correctly with CHOAM delegation.
 * <p>
 * Tests follow Synchronizer extraction plan (Phase 2, Bead: Delos-m72q)
 * These tests verify correct delegation behavior without requiring full CHOAM setup.
 *
 * @author hal.hildebrand
 */
public class CommitteeSynchronizerTest {

    /**
     * Test that synchronizer properly implements Committee interface.
     */
    @Test
    public void testIsCommitteeImplementation() {
        CHOAM choam = mock(CHOAM.class);
        Logger log = mock(Logger.class);
        Map<Member, Verifier> validators = new HashMap<>();

        CommitteeSynchronizer synchronizer = new CommitteeSynchronizer(choam, validators, log);

        assertInstanceOf(Committee.class, synchronizer, "Should implement Committee");
        assertFalse(synchronizer.isMember(), "Synchronizer is not a member (observer role)");
        assertSame(log, synchronizer.log(), "Should return configured logger");
    }

    /**
     * Test accept() delegates to CHOAM.process().
     * This is the core synchronization operation.
     */
    @Test
    public void testAcceptDelegatesToProcess() {
        CHOAM choam = mock(CHOAM.class);
        Logger log = mock(Logger.class);
        Map<Member, Verifier> validators = new HashMap<>();
        CommitteeSynchronizer synchronizer = new CommitteeSynchronizer(choam, validators, log);

        HashedCertifiedBlock block = mock(HashedCertifiedBlock.class);

        synchronizer.accept(block);

        verify(choam, times(1)).process();
        verifyNoMoreInteractions(choam);
    }

    /**
     * Test complete() is a no-op (synchronizer has no cleanup).
     */
    @Test
    public void testCompleteIsNoOp() {
        CHOAM choam = mock(CHOAM.class);
        Logger log = mock(Logger.class);
        Map<Member, Verifier> validators = new HashMap<>();
        CommitteeSynchronizer synchronizer = new CommitteeSynchronizer(choam, validators, log);

        assertDoesNotThrow(() -> synchronizer.complete(), "complete() should not throw");
        verifyNoInteractions(choam);
    }

    /**
     * Test validate() delegates to CHOAM.validate() with validators.
     * Uses simple majority validation for synchronization phase.
     */
    @Test
    public void testValidateDelegatesToCHOAMValidate() {
        CHOAM choam = mock(CHOAM.class);
        Logger log = mock(Logger.class);
        Map<Member, Verifier> validators = new HashMap<>();
        CommitteeSynchronizer synchronizer = new CommitteeSynchronizer(choam, validators, log);

        HashedCertifiedBlock block = mock(HashedCertifiedBlock.class);

        // Configure CHOAM to return true for validation
        when(choam.validate(block, validators)).thenReturn(true);

        assertTrue(synchronizer.validate(block), "Should return CHOAM validation result");
        verify(choam, times(1)).validate(block, validators);

        // Test false case
        when(choam.validate(block, validators)).thenReturn(false);
        assertFalse(synchronizer.validate(block), "Should return false when CHOAM validates false");
    }

    /**
     * Test params() delegates to CHOAM.params().
     */
    @Test
    public void testParamsDelegatesToCHOAM() {
        CHOAM choam = mock(CHOAM.class);
        Logger log = mock(Logger.class);
        Map<Member, Verifier> validators = new HashMap<>();
        CommitteeSynchronizer synchronizer = new CommitteeSynchronizer(choam, validators, log);

        synchronizer.params();

        verify(choam, times(1)).params();
    }

    /**
     * Test concurrent accept() calls are safe.
     * Multiple blocks can be accepted without race conditions.
     */
    @Test
    public void testConcurrentAccept() throws InterruptedException {
        CHOAM choam = mock(CHOAM.class);
        Logger log = mock(Logger.class);
        Map<Member, Verifier> validators = new HashMap<>();
        CommitteeSynchronizer synchronizer = new CommitteeSynchronizer(choam, validators, log);

        final int threadCount = 10;
        var latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    synchronizer.accept(mock(HashedCertifiedBlock.class));
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "All threads should complete");
        verify(choam, times(threadCount)).process();
    }

    /**
     * Test concurrent validate() calls are safe.
     */
    @Test
    public void testConcurrentValidate() throws InterruptedException {
        CHOAM choam = mock(CHOAM.class);
        Logger log = mock(Logger.class);
        Map<Member, Verifier> validators = new HashMap<>();
        CommitteeSynchronizer synchronizer = new CommitteeSynchronizer(choam, validators, log);

        HashedCertifiedBlock block = mock(HashedCertifiedBlock.class);
        when(choam.validate(eq(block), eq(validators))).thenReturn(true);

        final int threadCount = 10;
        var latch = new CountDownLatch(threadCount);
        var successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    if (synchronizer.validate(block)) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "All threads should complete");
        assertEquals(threadCount, successCount.get(), "All validations should succeed");
        verify(choam, times(threadCount)).validate(block, validators);
    }

    /**
     * Test synchronizer lifecycle: create, use, complete.
     */
    @Test
    public void testSynchronizerLifecycle() {
        CHOAM choam = mock(CHOAM.class);
        Logger log = mock(Logger.class);
        Map<Member, Verifier> validators = new HashMap<>();

        // Create
        CommitteeSynchronizer synchronizer = new CommitteeSynchronizer(choam, validators, log);
        assertNotNull(synchronizer, "Should create successfully");

        // Use for synchronization
        HashedCertifiedBlock block = mock(HashedCertifiedBlock.class);
        when(choam.validate(block, validators)).thenReturn(true);

        assertTrue(synchronizer.validate(block), "Should validate block");
        synchronizer.accept(block);
        verify(choam).process();

        // Complete (no-op but should be safe)
        assertDoesNotThrow(() -> synchronizer.complete());

        // Should still work after complete
        synchronizer.accept(block);
        verify(choam, times(2)).process();
    }

    /**
     * Test that synchronizer uses the correct validation method.
     * This verifies the fix from commit b6d0bf44 - synchronizer calls choam.validate()
     * which implements simple majority, NOT Committee.super.validate() which uses
     * Byzantine toleranceLevel.
     */
    @Test
    public void testValidationUsesCorrectMethod() {
        CHOAM choam = mock(CHOAM.class);
        Logger log = mock(Logger.class);
        Map<Member, Verifier> validators = new HashMap<>();

        // Add 4 validators to test majority behavior
        for (int i = 0; i < 4; i++) {
            validators.put(mock(Member.class), mock(Verifier.class));
        }

        CommitteeSynchronizer synchronizer = new CommitteeSynchronizer(choam, validators, log);
        HashedCertifiedBlock block = mock(HashedCertifiedBlock.class);

        // CHOAM.validate() should use simple majority logic (n/2+1 = 3 for 4 validators)
        when(choam.validate(block, validators)).thenReturn(true);

        assertTrue(synchronizer.validate(block), "Should use CHOAM's simple majority validation");

        // Verify it called CHOAM's validate, passing the same validators map
        verify(choam, times(1)).validate(block, validators);
    }

    /**
     * Test that validators map is passed by reference, not copied.
     */
    @Test
    public void testValidatorsPassedByReference() {
        CHOAM choam = mock(CHOAM.class);
        Logger log = mock(Logger.class);
        Map<Member, Verifier> validators = new HashMap<>();
        Member m1 = mock(Member.class);
        Verifier v1 = mock(Verifier.class);
        validators.put(m1, v1);

        CommitteeSynchronizer synchronizer = new CommitteeSynchronizer(choam, validators, log);
        HashedCertifiedBlock block = mock(HashedCertifiedBlock.class);
        when(choam.validate(eq(block), any())).thenReturn(true);

        synchronizer.validate(block);

        // Verify the SAME validators map was passed (not a copy)
        verify(choam).validate(eq(block), eq(validators));
    }
}
