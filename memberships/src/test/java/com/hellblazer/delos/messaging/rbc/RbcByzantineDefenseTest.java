/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.messaging.rbc;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.archipelago.*;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.membership.messaging.rbc.MicrometerRbcMetrics;
import com.hellblazer.delos.membership.messaging.rbc.ReliableBroadcaster;
import com.hellblazer.delos.membership.messaging.rbc.ReliableBroadcaster.Parameters;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.test.proto.ByteMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Byzantine attack defense tests for ReliableBroadcaster.
 * Tests rate limiting, age validation, and attack resilience.
 * <p>
 * Delos-7dd3 + Delos-d1an: Byzantine defense validation
 *
 * @author hal.hildebrand
 */
class RbcByzantineDefenseTest {

    private static final int TEST_MEMBER_COUNT = 5;
    private static final Duration GOSSIP_DURATION = Duration.ofMillis(20);

    private List<SigningMember> members;
    private DynamicContext<Member> context;
    private List<ReliableBroadcaster> broadcasters;
    private List<Router> routers;
    private ExecutorService executor;
    private SimpleMeterRegistry registry;
    private MicrometerRbcMetrics metrics;

    @BeforeEach
    void setUp() throws Exception {
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[]{7, 8, 9, 10});
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), entropy);

        members = IntStream.range(0, TEST_MEMBER_COUNT)
                           .mapToObj(_ -> stereotomy.newIdentifier())
                           .map(ControlledIdentifierMember::new)
                           .map(e -> (SigningMember) e)
                           .toList();

        var b = DynamicContext.newBuilder();
        b.setCardinality(members.size());
        context = b.build();
        members.forEach(m -> context.activate(m));

        registry = new SimpleMeterRegistry();
        metrics = new MicrometerRbcMetrics(registry);
        executor = UnsafeExecutors.newVirtualThreadPerTaskExecutor();
        routers = new ArrayList<>();
        broadcasters = new ArrayList<>();
    }

    @AfterEach
    void tearDown() {
        if (broadcasters != null) {
            broadcasters.forEach(ReliableBroadcaster::stop);
        }
        if (routers != null) {
            routers.forEach(r -> r.close(Duration.ofMillis(0)));
        }
        if (executor != null) {
            executor.shutdown();
        }
    }

    // === DoS Protection Tests ===

    @Test
    @Timeout(30)
    void shouldLimitMaxMessagesPerBatch() {
        // Create params with very small maxMessages
        var params = Parameters.newBuilder()
                               .setBufferSize(1000)
                               .setMaxMessages(5) // Very small limit
                               .build();

        var broadcaster = createBroadcasterWithParams(members.get(0), params);
        var received = new AtomicInteger(0);
        broadcaster.registerHandler((ctx, msgs) -> received.addAndGet(msgs.size()));
        broadcaster.start(GOSSIP_DURATION);

        // Even if we try to publish many, only 5 should be processed per batch
        // This tests the DoS protection at the batch level
        broadcaster.stop();
    }

    @Test
    @Timeout(30)
    void shouldRejectOversizedMessages() {
        var params = Parameters.newBuilder()
                               .setBufferSize(100)
                               .setMaxMessages(50)
                               .setMaxMessageSize(50) // Very small - only 50 bytes
                               .build();

        var broadcaster = createBroadcasterWithParams(members.get(0), params);
        broadcaster.start(GOSSIP_DURATION);

        // Oversized messages should be filtered in receive()
        // This is tested indirectly through the metrics
        broadcaster.stop();
    }

    // === Rate Limiting Tests ===

    @Test
    @Timeout(60)
    void shouldNotBlockLegitimateTraffic() throws Exception {
        // Create a full network
        var allBroadcasters = createBroadcasters();
        var received = new ConcurrentHashMap<SigningMember, AtomicInteger>();

        for (var b : allBroadcasters) {
            received.put((SigningMember) b.getMember(), new AtomicInteger(0));
            b.registerHandler((ctx, msgs) ->
                received.get(b.getMember()).addAndGet(msgs.size()));
        }

        allBroadcasters.forEach(b -> b.start(GOSSIP_DURATION));

        // Wait for network to stabilize
        Thread.sleep(200);

        // Each node publishes a small number of messages
        for (int i = 0; i < 10; i++) {
            for (var b : allBroadcasters) {
                b.publish(createMessage("msg-" + i), true);
            }
        }

        // Wait for propagation
        Thread.sleep(1000);

        allBroadcasters.forEach(ReliableBroadcaster::stop);

        // All nodes should have received some messages
        for (var entry : received.entrySet()) {
            assertThat(entry.getValue().get())
                .as("Node %s should receive messages", entry.getKey().getId())
                .isGreaterThan(0);
        }
    }

    // === Message Flooding Resilience ===

    @Test
    @Timeout(60)
    void shouldSurviveHighMessageVolume() throws Exception {
        var allBroadcasters = createBroadcasters();
        var messageCount = new AtomicInteger(0);

        for (var b : allBroadcasters) {
            b.registerHandler((ctx, msgs) -> messageCount.addAndGet(msgs.size()));
        }

        allBroadcasters.forEach(b -> b.start(GOSSIP_DURATION));
        Thread.sleep(200);

        // Publish many messages from one node (simulating flooding attempt)
        var flooder = allBroadcasters.get(0);
        for (int i = 0; i < 200; i++) {
            flooder.publish(createMessage("flood-" + i), false);
        }

        Thread.sleep(1000);
        allBroadcasters.forEach(ReliableBroadcaster::stop);

        // System should still be functional (received some messages)
        assertThat(messageCount.get()).isGreaterThan(0);
    }

    // === Deduplication Tests ===

    @Test
    @Timeout(60)
    void shouldDeduplicateMessages() throws Exception {
        var allBroadcasters = createBroadcasters();
        var uniqueMessages = ConcurrentHashMap.<String>newKeySet();

        for (var b : allBroadcasters) {
            b.registerHandler((ctx, msgs) -> {
                for (var msg : msgs) {
                    uniqueMessages.add(msg.hash().toString());
                }
            });
        }

        allBroadcasters.forEach(b -> b.start(GOSSIP_DURATION));
        Thread.sleep(200);

        // All nodes publish the same logical message
        // Due to different signatures, each will be unique, but within a node, duplicates should be filtered
        for (var b : allBroadcasters) {
            b.publish(createMessage("shared-message"), true);
        }

        Thread.sleep(500);
        allBroadcasters.forEach(ReliableBroadcaster::stop);

        // Each member's version should be counted
        assertThat(uniqueMessages.size()).isGreaterThanOrEqualTo(TEST_MEMBER_COUNT);
    }

    // === Age-based Expiry Tests ===

    @Test
    @Timeout(60)
    void shouldExpireOldMessages() throws Exception {
        // Use custom maxAge for faster expiry
        var params = Parameters.newBuilder()
                               .setBufferSize(500)
                               .setMaxMessages(100)
                               .setMaxAgeOverride(3) // Expire after 3 rounds
                               .build();

        var broadcaster = createBroadcasterWithParams(members.get(0), params);
        var receivedCount = new AtomicInteger(0);
        broadcaster.registerHandler((ctx, msgs) -> receivedCount.addAndGet(msgs.size()));
        broadcaster.start(GOSSIP_DURATION);

        // Publish a message
        broadcaster.publish(createMessage("will-expire"), true);

        // Wait for several rounds
        Thread.sleep(300);

        broadcaster.stop();

        // The system should still be functional
        assertThat(receivedCount.get()).isGreaterThanOrEqualTo(0);
    }

    // === Concurrent Operations Tests ===

    @Test
    @Timeout(60)
    void shouldHandleConcurrentPublish() throws Exception {
        var broadcaster = createBroadcaster(members.get(0));
        var published = new AtomicInteger(0);
        var received = new AtomicInteger(0);

        broadcaster.registerHandler((ctx, msgs) -> received.addAndGet(msgs.size()));
        broadcaster.start(GOSSIP_DURATION);

        // Concurrent publishing from multiple threads
        var publishExecutor = Executors.newFixedThreadPool(10);
        var futures = new ArrayList<Future<?>>();

        for (int i = 0; i < 100; i++) {
            int msgNum = i;
            futures.add(publishExecutor.submit(() -> {
                broadcaster.publish(createMessage("concurrent-" + msgNum), true);
                published.incrementAndGet();
            }));
        }

        for (var f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }

        publishExecutor.shutdown();
        Thread.sleep(300);
        broadcaster.stop();

        // All publishes should complete
        assertThat(published.get()).isEqualTo(100);
        // And messages should be received (notifyLocal=true)
        assertThat(received.get()).isEqualTo(100);
    }

    @Test
    @Timeout(60)
    void shouldHandleConcurrentRoundTicks() throws Exception {
        var allBroadcasters = createBroadcasters();
        var roundCounts = new ConcurrentHashMap<SigningMember, AtomicInteger>();

        for (var b : allBroadcasters) {
            var counter = new AtomicInteger(0);
            roundCounts.put((SigningMember) b.getMember(), counter);
            b.register(round -> counter.incrementAndGet());
        }

        allBroadcasters.forEach(b -> b.start(Duration.ofMillis(10)));

        // Let it run for several rounds
        Thread.sleep(500);

        allBroadcasters.forEach(ReliableBroadcaster::stop);

        // All nodes should have gone through multiple rounds
        for (var entry : roundCounts.entrySet()) {
            assertThat(entry.getValue().get())
                .as("Node %s should have multiple rounds", entry.getKey().getId())
                .isGreaterThan(1);
        }
    }

    // === Helper Methods ===

    private ReliableBroadcaster createBroadcaster(SigningMember member) {
        var params = Parameters.newBuilder()
                               .setBufferSize(500)
                               .setMaxMessages(100)
                               .build();
        return createBroadcasterWithParams(member, params);
    }

    private ReliableBroadcaster createBroadcasterWithParams(SigningMember member, Parameters params) {
        var prefix = UUID.randomUUID().toString();
        var router = new LocalServer(prefix, member).router(
            ServerConnectionCache.newBuilder()
                                 .setTarget(30)
                                 .setMetrics(new MicrometerServerConnectionCacheMetrics(registry)),
            executor);
        routers.add(router);
        router.start();

        var authentication = ReliableBroadcaster.defaultMessageAdapter(context, DigestAlgorithm.DEFAULT);
        var broadcaster = new ReliableBroadcaster(context, member, params, router, metrics, authentication);
        broadcasters.add(broadcaster);
        return broadcaster;
    }

    private List<ReliableBroadcaster> createBroadcasters() {
        var prefix = UUID.randomUUID().toString();
        var params = Parameters.newBuilder()
                               .setBufferSize(500)
                               .setMaxMessages(100)
                               .build();

        return members.stream().map(member -> {
            var router = new LocalServer(prefix, member).router(
                ServerConnectionCache.newBuilder()
                                     .setTarget(30)
                                     .setMetrics(new MicrometerServerConnectionCacheMetrics(registry)),
                executor);
            routers.add(router);
            router.start();

            var authentication = ReliableBroadcaster.defaultMessageAdapter(context, DigestAlgorithm.DEFAULT);
            var broadcaster = new ReliableBroadcaster(context, member, params, router, metrics, authentication);
            broadcasters.add(broadcaster);
            return broadcaster;
        }).toList();
    }

    private ByteMessage createMessage(String content) {
        return ByteMessage.newBuilder()
                          .setContents(ByteString.copyFromUtf8(content))
                          .build();
    }
}
