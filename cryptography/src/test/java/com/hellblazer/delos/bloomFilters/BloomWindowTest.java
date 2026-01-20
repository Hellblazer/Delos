/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.bloomFilters;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.proto.Biff;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author hal.hildebrand
 **/
public class BloomWindowTest {

    @Test
    public void smokin() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        var algo = DigestAlgorithm.DEFAULT;

        var window = 1 << 10; // 1024 - smaller for faster tests
        var seen = BloomWindow.create(entropy.nextLong(), entropy.nextLong(), window, Math.pow(10, -9),
                                      Biff.Type.DIGEST);
        var inserted = new TreeSet<Digest>();
        var falsePositives = new TreeSet<Digest>();
        var decayed = new TreeSet<Digest>();

        // First batch: insert window elements (fills buffer 1)
        IntStream.range(0, window).mapToObj(i -> algo.random(entropy)).forEach(d -> {
            if (!seen.add(d)) {
                falsePositives.add(d);
            } else {
                inserted.add(d);
            }
        });
        assertEquals(0, falsePositives.size(), "First batch should have no false positives");

        // Verify all inserted elements are still present
        for (Digest digest : inserted) {
            if (!seen.contains(digest)) {
                decayed.add(digest);
            }
        }
        assertEquals(0, decayed.size(), "No elements should decay before overflow");

        // Second batch (partial): insert half-window to trigger ONE swap
        // After this, original elements are in aging buffer (active2)
        var halfBatch = new ArrayList<Digest>();
        IntStream.range(0, window / 2).mapToObj(i -> algo.random(entropy)).forEach(d -> {
            if (!seen.add(d)) {
                falsePositives.add(d);
            } else {
                halfBatch.add(d);
            }
        });
        assertTrue(falsePositives.size() <= 2,
                   "Half batch false positives should be minimal: " + falsePositives.size());

        // KEY TEST: Original elements should STILL be present due to overlap
        // (they're now in active2/aging buffer, which is checked by contains())
        decayed.clear();
        for (Digest digest : inserted) {
            if (!seen.contains(digest)) {
                decayed.add(digest);
            }
        }
        assertEquals(0, decayed.size(), "Original elements should persist in aging buffer");

        // Half batch elements should also be present
        for (Digest digest : halfBatch) {
            if (!seen.contains(digest)) {
                decayed.add(digest);
            }
        }
        assertEquals(0, decayed.size(), "Half batch elements should persist");

        // Now trigger a SECOND swap by filling the buffer again
        // This will clear the aging buffer, losing original elements
        var secondBatch = new ArrayList<Digest>();
        IntStream.range(0, window).mapToObj(i -> algo.random(entropy)).forEach(d -> {
            if (!seen.add(d)) {
                falsePositives.add(d);
            } else {
                secondBatch.add(d);
            }
        });

        // After second swap, original elements should have decayed
        decayed.clear();
        for (Digest digest : inserted) {
            if (!seen.contains(digest)) {
                decayed.add(digest);
            }
        }
        // Most (or all) original elements should now be gone
        assertTrue(decayed.size() > inserted.size() / 2,
                   "Original elements should decay after second swap: " + decayed.size() + "/" + inserted.size());

        // Second batch elements should still be present
        decayed.clear();
        for (Digest digest : secondBatch) {
            if (!seen.contains(digest)) {
                decayed.add(digest);
            }
        }
        assertEquals(0, decayed.size(), "Second batch elements should persist");
    }

    @Test
    public void testConcurrentAccess() throws Exception {
        // Use larger window to avoid overflow during concurrent test
        var window = 5000;
        var seen = BloomWindow.<Digest>create(window, Math.pow(10, -6), Biff.Type.DIGEST);
        var algo = DigestAlgorithm.DEFAULT;

        int numThreads = 4;
        int elementsPerThread = window / 8; // Stay well under capacity to avoid swap
        var executor = Executors.newFixedThreadPool(numThreads);
        var latch = new CountDownLatch(numThreads);
        var addedCount = new AtomicInteger(0);
        var allDigests = new ArrayList<List<Digest>>();

        // Prepare digest lists for each thread (unique random digests)
        for (int t = 0; t < numThreads; t++) {
            var digests = new ArrayList<Digest>();
            for (int i = 0; i < elementsPerThread; i++) {
                digests.add(algo.random());
            }
            allDigests.add(digests);
        }

        // Concurrent adds
        for (int t = 0; t < numThreads; t++) {
            final var digests = allDigests.get(t);
            executor.submit(() -> {
                try {
                    for (var d : digests) {
                        if (seen.add(d)) {
                            addedCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(30, TimeUnit.SECONDS), "Threads should complete");
        executor.shutdown();

        // Verify: most elements should have been added
        int totalElements = numThreads * elementsPerThread;
        assertTrue(addedCount.get() > totalElements / 2,
                   "Should have added most elements: " + addedCount.get() + "/" + totalElements);

        // Verify: added elements should be found (no overflow = no decay)
        int foundCount = 0;
        for (var digests : allDigests) {
            for (var d : digests) {
                if (seen.contains(d)) {
                    foundCount++;
                }
            }
        }
        // Without overflow, all added elements should still be present
        assertTrue(foundCount >= addedCount.get() - 10,
                   "All added elements should be found: " + foundCount + "/" + addedCount.get());
    }

    /**
     * Explicit test of the decay lifecycle.
     * The aging bloom filter works as follows:
     * - Elements go into the active buffer
     * - When active buffer fills (count >= capacity), swap occurs:
     *   - Aging buffer is cleared
     *   - Full active buffer becomes the new aging buffer
     *   - Cleared buffer becomes the new active buffer
     *   - Current element is re-added to fresh active buffer (overlap)
     * - Elements are findable in BOTH buffers
     * - So elements survive ONE swap (in aging buffer)
     * - But decay after TWO swaps (aging buffer cleared)
     */
    @Test
    public void testDecayLifecycle() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 1, 2, 3 });
        var algo = DigestAlgorithm.DEFAULT;

        var capacity = 100;
        var seen = BloomWindow.create(entropy.nextLong(), entropy.nextLong(), capacity,
                                      Math.pow(10, -9), Biff.Type.DIGEST);

        // Phase 1: Insert capacity elements (triggers 1st swap on last element)
        var batch1 = new ArrayList<Digest>();
        for (int i = 0; i < capacity; i++) {
            var d = algo.random(entropy);
            assertTrue(seen.add(d), "Batch1 element " + i + " should be new");
            batch1.add(d);
        }
        // After this: batch1 elements in aging buffer, last element also in active

        // Verify all batch1 elements are findable (in aging buffer)
        for (var d : batch1) {
            assertTrue(seen.contains(d), "Batch1 element should be in aging buffer");
        }

        // Phase 2: Insert PARTIAL batch (capacity - 2 elements)
        // After swap, count=1 (from re-added element), so we can add capacity-2 more
        // without triggering another swap (1 + (capacity-2) = capacity-1 < capacity)
        var batch2 = new ArrayList<Digest>();
        for (int i = 0; i < capacity - 2; i++) {
            var d = algo.random(entropy);
            assertTrue(seen.add(d), "Batch2 element " + i + " should be new");
            batch2.add(d);
        }

        // Batch1 should STILL be findable (aging buffer not cleared yet)
        int batch1Found = 0;
        for (var d : batch1) {
            if (seen.contains(d)) batch1Found++;
        }
        assertEquals(capacity, batch1Found, "All Batch1 elements should persist before 2nd swap");

        // Batch2 should be findable (in active buffer)
        for (var d : batch2) {
            assertTrue(seen.contains(d), "Batch2 element should be in active buffer");
        }

        // Phase 3: Add ONE more element to trigger 2nd swap
        var trigger = algo.random(entropy);
        assertTrue(seen.add(trigger), "Trigger element should be new");
        // Now: aging buffer (batch1) was cleared, batch2+trigger moved to aging

        // Batch1 should now have DECAYED (except the overlap element from first swap)
        // The last batch1 element was re-added to active1 after 1st swap,
        // so it moves to active2 during 2nd swap and survives one more cycle
        int batch1Remaining = 0;
        for (var d : batch1) {
            if (seen.contains(d)) batch1Remaining++;
        }
        assertEquals(1, batch1Remaining, "Only overlap element should survive 2nd swap");

        // Batch2 should still be findable (now in aging buffer)
        int batch2Found = 0;
        for (var d : batch2) {
            if (seen.contains(d)) batch2Found++;
        }
        assertEquals(capacity - 2, batch2Found, "All Batch2 elements should persist in aging buffer");

        // Trigger element should be findable
        assertTrue(seen.contains(trigger), "Trigger element should be in active buffer");
    }

    /**
     * Test that decay occurs at the exact boundary (capacity reached).
     * Verifies the precise timing of when elements age out.
     */
    @Test
    public void testDecayBoundary() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 4, 5, 6 });
        var algo = DigestAlgorithm.DEFAULT;

        var capacity = 50;
        var seen = BloomWindow.create(entropy.nextLong(), entropy.nextLong(), capacity,
                                      Math.pow(10, -9), Biff.Type.DIGEST);

        // Insert batch1: exactly capacity elements (triggers 1st swap on last)
        var batch1 = new ArrayList<Digest>();
        for (int i = 0; i < capacity; i++) {
            var d = algo.random(entropy);
            seen.add(d);
            batch1.add(d);
        }
        // After: batch1 in aging buffer

        // Insert batch2: capacity - 3 elements (leaves room for boundary test)
        // After swap, count=1 (overlap element). Adding capacity-3 gives count=capacity-2.
        // Then almostTrigger makes count=capacity-1, trigger makes count=capacity (swap).
        var batch2 = new ArrayList<Digest>();
        for (int i = 0; i < capacity - 3; i++) {
            var d = algo.random(entropy);
            seen.add(d);
            batch2.add(d);
        }

        // Batch1 should still be in aging buffer (2nd swap not triggered)
        int batch1Found = 0;
        for (var d : batch1) {
            if (seen.contains(d)) batch1Found++;
        }
        assertEquals(capacity, batch1Found, "Batch1 should persist before boundary");

        // Add ONE element - still no swap (count = capacity - 1)
        var almostTrigger = algo.random(entropy);
        seen.add(almostTrigger);

        // Batch1 STILL should be present (count = capacity - 1, no swap yet)
        batch1Found = 0;
        for (var d : batch1) {
            if (seen.contains(d)) batch1Found++;
        }
        assertEquals(capacity, batch1Found, "Batch1 should persist at count = capacity - 1");

        // Add ONE more element - THIS triggers 2nd swap (count = capacity)
        var trigger = algo.random(entropy);
        seen.add(trigger);

        // Now batch1 should have decayed (except overlap element)
        int batch1Remaining = 0;
        for (var d : batch1) {
            if (seen.contains(d)) batch1Remaining++;
        }
        assertEquals(1, batch1Remaining, "Only overlap element should survive 2nd swap");

        // Batch2 + almostTrigger should still be present (in aging buffer)
        int batch2Found = 0;
        for (var d : batch2) {
            if (seen.contains(d)) batch2Found++;
        }
        assertEquals(capacity - 3, batch2Found, "Batch2 should persist in aging buffer");
        assertTrue(seen.contains(almostTrigger), "almostTrigger should be in aging buffer");

        // Trigger element should be present (in active buffer)
        assertTrue(seen.contains(trigger), "Trigger element should be in active buffer");
    }
}
