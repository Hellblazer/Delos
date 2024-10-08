/*
 * Copyright (c) 2020, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.messaging.rbc;

import com.codahale.metrics.ConsoleReporter;
import com.codahale.metrics.MetricRegistry;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hellblazer.delos.archipelago.*;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.membership.messaging.rbc.RbcMetricsImpl;
import com.hellblazer.delos.membership.messaging.rbc.ReliableBroadcaster;
import com.hellblazer.delos.membership.messaging.rbc.ReliableBroadcaster.MessageHandler;
import com.hellblazer.delos.membership.messaging.rbc.ReliableBroadcaster.Msg;
import com.hellblazer.delos.membership.messaging.rbc.ReliableBroadcaster.Parameters;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.test.proto.ByteMessage;
import com.hellblazer.delos.utils.Entropy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author hal.hildebrand
 */
public class RbcTest {

    private static final boolean                         LARGE_TESTS    = Boolean.getBoolean("large_tests");
    private static final Parameters.Builder              parameters     = Parameters.newBuilder()
                                                                                    .setMaxMessages(100)
                                                                                    .setFalsePositiveRate(0.00125)
                                                                                    .setBufferSize(500);
    final                AtomicReference<CountDownLatch> round          = new AtomicReference<>();
    private final        List<Router>                    communications = new ArrayList<>();
    private final        AtomicInteger                   totalReceived  = new AtomicInteger(0);
    private              List<ReliableBroadcaster>       messengers;
    private              ExecutorService                 executor;

    @AfterEach
    public void after() {
        if (messengers != null) {
            messengers.forEach(e -> e.stop());
        }
        communications.forEach(e -> e.close(Duration.ofMillis(0)));
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Test
    public void broadcast() throws Exception {
        executor = UnsafeExecutors.newVirtualThreadPerTaskExecutor();
        MetricRegistry registry = new MetricRegistry();

        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[] { 6, 6, 7, 6 });
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);

        var members = IntStream.range(0, 50)
                               .mapToObj(i -> stereotomy.newIdentifier())
                               .map(cpk -> new ControlledIdentifierMember(cpk))
                               .map(e -> (SigningMember) e)
                               .toList();

        var b = DynamicContext.newBuilder();
        b.setCardinality(members.size());
        var context = b.build();
        var metrics = new RbcMetricsImpl(context.getId(), "test", registry);
        members.forEach(m -> context.activate(m));

        final var prefix = UUID.randomUUID().toString();
        final var authentication = ReliableBroadcaster.defaultMessageAdapter(context, DigestAlgorithm.DEFAULT);
        messengers = members.stream().map(node -> {
            var comms = new LocalServer(prefix, node).router(
            ServerConnectionCache.newBuilder().setTarget(30).setMetrics(new ServerConnectionCacheMetricsImpl(registry)),
            executor);
            communications.add(comms);
            comms.start();
            return new ReliableBroadcaster(context, node, parameters.build(), comms, metrics, authentication);
        }).collect(Collectors.toList());

        System.out.println("Messaging with " + messengers.size() + " members");
        messengers.forEach(view -> view.start(Duration.ofMillis(10)));

        Map<Member, Receiver> receivers = new HashMap<>();
        AtomicInteger current = new AtomicInteger(-1);
        for (ReliableBroadcaster view : messengers) {
            Receiver receiver = new Receiver(view.getMember().getId(), messengers.size(), current);
            view.registerHandler(receiver);
            receivers.put(view.getMember(), receiver);
        }
        System.out.println();
        int rounds = LARGE_TESTS ? 100 : 5;
        for (int r = 0; r < rounds; r++) {
            CountDownLatch latch = new CountDownLatch(messengers.size());
            round.set(latch);
            var rnd = r;
            System.out.printf("\nround: %s ", r);
            messengers.stream().forEach(view -> {
                byte[] rand = new byte[32];
                Entropy.nextSecureBytes(rand);
                ByteBuffer buf = ByteBuffer.wrap(new byte[36]);
                buf.putInt(rnd);
                buf.put(rand);
                buf.flip();
                view.publish(ByteMessage.newBuilder().setContents(ByteString.copyFrom(buf)).build(), true);
            });
            boolean success = latch.await(60, TimeUnit.SECONDS);
            assertTrue(success, "Did not complete round: " + r + " waiting for: " + latch.getCount());

            current.incrementAndGet();
            for (Receiver receiver : receivers.values()) {
                receiver.reset();
            }
        }
        communications.forEach(e -> e.close(Duration.ofMillis(0)));

        System.out.println();

        if (Boolean.getBoolean("reportMetrics")) {
            ConsoleReporter.forRegistry(registry)
                           .convertRatesTo(TimeUnit.SECONDS)
                           .convertDurationsTo(TimeUnit.MILLISECONDS)
                           .build()
                           .report();
        }
    }

    class Receiver implements MessageHandler {
        final Set<Digest>   counted = Collections.newSetFromMap(new ConcurrentHashMap<>());
        final AtomicInteger current;
        final Digest        memberId;

        Receiver(Digest memberId, int cardinality, AtomicInteger current) {
            this.current = current;
            this.memberId = memberId;
        }

        @Override
        public void message(Digest context, List<Msg> messages) {
            messages.forEach(m -> {
                assert m.source() != null : "null member";
                ByteBuffer buf;
                try {
                    buf = ByteMessage.parseFrom(m.content()).getContents().asReadOnlyByteBuffer();
                } catch (InvalidProtocolBufferException e) {
                    throw new IllegalStateException(e);
                }
                assert buf.remaining() > 4 : "buffer: " + buf.remaining();
                final var index = buf.getInt();
                if (index == current.get() + 1) {
                    if (counted.add(m.source().get(0))) {
                        int totalCount = totalReceived.incrementAndGet();
                        if (totalCount % 100 == 0) {
                            System.out.print(".");
                        }
                        if (counted.size() == messengers.size() - 1) {
                            round.get().countDown();
                        }
                    }
                }
            });
        }

        void reset() {
            counted.clear();
        }
    }
}
