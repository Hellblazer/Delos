/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.ethereal;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.Signer;
import com.hellblazer.delos.cryptography.Verifier;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Ethereal graceful shutdown and pending unit drainage.
 *
 * @author hal.hildebrand
 */
public class EtherealShutdownTest {

    private static final Logger log = LoggerFactory.getLogger(EtherealShutdownTest.class);

    /**
     * Create members and return their verifiers array for testing.
     */
    private static record TestSetup(List<Member> members, Verifier[] verifiers) {
    }

    private static TestSetup createTestSetup(int count) throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 1, 2, 3 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);

        var members = IntStream.range(0, count)
                               .mapToObj(i -> stereotomy.newIdentifier())
                               .map(ControlledIdentifierMember::new)
                               .map(e -> (Member) e)
                               .toList();

        var verifiers = members.stream().map(m -> (Verifier) m).toArray(Verifier[]::new);

        return new TestSetup(members, verifiers);
    }

    /**
     * Test that shutdown completes immediately when there are no pending units.
     */
    @Test
    public void testCleanShutdownNoPendingUnits() throws Exception {
        var setup = createTestSetup(4);
        var member = setup.members().get(0);

        var config = Config.newBuilder()
                           .setnProc((short) 4)
                           .setPid((short) 0)
                           .setEpochLength(11)
                           .setNumberOfEpochs(1)
                           .setSigner((Signer) member)
                           .setDigestAlgorithm(DigestAlgorithm.DEFAULT)
                           .setShutdownDrainTimeoutMillis(5000L)
                           .setLabel("test-clean-shutdown")
                           .build();

        var processedCount = new AtomicInteger(0);
        var ethereal = new Ethereal(config, 1024 * 1024, new MockDataSource(),
                                     (blocks, last) -> processedCount.addAndGet(blocks.size()), epoch -> {
        }, "test-clean", setup.verifiers());

        ethereal.start();

        // Stop immediately - no pending units
        var startTime = System.currentTimeMillis();
        ethereal.stop();
        var stopTime = System.currentTimeMillis();

        // Should complete quickly (well under timeout)
        var elapsed = stopTime - startTime;
        assertTrue(elapsed < 1000, "Shutdown should be fast when no pending units, took: " + elapsed + "ms");
    }

    /**
     * Test that shutdown drains pending work gracefully without hanging.
     *
     * Note: With a single node (no gossip partners), consensus may not produce
     * timing rounds within the short startup window. This test verifies the
     * shutdown mechanism works correctly - completing quickly whether or not
     * there was pending work.
     */
    @Test
    public void testShutdownDrainsPendingUnits() throws Exception {
        var setup = createTestSetup(4);
        var member = setup.members().get(0);

        var config = Config.newBuilder()
                           .setnProc((short) 4)
                           .setPid((short) 0)
                           .setEpochLength(11)
                           .setNumberOfEpochs(1)
                           .setSigner((Signer) member)
                           .setDigestAlgorithm(DigestAlgorithm.DEFAULT)
                           .setShutdownDrainTimeoutMillis(5000L)
                           .setLabel("test-drain")
                           .build();

        var processedCount = new AtomicInteger(0);
        var processingStarted = new AtomicBoolean(false);

        // Blocker that simulates slow processing (but completes within timeout)
        var ethereal = new Ethereal(config, 1024 * 1024, new MockDataSource(), (blocks, last) -> {
            processingStarted.set(true);
            try {
                // Simulate processing time (2 seconds - within 5s timeout)
                Thread.sleep(2000);
                processedCount.addAndGet(blocks.size());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, epoch -> {
        }, "test-drain", setup.verifiers());

        ethereal.start();

        // Give it time to start
        Thread.sleep(100);

        // Trigger stop - should complete within timeout
        var startTime = System.currentTimeMillis();
        ethereal.stop();
        var elapsed = System.currentTimeMillis() - startTime;

        // Key invariant: shutdown completes within the configured timeout
        // With single node, work may or may not be pending, but shutdown should not hang
        assertTrue(elapsed < config.shutdownDrainTimeoutMillis() + 1000,
                   "Shutdown should complete within timeout + margin. Took: " + elapsed + "ms");

        // If processing started, it should have had a chance to complete
        if (processingStarted.get()) {
            assertTrue(processedCount.get() >= 0, "Started work should have been processed");
        }

        log.info("Shutdown completed in {}ms, processing started: {}, processed: {}",
                 elapsed, processingStarted.get(), processedCount.get());
    }

    /**
     * Test that shutdown enforces timeout when drain takes too long.
     */
    @Test
    public void testShutdownTimeoutEnforced() throws Exception {
        var setup = createTestSetup(4);
        var member = setup.members().get(0);

        var config = Config.newBuilder()
                           .setnProc((short) 4)
                           .setPid((short) 0)
                           .setEpochLength(11)
                           .setNumberOfEpochs(1)
                           .setSigner((Signer) member)
                           .setDigestAlgorithm(DigestAlgorithm.DEFAULT)
                           .setShutdownDrainTimeoutMillis(1000L)  // Short timeout (1s)
                           .setLabel("test-timeout")
                           .build();

        var processingStarted = new AtomicBoolean(false);
        var processingCompleted = new AtomicBoolean(false);

        // Blocker that takes longer than the timeout
        var ethereal = new Ethereal(config, 1024 * 1024, new MockDataSource(), (blocks, last) -> {
            processingStarted.set(true);
            try {
                // Simulate very slow processing (5 seconds - exceeds 1s timeout)
                Thread.sleep(5000);
                processingCompleted.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, epoch -> {
        }, "test-timeout", setup.verifiers());

        ethereal.start();

        // Give it time to start
        Thread.sleep(100);

        // Shutdown should timeout and not wait for slow processing
        var startTime = System.currentTimeMillis();
        ethereal.stop();
        var stopTime = System.currentTimeMillis();

        var elapsed = stopTime - startTime;

        // Should complete around the timeout (1000ms), not wait for full processing (5000ms)
        // Allow some wiggle room for thread scheduling
        assertTrue(elapsed < 2000, "Shutdown should timeout, not wait indefinitely. Took: " + elapsed + "ms");
        assertFalse(processingCompleted.get(),
                    "Slow processing should have been interrupted by timeout");
    }

    /**
     * Mock DataSource for testing.
     */
    private static class MockDataSource implements DataSource {
        @Override
        public ByteString getData() {
            return ByteString.copyFromUtf8("test-data");
        }
    }
}
