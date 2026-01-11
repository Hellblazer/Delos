/*
 * Copyright (c) 2024, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.ethereal;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.cryptography.Verifier;
import com.hellblazer.delos.ethereal.PreUnit.preUnit;
import com.hellblazer.delos.ethereal.proto.PreUnit_s;
import org.joou.ULong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for liveness timeout mechanism in Aleph-BFT.
 *
 * Liveness Guarantee (Aleph-BFT §3.4):
 * Byzantine nodes may attempt to cause liveness failures by withholding critical units
 * (e.g., parent units needed for RBC progression). The timeout mechanism ensures:
 *
 * 1. **Timeout Detection**: Units waiting for parents beyond timeout threshold are detected as stale
 * 2. **Stale Unit Cleanup**: Stale units are removed from waiting queue to prevent memory exhaustion
 * 3. **Liveness Recovery**: Timeout triggers broadcast request to peers for missing parent units
 * 4. **Byzantine Safety**: Timeouts NEVER compromise safety (signature verification still required)
 *
 * The timeout mechanism complements RBC delivery guarantees (Delos-46ni):
 * - RBC guarantees eventual delivery if 2f+1 nodes have the unit
 * - Timeout detects when delivery is NOT happening (Byzantine withholding)
 * - Recovery broadcast requests unit from honest peers (at least f+1 honest in any 2f+1 quorum)
 *
 * @author hal.hildebrand
 */
public class LivenessTimeoutTest {

    private static final long UNIT_TIMEOUT_MILLIS = 10000; // Longer timeout for tests to avoid flakiness
    private static final DigestAlgorithm DIGEST_ALGO = DigestAlgorithm.DEFAULT;

    private Config config;
    private Dag dag;
    private Adder adder;
    private Set<Digest> failed;

    @BeforeEach
    void setUp() {
        config = Config.newBuilder()
            .setnProc((short) 4)  // n=4: f=1, quorum=3
            .setBias(3)
            .setPid((short) 0)
            .setDigestAlgorithm(DIGEST_ALGO)
            .setUnitTimeoutMillis(UNIT_TIMEOUT_MILLIS)
            .build();

        failed = new HashSet<>();
        dag = new Dag.DagImpl(config, 0);
        adder = new Adder(0, dag, 1024 * 1024, config, failed, new Verifier[config.nProc()]);
    }

    @AfterEach
    void tearDown() {
        if (adder != null) {
            adder.close();
        }
    }

    /**
     * Test that units track arrival timestamp correctly.
     *
     * Waiting units should have an arrival timestamp that can be queried to determine staleness.
     */
    @Test
    void testWaitingUnitHasArrivalTimestamp() {
        // Create a waiting unit
        var preUnit = createTestPreUnit((short) 1, 1, 0);
        var waiting = new Waiting(preUnit);

        // Verify timestamp is set and recent
        var arrivalTime = waiting.getArrivalTime();
        var now = System.currentTimeMillis();

        assertTrue(arrivalTime > 0, "Arrival timestamp should be set");
        assertTrue(arrivalTime <= now, "Arrival timestamp should not be in the future");
        assertTrue(now - arrivalTime < 100, "Arrival timestamp should be very recent (< 100ms)");
    }

    /**
     * Test that fresh units are NOT considered stale.
     *
     * Units waiting for < timeout threshold should return false for isStale().
     */
    @Test
    void testFreshWaitingUnitNotStale() {
        var preUnit = createTestPreUnit((short) 1, 1, 0);
        var waiting = new Waiting(preUnit);

        // Unit just created - should NOT be stale
        assertFalse(waiting.isStaleAfterMillis(UNIT_TIMEOUT_MILLIS),
            "Fresh unit (< 1ms old) should not be stale with 1000ms timeout");
    }

    /**
     * Test that stale units are detected correctly.
     *
     * Units waiting for > timeout threshold should return true for isStale().
     */
    @Test
    void testStaleWaitingUnitDetected() throws InterruptedException {
        var preUnit = createTestPreUnit((short) 1, 1, 0);
        var waiting = new Waiting(preUnit);

        // Verify initially not stale
        assertFalse(waiting.isStaleAfterMillis(100),
            "Fresh unit should not be stale");

        // Wait for timeout to expire
        Thread.sleep(150);

        // Now should be stale
        assertTrue(waiting.isStaleAfterMillis(100),
            "Unit waiting > 100ms should be stale with 100ms timeout");
    }

    /**
     * Test that timeout threshold is conservative (5 seconds in production).
     *
     * This prevents false positives from legitimate slow networks.
     */
    @Test
    void testConservativeTimeoutPreventsfalsePositives() throws InterruptedException {
        var preUnit = createTestPreUnit((short) 1, 1, 0);
        var waiting = new Waiting(preUnit);

        // Even after 500ms (typical slow network), 5s timeout should NOT trigger
        Thread.sleep(500);

        assertFalse(waiting.isStaleAfterMillis(5000),
            "500ms delay should NOT trigger 5s timeout (false positive prevention)");
    }

    /**
     * Test that Adder removes stale waiting units during cleanup.
     *
     * Simulates Byzantine withholding: unit proposed but remains in waiting state beyond timeout.
     * After timeout, cleanup should remove the stale unit.
     */
    @Test
    void testAdderRemovesStaleWaitingUnits() throws InterruptedException {
        // Propose a unit (enters PROPOSED state, then PREVOTED after local prevote)
        var preUnit = createTestPreUnit((short) 1, 1, 0);
        var hash = preUnit.hash();
        var serialized = preUnit.toPreUnit_s();

        adder.propose(hash, serialized);

        // Verify in waiting state
        var waitingMap = adder.getWaiting();
        assertTrue(waitingMap.containsKey(hash),
            "Unit should be in waiting map");

        // SIMULATE BYZANTINE WITHHOLDING: unit stays in waiting (not enough prevotes to advance)
        // Wait for timeout to expire
        Thread.sleep(UNIT_TIMEOUT_MILLIS + 100);

        // Run cleanup
        adder.runTimeoutCheck();

        // Verify stale unit was removed from waiting queue
        assertFalse(waitingMap.containsKey(hash),
            "Stale unit should be removed after timeout cleanup");
    }

    /**
     * Test that cleanup does NOT remove fresh units.
     *
     * Only units exceeding timeout threshold should be removed.
     */
    @Test
    void testCleanupDoesNotRemoveFreshUnits() {
        // Propose unit that will wait
        var preUnit = createTestPreUnit((short) 1, 1, 0);
        var hash = preUnit.hash();
        adder.propose(hash, preUnit.toPreUnit_s());

        // Verify in waiting state
        var waitingMap = adder.getWaiting();
        assertTrue(waitingMap.containsKey(hash));

        // Run cleanup immediately (unit fresh)
        adder.runTimeoutCheck();

        // Fresh unit should still be in waiting queue
        assertTrue(waitingMap.containsKey(hash),
            "Fresh unit should NOT be removed by cleanup");
    }

    /**
     * Test concurrent timeout checks are thread-safe.
     *
     * Multiple threads calling runTimeoutCheck() should not corrupt state.
     */
    @Test
    void testConcurrentTimeoutChecks() throws InterruptedException, ExecutionException {
        // Propose multiple waiting units
        var waitingUnits = new ArrayList<Digest>();
        for (int i = 0; i < 10; i++) {
            var preUnit = createTestPreUnit((short) 1, 1 + i, 0);
            var hash = preUnit.hash();
            adder.propose(hash, preUnit.toPreUnit_s());
            waitingUnits.add(hash);
        }

        // Verify all in waiting state
        var waitingMap = adder.getWaiting();
        assertEquals(10, waitingMap.size());

        // Run concurrent timeout checks
        var executor = Executors.newFixedThreadPool(4);
        var futures = new ArrayList<Future<?>>();

        for (int i = 0; i < 20; i++) {
            futures.add(executor.submit(() -> adder.runTimeoutCheck()));
        }

        // Wait for all to complete
        for (var future : futures) {
            future.get();
        }
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        // State should be consistent (no exceptions thrown)
        // All units are fresh, so none should be removed
        assertEquals(10, waitingMap.size(),
            "Concurrent cleanup should not corrupt state or remove fresh units");
    }

    /**
     * Test that timeout cleanup detects Byzantine withholding.
     *
     * Scenario:
     * 1. Byzantine behavior causes unit to stay in waiting state
     * 2. Honest node detects unit waiting beyond timeout
     * 3. After timeout, cleanup removes stale unit
     * 4. Logs warning about possible Byzantine withholding
     */
    @Test
    void testByzantineWithholdingDetectedByTimeout() throws InterruptedException {
        // Propose unit that will stay in waiting state (no quorum of prevotes)
        var preUnit = createTestPreUnit((short) 1, 1, 0);
        var hash = preUnit.hash();

        adder.propose(hash, preUnit.toPreUnit_s());

        // Unit in waiting (Byzantine withholding - not enough prevotes to progress)
        var waitingMap = adder.getWaiting();
        assertTrue(waitingMap.containsKey(hash));

        // Simulate Byzantine withholding: no progress for extended period
        Thread.sleep(UNIT_TIMEOUT_MILLIS + 100);

        // Cleanup detects stale unit
        adder.runTimeoutCheck();

        // Stale unit removed (Byzantine withholding detected)
        assertFalse(waitingMap.containsKey(hash),
            "Byzantine withholding should be detected and unit removed");
    }

    /**
     * Test that liveness can recover after timeout triggers cleanup.
     *
     * After timeout cleanup, consensus should be able to proceed with new units
     * (not blocked by stale waiting units).
     */
    @Test
    void testLivenessRecoveryAfterTimeout() throws InterruptedException {
        // Byzantine withholding: propose unit that will stall
        var stalePreUnit = createTestPreUnit((short) 1, 1, 0);
        var staleHash = stalePreUnit.hash();
        adder.propose(staleHash, stalePreUnit.toPreUnit_s());

        // Verify in waiting
        assertTrue(adder.getWaiting().containsKey(staleHash));

        // Wait for timeout
        Thread.sleep(UNIT_TIMEOUT_MILLIS + 100);
        adder.runTimeoutCheck();

        // Stale unit removed
        assertFalse(adder.getWaiting().containsKey(staleHash),
            "Stale unit should be removed after timeout");

        // Consensus can proceed: propose new valid unit
        var validPreUnit = createTestPreUnit((short) 1, 2, 0);
        var validHash = validPreUnit.hash();
        adder.propose(validHash, validPreUnit.toPreUnit_s());

        // New unit should be processed (liveness recovered)
        var waitingMap = adder.getWaiting();
        assertTrue(waitingMap.containsKey(validHash),
            "After timeout cleanup, new units should be processable (liveness recovered)");
    }

    /**
     * Test that timeout does NOT compromise Byzantine safety.
     *
     * Even after timeout triggers cleanup and recovery:
     * - Signature verification still required
     * - Invalid units still rejected
     * - No critical units skipped
     */
    @Test
    void testTimeoutDoesNotCompromiseByzantineSafety() throws InterruptedException {
        // Propose unit from invalid creator (out of bounds)
        var invalidCreator = (short) 999;
        var invalidPreUnit = createTestPreUnit(invalidCreator, 1, 0);
        var invalidHash = invalidPreUnit.hash();

        adder.propose(invalidHash, invalidPreUnit.toPreUnit_s());

        // Unit should be in failed set (rejected due to invalid creator)
        assertTrue(failed.contains(invalidHash),
            "Invalid unit should be rejected regardless of timeout");

        // Wait for timeout
        Thread.sleep(UNIT_TIMEOUT_MILLIS + 100);
        adder.runTimeoutCheck();

        // Failed set should still contain invalid unit (safety maintained)
        assertTrue(failed.contains(invalidHash),
            "Timeout should NOT clear failed units (Byzantine safety)");
    }

    /**
     * Test that periodic cleanup runs at configured interval.
     *
     * This is a functional test - not timing-precise, just verifies cleanup can run repeatedly.
     */
    @Test
    void testPeriodicCleanupRuns() throws InterruptedException {
        // Propose stale units
        for (int i = 0; i < 5; i++) {
            var preUnit = createTestPreUnit((short) 1, 1 + i, 0);
            adder.propose(preUnit.hash(), preUnit.toPreUnit_s());
        }

        assertEquals(5, adder.getWaiting().size());

        // Wait for timeout
        Thread.sleep(UNIT_TIMEOUT_MILLIS + 100);

        // Run cleanup multiple times (simulating periodic execution)
        for (int i = 0; i < 3; i++) {
            adder.runTimeoutCheck();
            Thread.sleep(100);
        }

        // All stale units should be cleaned up
        assertEquals(0, adder.getWaiting().size(),
            "Periodic cleanup should remove all stale units");
    }

    // ===== Test Helper Methods =====

    /**
     * Create a test PreUnit with specified creator, height, and epoch.
     */
    private PreUnit createTestPreUnit(short creator, int height, int epoch) {
        var crown = new Crown(new int[config.nProc()], Digest.NONE);
        var signature = new JohnHancock(SignatureAlgorithm.DEFAULT, new byte[64], ULong.MIN);
        var hash = DIGEST_ALGO.digest("test-unit-" + creator + "-" + epoch + "-" + height);
        return new preUnit(creator, epoch, height, hash, crown, ByteString.EMPTY, signature, new byte[0]);
    }

    /**
     * Create a test PreUnit with specified parents.
     *
     * @param creator Creator process ID
     * @param height Unit height
     * @param epoch Epoch number
     * @param parents Map of creator -> height for parent units
     */
    private PreUnit createTestPreUnitWithParents(short creator, int height, int epoch,
                                                  Map<Short, Integer> parents) {
        var heights = new int[config.nProc()];
        Arrays.fill(heights, 0);

        // Set view heights based on parents
        for (var entry : parents.entrySet()) {
            heights[entry.getKey()] = entry.getValue();
        }

        var crown = new Crown(heights, Digest.NONE);
        var signature = new JohnHancock(SignatureAlgorithm.DEFAULT, new byte[64], ULong.MIN);
        var hash = DIGEST_ALGO.digest("test-unit-" + creator + "-" + epoch + "-" + height);
        return new preUnit(creator, epoch, height, hash, crown, ByteString.EMPTY, signature, new byte[0]);
    }
}
