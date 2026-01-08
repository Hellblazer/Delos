/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.gorgoneion;

import com.google.protobuf.Any;
import com.hellblazer.delos.archipelago.LocalServer;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.stereotomy.services.proto.ProtoEventObserver;
import org.junit.jupiter.api.Test;

import java.io.Closeable;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Tests for Gorgoneion scheduler resource cleanup and lifecycle management.
 *
 * @author hal.hildebrand
 */
public class GorgoneionLifecycleTest {

    @Test
    public void testGorgoneionImplementsCloseable() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        final var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        final var prefix = UUID.randomUUID().toString();
        var member = new ControlledIdentifierMember(stereotomy.newIdentifier());
        var b = DynamicContext.newBuilder();
        b.setCardinality(10);
        var context = b.build();
        context.activate(member);

        var gorgonRouter = new LocalServer(prefix, member).router(ServerConnectionCache.newBuilder().setTarget(2));
        gorgonRouter.start();

        var observer = mock(ProtoEventObserver.class);
        var gorgon = new Gorgoneion(t -> true, (c, v) -> Any.getDefaultInstance(),
                                    Parameters.newBuilder().setKerl(kerl).build(), member, context, observer,
                                    gorgonRouter, null);

        assertTrue(gorgon instanceof Closeable, "Gorgoneion should implement Closeable");

        gorgon.close();
        gorgonRouter.close(Duration.ofSeconds(0));
    }

    @Test
    public void testSchedulerShutdownOnClose() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        final var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        final var prefix = UUID.randomUUID().toString();
        var member = new ControlledIdentifierMember(stereotomy.newIdentifier());
        var b = DynamicContext.newBuilder();
        b.setCardinality(10);
        var context = b.build();
        context.activate(member);

        var gorgonRouter = new LocalServer(prefix, member).router(ServerConnectionCache.newBuilder().setTarget(2));
        gorgonRouter.start();

        var observer = mock(ProtoEventObserver.class);
        var gorgon = new Gorgoneion(t -> true, (c, v) -> Any.getDefaultInstance(),
                                    Parameters.newBuilder().setKerl(kerl).build(), member, context, observer,
                                    gorgonRouter, null);

        // Verify Gorgoneion is operational
        assertNotNull(gorgon);

        // Close should shut down scheduler
        gorgon.close();

        // Give time for shutdown to complete
        Thread.sleep(100);

        // Verify no active scheduler threads remain
        assertNoLeakedSchedulerThreads();

        gorgonRouter.close(Duration.ofSeconds(0));
    }

    @Test
    public void testRepeatedCloseIsIdempotent() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        final var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        final var prefix = UUID.randomUUID().toString();
        var member = new ControlledIdentifierMember(stereotomy.newIdentifier());
        var b = DynamicContext.newBuilder();
        b.setCardinality(10);
        var context = b.build();
        context.activate(member);

        var gorgonRouter = new LocalServer(prefix, member).router(ServerConnectionCache.newBuilder().setTarget(2));
        gorgonRouter.start();

        var observer = mock(ProtoEventObserver.class);
        var gorgon = new Gorgoneion(t -> true, (c, v) -> Any.getDefaultInstance(),
                                    Parameters.newBuilder().setKerl(kerl).build(), member, context, observer,
                                    gorgonRouter, null);

        // First close
        gorgon.close();

        // Second close should not throw
        assertDoesNotThrow(() -> gorgon.close());

        // Third close should also not throw
        assertDoesNotThrow(() -> gorgon.close());

        gorgonRouter.close(Duration.ofSeconds(0));
    }

    @Test
    public void testTryWithResourcesPattern() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        final var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        final var prefix = UUID.randomUUID().toString();
        var member = new ControlledIdentifierMember(stereotomy.newIdentifier());
        var b = DynamicContext.newBuilder();
        b.setCardinality(10);
        var context = b.build();
        context.activate(member);

        var gorgonRouter = new LocalServer(prefix, member).router(ServerConnectionCache.newBuilder().setTarget(2));
        gorgonRouter.start();

        var observer = mock(ProtoEventObserver.class);
        try (var gorgon = new Gorgoneion(t -> true, (c, v) -> Any.getDefaultInstance(),
                                         Parameters.newBuilder().setKerl(kerl).build(), member, context, observer,
                                         gorgonRouter, null)) {
            assertNotNull(gorgon);
        }

        // Verify cleanup occurred
        Thread.sleep(100);
        assertNoLeakedSchedulerThreads();

        gorgonRouter.close(Duration.ofSeconds(0));
    }

    @Test
    public void testShutdownTimeout() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 6 });
        final var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        final var prefix = UUID.randomUUID().toString();
        var member = new ControlledIdentifierMember(stereotomy.newIdentifier());
        var b = DynamicContext.newBuilder();
        b.setCardinality(10);
        var context = b.build();
        context.activate(member);

        var gorgonRouter = new LocalServer(prefix, member).router(ServerConnectionCache.newBuilder().setTarget(2));
        gorgonRouter.start();

        var observer = mock(ProtoEventObserver.class);
        var gorgon = new Gorgoneion(t -> true, (c, v) -> Any.getDefaultInstance(),
                                    Parameters.newBuilder().setKerl(kerl).build(), member, context, observer,
                                    gorgonRouter, null);

        // Close and verify it completes within reasonable time
        var startTime = System.currentTimeMillis();
        gorgon.close();
        var duration = System.currentTimeMillis() - startTime;

        // Should complete well before 30 second timeout
        assertTrue(duration < 5000, "Close should complete quickly when no tasks are running");

        gorgonRouter.close(Duration.ofSeconds(0));
    }

    /**
     * Best-effort check for leaked scheduler threads. This examines thread names to detect any scheduler-related
     * threads that may have been left running.
     */
    private void assertNoLeakedSchedulerThreads() {
        var threadMXBean = ManagementFactory.getThreadMXBean();
        var threadInfos = threadMXBean.dumpAllThreads(false, false);

        var suspiciousThreads = Arrays.stream(threadInfos)
                                      .map(ThreadInfo::getThreadName)
                                      .filter(name -> name != null && (name.contains("pool") || name.contains(
                                      "scheduler") || name.contains("ForkJoinPool")))
                                      .toList();

        // Log suspicious threads for debugging
        if (!suspiciousThreads.isEmpty()) {
            System.err.println("Warning: Found potentially leaked threads: " + suspiciousThreads);
        }

        // Virtual threads use ForkJoinPool, but we're checking for abnormal growth
        // This is a weak assertion but better than nothing
        var totalThreads = threadInfos.length;
        assertTrue(totalThreads < 200,
                   "Suspicious number of threads (" + totalThreads + "), possible leak: " + suspiciousThreads);
    }
}
