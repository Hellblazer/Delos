/*
 * Copyright (c) 2025, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test concurrent rotation to verify thread safety
 *
 * @author hal.hildebrand
 */
public class ConcurrentRotationTest {
    KERL.AppendKERL    kel;
    StereotomyKeyStore ks;
    SecureRandom       secureRandom;

    @BeforeEach
    public void before() throws Exception {
        secureRandom = SecureRandom.getInstance("SHA1PRNG");
        secureRandom.setSeed(new byte[] { 0 });
        kel = new MemKERL(DigestAlgorithm.DEFAULT);
        ks = new MemKeyStore();
    }

    @Test
    public void concurrentRotation() throws Exception {
        var controller = new StereotomyImpl(ks, kel, secureRandom);
        var identifier = controller.newIdentifier();

        final int threadCount = 10;
        var barrier = new CyclicBarrier(threadCount);
        var latch = new CountDownLatch(threadCount);
        var successCount = new AtomicInteger(0);
        var exceptionRef = new AtomicReference<Throwable>();

        var initialState = identifier.getCoordinates();

        // Launch concurrent rotation threads
        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    barrier.await(); // Synchronize start
                    identifier.rotate();
                    successCount.incrementAndGet();
                } catch (Throwable e) {
                    exceptionRef.compareAndSet(null, e);
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();

        // Check for exceptions
        if (exceptionRef.get() != null) {
            fail("Exception during concurrent rotation: " + exceptionRef.get().getMessage(), exceptionRef.get());
        }

        // Verify that exactly one rotation succeeded per thread
        // Since each rotate() is now atomic, all should succeed
        assertEquals(threadCount, successCount.get(), "All rotation attempts should succeed");

        // Verify final state has advanced by exactly threadCount rotations
        var finalState = identifier.getCoordinates();
        assertEquals(initialState.getSequenceNumber().longValue() + threadCount,
                     finalState.getSequenceNumber().longValue(), "Sequence number should advance by thread count");

        // Verify KERL consistency - should have inception + threadCount rotations
        var kerl = kel.kerl(identifier.getIdentifier());
        assertNotNull(kerl);
        assertEquals(threadCount + 1, kerl.size(), "KERL should have inception + all rotations");
    }
}
