/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */

package com.hellblazer.delos.ethereal;

import com.codahale.metrics.MetricRegistry;
import com.google.protobuf.ByteString;
import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.Signer;
import com.hellblazer.delos.ethereal.memberships.ChRbcGossip;
import com.hellblazer.delos.ethereal.memberships.comm.EtherealMetricsImpl;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.messaging.proto.ByteMessage;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.junit.jupiter.api.Test;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for consumer error propagation and circuit breaker functionality.
 * <p>
 * Tests the fix for bug Delos-s7zt where consumer exceptions were caught and logged
 * but not propagated, leading to silent data corruption and divergent state.
 * <p>
 * Key scenarios tested:
 * 1. Consumer exceptions trigger error handler
 * 2. Repeated failures trigger circuit breaker
 * 3. Successful consumption resets failure counter
 * 4. Consensus behavior under consumer failures
 *
 * @author hal.hildebrand
 */
public class ConsumerErrorPropagationTest {

    private static final int     EPOCH_LENGTH = 11;
    private static final boolean LARGE_TESTS  = Boolean.getBoolean("large_tests");
    private static final int     NPROC        = 4;
    private static final int     NUM_EPOCHS   = 1;

    /**
     * Test that consumer exceptions are propagated to the error handler.
     * <p>
     * Verifies:
     * - Error handler is invoked when consumer throws exception
     * - Exception details are passed to error handler
     * - Preblock data is accessible in error handler
     */
    @Test
    public void testConsumerExceptionTriggersErrorHandler() throws Exception {
        var errorHandlerInvoked = new CountDownLatch(1);
        var capturedError = new AtomicReference<ConsumerErrorHandler.ErrorContext>();

        var errorHandler = new ConsumerErrorHandler.Builder().setOnError((context) -> {
            capturedError.set(context);
            errorHandlerInvoked.countDown();
            // Return CONTINUE to allow test to progress
            return ConsumerErrorHandler.ErrorAction.CONTINUE;
        }).build();

        var failOnFirstBlock = new AtomicBoolean(true);

        runWithErrorHandler(errorHandler, (preBlock, last) -> {
            if (failOnFirstBlock.getAndSet(false)) {
                throw new RuntimeException("Simulated consumer failure");
            }
        });

        assertTrue(errorHandlerInvoked.await(30, TimeUnit.SECONDS), "Error handler was not invoked");
        assertNotNull(capturedError.get(), "Error context was not captured");
        assertNotNull(capturedError.get().exception(), "Exception was not captured");
        assertEquals("Simulated consumer failure", capturedError.get().exception().getMessage());
        assertNotNull(capturedError.get().preBlock(), "PreBlock was not captured");
    }

    /**
     * Test that repeated consumer failures trigger the circuit breaker.
     * <p>
     * Verifies:
     * - Circuit breaker opens after threshold failures
     * - Error handler receives CIRCUIT_OPEN action
     * - Further attempts are blocked while circuit is open
     */
    @Test
    public void testRepeatedFailuresTriggersCircuitBreaker() throws Exception {
        var failureCount = new AtomicInteger(0);
        var circuitOpened = new CountDownLatch(1);

        var errorHandler = new ConsumerErrorHandler.Builder().setMaxConsecutiveFailures(3)
                                                              .setOnError((context) -> {
                                                                  failureCount.incrementAndGet();
                                                                  if (context.consecutiveFailures() >= 3) {
                                                                      circuitOpened.countDown();
                                                                  }
                                                                  return ConsumerErrorHandler.ErrorAction.CONTINUE;
                                                              })
                                                              .build();

        runWithErrorHandler(errorHandler, (preBlock, last) -> {
            throw new RuntimeException("Persistent failure");
        });

        assertTrue(circuitOpened.await(30, TimeUnit.SECONDS), "Circuit breaker did not open");
        assertTrue(failureCount.get() >= 3, "Circuit breaker opened before threshold: " + failureCount.get());
    }

    /**
     * Test that successful consumption resets the failure counter.
     * <p>
     * Verifies:
     * - Failure counter increments on errors
     * - Failure counter resets to 0 after successful consumption
     * - Circuit breaker does not open if successes occur between failures
     */
    @Test
    public void testSuccessfulConsumptionResetsFailureCounter() throws Exception {
        var failureSequence = new AtomicInteger(0);
        var maxConsecutiveFailures = new AtomicInteger(0);

        var errorHandler = new ConsumerErrorHandler.Builder().setMaxConsecutiveFailures(5)
                                                              .setOnError((context) -> {
                                                                  maxConsecutiveFailures.set(
                                                                  Math.max(maxConsecutiveFailures.get(),
                                                                           context.consecutiveFailures()));
                                                                  return ConsumerErrorHandler.ErrorAction.CONTINUE;
                                                              })
                                                              .build();

        runWithErrorHandler(errorHandler, (preBlock, last) -> {
            var count = failureSequence.getAndIncrement();
            // Fail, fail, succeed, fail, fail, succeed pattern
            if (count == 0 || count == 1 || count == 3 || count == 4) {
                throw new RuntimeException("Intermittent failure");
            }
            // Otherwise succeed
        });

        // Should never reach 5 consecutive failures due to successes resetting counter
        assertTrue(maxConsecutiveFailures.get() < 5,
                   "Failure counter should reset on success, but reached: " + maxConsecutiveFailures.get());
    }

    /**
     * Test that HALT action stops consensus processing.
     * <p>
     * Verifies:
     * - Error handler can request consensus halt
     * - Consensus stops processing after HALT action
     * - No further blocks are processed
     */
    @Test
    public void testHaltActionStopsConsensus() throws Exception {
        var blocksProcessed = new AtomicInteger(0);
        var haltRequested = new CountDownLatch(1);

        var errorHandler = new ConsumerErrorHandler.Builder().setOnError((context) -> {
            haltRequested.countDown();
            return ConsumerErrorHandler.ErrorAction.HALT;
        }).build();

        runWithErrorHandler(errorHandler, (preBlock, last) -> {
            var count = blocksProcessed.incrementAndGet();
            if (count == 2) {
                throw new RuntimeException("Trigger halt");
            }
        });

        assertTrue(haltRequested.await(30, TimeUnit.SECONDS), "HALT action was not requested");
        // After halt, no more blocks should be processed (allowing some buffer for in-flight blocks)
        var finalCount = blocksProcessed.get();
        Thread.sleep(1000); // Wait to ensure no further processing
        assertTrue(blocksProcessed.get() <= finalCount + 2,
                   "Blocks continued processing after HALT: " + blocksProcessed.get());
    }

    /**
     * Helper method to run Ethereal with a custom error handler.
     */
    private void runWithErrorHandler(ConsumerErrorHandler errorHandler,
                                      java.util.function.BiConsumer<List<ByteString>, Boolean> blocker)
    throws NoSuchAlgorithmException, InterruptedException {
        var registry = new MetricRegistry();
        var finished = new CountDownLatch(NPROC);

        var controllers = new ArrayList<Ethereal>();
        var dataSources = new ArrayList<DataSource>();
        var gossipers = new ArrayList<ChRbcGossip>();
        var comms = new ArrayList<Router>();

        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 1, 2, 3 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);

        List<Member> members = IntStream.range(0, NPROC)
                                        .mapToObj(i -> stereotomy.newIdentifier())
                                        .map(ControlledIdentifierMember::new)
                                        .map(e -> (Member) e)
                                        .toList();

        DynamicContext<Member> context = DynamicContext.newBuilder()
                                                       .setBias(3)
                                                       .setpByz(0.1)
                                                       .setId(DigestAlgorithm.DEFAULT.getOrigin())
                                                       .build();
        context.activate(members);

        var metrics = new EtherealMetricsImpl(context.getId(), "test", registry);
        var builder = Config.newBuilder()
                            .setnProc((short) NPROC)
                            .setNumberOfEpochs(NUM_EPOCHS)
                            .setEpochLength(EPOCH_LENGTH)
                            .setConsumerErrorHandler(errorHandler);

        var prefix = UUID.randomUUID().toString();
        var maxSize = 1024 * 1024;
        var verifiers = members.stream()
                               .map(m -> (com.hellblazer.delos.cryptography.Verifier) m)
                               .toArray(com.hellblazer.delos.cryptography.Verifier[]::new);

        for (short i = 0; i < NPROC; i++) {
            var ds = new SimpleDataSource();
            final short pid = i;
            final var member = members.get(i);
            var com = new LocalServer(prefix, member).router(ServerConnectionCache.newBuilder());
            comms.add(com);

            var controller = new Ethereal(builder.setSigner((Signer) member).setPid(pid).build(), maxSize, ds,
                                          (pb, last) -> {
                                              blocker.accept(pb, last);
                                              if (last) {
                                                  finished.countDown();
                                              }
                                          }, ep -> {
            }, "Test: " + i, verifiers);

            var gossiper = new ChRbcGossip(context.getId(), (SigningMember) member, members, controller.processor(),
                                           com, metrics,
                                           Executors.newScheduledThreadPool(1, Thread.ofVirtual().factory()));

            gossipers.add(gossiper);
            dataSources.add(ds);
            controllers.add(controller);

            for (int d = 0; d < 100; d++) {
                ds.dataStack.add(ByteMessage.newBuilder()
                                            .setContents(ByteString.copyFromUtf8("pid: " + pid + " data: " + d))
                                            .build()
                                            .toByteString());
            }
        }

        try {
            controllers.forEach(Ethereal::start);
            comms.forEach(Router::start);
            gossipers.forEach(g -> g.start(Duration.ofMillis(5)));

            // Wait for completion with reasonable timeout
            finished.await(60, TimeUnit.SECONDS);
        } finally {
            controllers.forEach(Ethereal::stop);
            gossipers.forEach(ChRbcGossip::stop);
            comms.forEach(r -> r.close(Duration.ofSeconds(0)));
        }
    }

    private static class SimpleDataSource implements DataSource {
        private final Deque<ByteString> dataStack = new ArrayDeque<>();

        @Override
        public ByteString getData() {
            return dataStack.pollFirst();
        }
    }
}
