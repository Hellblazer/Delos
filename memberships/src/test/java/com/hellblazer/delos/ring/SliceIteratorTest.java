package com.hellblazer.delos.ring;

import com.google.protobuf.Any;
import com.hellblazer.delos.archipelago.Router;
import com.hellblazer.delos.archipelago.RouterImpl;
import com.hellblazer.delos.archipelago.ServerConnectionCache;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.impl.SigningMemberImpl;
import com.hellblazer.delos.utils.Utils;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import org.joou.ULong;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * @author hal.hildebrand
 **/
public class SliceIteratorTest {
    @Test
    public void smokin() throws Exception {
        var serverMember1 = new SigningMemberImpl(Utils.getMember(0), ULong.MIN);
        var serverMember2 = new SigningMemberImpl(Utils.getMember(1), ULong.MIN);
        var pinged1 = new AtomicBoolean();
        var pinged2 = new AtomicBoolean();

        var local1 = new TestItService() {

            @Override
            public void close() throws IOException {
            }

            @Override
            public Member getMember() {
                return serverMember1;
            }

            @Override
            public Any ping(Any request) {
                pinged1.set(true);
                return Any.getDefaultInstance();
            }
        };
        var local2 = new TestItService() {

            @Override
            public void close() throws IOException {
            }

            @Override
            public Member getMember() {
                return serverMember2;
            }

            @Override
            public Any ping(Any request) {
                pinged2.set(true);
                return Any.getDefaultInstance();
            }
        };
        final var name = UUID.randomUUID().toString();
        DynamicContext<Member> context = DynamicContext.newBuilder().build();
        context.activate(serverMember1);
        context.activate(serverMember2);

        var serverBuilder = InProcessServerBuilder.forName(name);
        var cacheBuilder = ServerConnectionCache.newBuilder()
                                                .setFactory(to -> InProcessChannelBuilder.forName(name).build());
        Router router = new RouterImpl(serverMember1, serverBuilder, cacheBuilder, null);
        try {
            RouterImpl.CommonCommunications<TestItService, TestIt> commsA = router.create(serverMember1,
                                                                                          context.getId(),
                                                                                          new ServiceImpl(local1, "A"),
                                                                                          "A", ServerImpl::new,
                                                                                          TestItClient::new, local1);

            RouterImpl.CommonCommunications<TestItService, TestIt> commsB = router.create(serverMember2,
                                                                                          context.getId(),
                                                                                          new ServiceImpl(local2, "B"),
                                                                                          "B", ServerImpl::new,
                                                                                          TestItClient::new, local2);

            router.start();
            var slice = new SliceIterator<TestItService>("Test Me", serverMember1,
                                                         Arrays.asList(serverMember1, serverMember2), commsA);
            var countdown = new CountDownLatch(1);
            slice.iterate((link) -> link.ping(Any.getDefaultInstance()), (_, _, _, _) -> true, () -> {
                countdown.countDown();
            }, Duration.ofMillis(1));
            boolean finished = countdown.await(3, TimeUnit.SECONDS);
            assertTrue(finished, "completed: " + countdown.getCount());
            assertTrue(pinged1.get());
            assertTrue(pinged2.get());
        } finally {
            router.close(Duration.ofSeconds(0));
        }
    }

    @Test
    public void collectAsyncSuccess() throws Exception {
        var serverMember1 = new SigningMemberImpl(Utils.getMember(0), ULong.MIN);
        var serverMember2 = new SigningMemberImpl(Utils.getMember(1), ULong.MIN);
        var serverMember3 = new SigningMemberImpl(Utils.getMember(2), ULong.MIN);

        var local1 = createTestService(serverMember1, "response1");
        var local2 = createTestService(serverMember2, "response2");
        var local3 = createTestService(serverMember3, "response3");

        final var name = UUID.randomUUID().toString();
        DynamicContext<Member> context = DynamicContext.newBuilder().build();
        context.activate(serverMember1);
        context.activate(serverMember2);
        context.activate(serverMember3);

        var serverBuilder = InProcessServerBuilder.forName(name);
        var cacheBuilder = ServerConnectionCache.newBuilder()
                                                .setFactory(to -> InProcessChannelBuilder.forName(name).build());
        Router router = new RouterImpl(serverMember1, serverBuilder, cacheBuilder, null);
        try {
            router.create(serverMember1, context.getId(), new ServiceImpl(local1, "A"), "A", ServerImpl::new,
                          TestItClient::new, local1);
            router.create(serverMember2, context.getId(), new ServiceImpl(local2, "B"), "B", ServerImpl::new,
                          TestItClient::new, local2);
            router.create(serverMember3, context.getId(), new ServiceImpl(local3, "C"), "C", ServerImpl::new,
                          TestItClient::new, local3);

            var comms = router.create(serverMember1, context.getId(), new ServiceImpl(local1, "Test"), "Test",
                                      ServerImpl::new, TestItClient::new, local1);

            router.start();

            var slice = new SliceIterator<TestItService>("collectTest", serverMember1,
                                                         Arrays.asList(serverMember1, serverMember2, serverMember3),
                                                         comms);

            var future = slice.collectAsync(link -> {
                var result = link.ping(Any.getDefaultInstance());
                return result.getTypeUrl();
            }, response -> response != null && !response.isEmpty(), 2, // require 2 responses
                                                                    Duration.ofMillis(10), Duration.ofSeconds(5));

            var result = future.get(10, TimeUnit.SECONDS);
            assertNotNull(result);
            assertTrue(result.size() >= 2, "Expected at least 2 responses, got: " + result.size());
        } finally {
            router.close(Duration.ofSeconds(0));
        }
    }

    @Test
    public void collectAsyncQuorumNotReached() throws Exception {
        var serverMember1 = new SigningMemberImpl(Utils.getMember(0), ULong.MIN);
        var serverMember2 = new SigningMemberImpl(Utils.getMember(1), ULong.MIN);

        // Only one member returns valid response
        var pingCount = new AtomicInteger(0);
        var local1 = new TestItService() {
            @Override
            public void close() throws IOException {
            }

            @Override
            public Member getMember() {
                return serverMember1;
            }

            @Override
            public Any ping(Any request) {
                pingCount.incrementAndGet();
                return Any.pack(com.google.protobuf.StringValue.of("valid"));
            }
        };
        var local2 = new TestItService() {
            @Override
            public void close() throws IOException {
            }

            @Override
            public Member getMember() {
                return serverMember2;
            }

            @Override
            public Any ping(Any request) {
                pingCount.incrementAndGet();
                return Any.getDefaultInstance(); // Invalid - empty
            }
        };

        final var name = UUID.randomUUID().toString();
        DynamicContext<Member> context = DynamicContext.newBuilder().build();
        context.activate(serverMember1);
        context.activate(serverMember2);

        var serverBuilder = InProcessServerBuilder.forName(name);
        var cacheBuilder = ServerConnectionCache.newBuilder()
                                                .setFactory(to -> InProcessChannelBuilder.forName(name).build());
        Router router = new RouterImpl(serverMember1, serverBuilder, cacheBuilder, null);
        try {
            router.create(serverMember1, context.getId(), new ServiceImpl(local1, "A"), "A", ServerImpl::new,
                          TestItClient::new, local1);
            router.create(serverMember2, context.getId(), new ServiceImpl(local2, "B"), "B", ServerImpl::new,
                          TestItClient::new, local2);

            var comms = router.create(serverMember1, context.getId(), new ServiceImpl(local1, "Test"), "Test",
                                      ServerImpl::new, TestItClient::new, local1);

            router.start();

            var slice = new SliceIterator<TestItService>("quorumTest", serverMember1,
                                                         Arrays.asList(serverMember1, serverMember2), comms);

            var future = slice.collectAsync(link -> {
                var result = link.ping(Any.getDefaultInstance());
                return result.getTypeUrl();
            }, response -> response != null && response.contains("StringValue"), // Only "valid" responses pass
                                                                               3, // require 3 - impossible with 2
                                                                               // members
                                                                               Duration.ofMillis(10),
                                                                               Duration.ofSeconds(5));

            var exception = assertThrows(java.util.concurrent.ExecutionException.class,
                                         () -> future.get(10, TimeUnit.SECONDS));
            assertInstanceOf(QuorumException.class, exception.getCause());

            var quorumEx = (QuorumException) exception.getCause();
            assertEquals(3, quorumEx.getRequired());
            assertTrue(quorumEx.getAchieved() < 3);
        } finally {
            router.close(Duration.ofSeconds(0));
        }
    }

    @Test
    public void collectAsyncTimeout() throws Exception {
        var serverMember1 = new SigningMemberImpl(Utils.getMember(0), ULong.MIN);
        var serverMember2 = new SigningMemberImpl(Utils.getMember(1), ULong.MIN);

        // Members that delay their responses
        var local1 = new TestItService() {
            @Override
            public void close() throws IOException {
            }

            @Override
            public Member getMember() {
                return serverMember1;
            }

            @Override
            public Any ping(Any request) {
                try {
                    Thread.sleep(500); // Delay response
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Any.pack(com.google.protobuf.StringValue.of("delayed"));
            }
        };
        var local2 = new TestItService() {
            @Override
            public void close() throws IOException {
            }

            @Override
            public Member getMember() {
                return serverMember2;
            }

            @Override
            public Any ping(Any request) {
                try {
                    Thread.sleep(500); // Delay response
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Any.pack(com.google.protobuf.StringValue.of("delayed"));
            }
        };

        final var name = UUID.randomUUID().toString();
        DynamicContext<Member> context = DynamicContext.newBuilder().build();
        context.activate(serverMember1);
        context.activate(serverMember2);

        var serverBuilder = InProcessServerBuilder.forName(name);
        var cacheBuilder = ServerConnectionCache.newBuilder()
                                                .setFactory(to -> InProcessChannelBuilder.forName(name).build());
        Router router = new RouterImpl(serverMember1, serverBuilder, cacheBuilder, null);
        try {
            router.create(serverMember1, context.getId(), new ServiceImpl(local1, "A"), "A", ServerImpl::new,
                          TestItClient::new, local1);
            router.create(serverMember2, context.getId(), new ServiceImpl(local2, "B"), "B", ServerImpl::new,
                          TestItClient::new, local2);

            var comms = router.create(serverMember1, context.getId(), new ServiceImpl(local1, "Test"), "Test",
                                      ServerImpl::new, TestItClient::new, local1);

            router.start();

            var slice = new SliceIterator<TestItService>("timeoutTest", serverMember1,
                                                         Arrays.asList(serverMember1, serverMember2), comms);

            var future = slice.collectAsync(link -> {
                var result = link.ping(Any.getDefaultInstance());
                return result.getTypeUrl();
            }, response -> response != null && response.contains("StringValue"), 2, Duration.ofMillis(10),
                                                                               Duration.ofMillis(100)); // Very short
            // timeout

            var exception = assertThrows(java.util.concurrent.ExecutionException.class,
                                         () -> future.get(10, TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class, exception.getCause());
        } finally {
            router.close(Duration.ofSeconds(0));
        }
    }

    @Test
    public void collectAsyncInvalidParameters() throws Exception {
        var serverMember1 = new SigningMemberImpl(Utils.getMember(0), ULong.MIN);
        var local1 = createTestService(serverMember1, "test");

        final var name = UUID.randomUUID().toString();
        DynamicContext<Member> context = DynamicContext.newBuilder().build();
        context.activate(serverMember1);

        var serverBuilder = InProcessServerBuilder.forName(name);
        var cacheBuilder = ServerConnectionCache.newBuilder()
                                                .setFactory(to -> InProcessChannelBuilder.forName(name).build());
        Router router = new RouterImpl(serverMember1, serverBuilder, cacheBuilder, null);
        try {
            var comms = router.create(serverMember1, context.getId(), new ServiceImpl(local1, "Test"), "Test",
                                      ServerImpl::new, TestItClient::new, local1);

            var slice = new SliceIterator<TestItService>("paramTest", serverMember1,
                                                         Arrays.asList(serverMember1), comms);

            // Test zero requiredCount
            assertThrows(IllegalArgumentException.class, () -> slice.collectAsync(link -> "test", s -> true, 0,
                                                                                   Duration.ofMillis(10),
                                                                                   Duration.ofSeconds(1)));

            // Test negative requiredCount
            assertThrows(IllegalArgumentException.class, () -> slice.collectAsync(link -> "test", s -> true, -1,
                                                                                   Duration.ofMillis(10),
                                                                                   Duration.ofSeconds(1)));

            // Test zero timeout
            assertThrows(IllegalArgumentException.class, () -> slice.collectAsync(link -> "test", s -> true, 1,
                                                                                   Duration.ofMillis(10),
                                                                                   Duration.ZERO));

            // Test negative timeout
            assertThrows(IllegalArgumentException.class, () -> slice.collectAsync(link -> "test", s -> true, 1,
                                                                                   Duration.ofMillis(10),
                                                                                   Duration.ofMillis(-100)));
        } finally {
            router.close(Duration.ofSeconds(0));
        }
    }

    @Test
    public void voteAsyncInvalidParameters() throws Exception {
        var serverMember1 = new SigningMemberImpl(Utils.getMember(0), ULong.MIN);
        var local1 = createTestService(serverMember1, "test");

        final var name = UUID.randomUUID().toString();
        DynamicContext<Member> context = DynamicContext.newBuilder().build();
        context.activate(serverMember1);

        var serverBuilder = InProcessServerBuilder.forName(name);
        var cacheBuilder = ServerConnectionCache.newBuilder()
                                                .setFactory(to -> InProcessChannelBuilder.forName(name).build());
        Router router = new RouterImpl(serverMember1, serverBuilder, cacheBuilder, null);
        try {
            var comms = router.create(serverMember1, context.getId(), new ServiceImpl(local1, "Test"), "Test",
                                      ServerImpl::new, TestItClient::new, local1);

            var slice = new SliceIterator<TestItService>("paramTest", serverMember1,
                                                         Arrays.asList(serverMember1), comms);

            // Test zero requiredCount
            assertThrows(IllegalArgumentException.class,
                         () -> slice.voteAsync(link -> "test", 0, Duration.ofMillis(10), Duration.ofSeconds(1)));

            // Test negative requiredCount
            assertThrows(IllegalArgumentException.class,
                         () -> slice.voteAsync(link -> "test", -1, Duration.ofMillis(10), Duration.ofSeconds(1)));

            // Test zero timeout
            assertThrows(IllegalArgumentException.class,
                         () -> slice.voteAsync(link -> "test", 1, Duration.ofMillis(10), Duration.ZERO));

            // Test negative timeout
            assertThrows(IllegalArgumentException.class,
                         () -> slice.voteAsync(link -> "test", 1, Duration.ofMillis(10), Duration.ofMillis(-100)));
        } finally {
            router.close(Duration.ofSeconds(0));
        }
    }

    private TestItService createTestService(Member member, String responseValue) {
        return new TestItService() {
            @Override
            public void close() throws IOException {
            }

            @Override
            public Member getMember() {
                return member;
            }

            @Override
            public Any ping(Any request) {
                return Any.pack(com.google.protobuf.StringValue.of(responseValue));
            }
        };
    }

    @Test
    public void voteAsyncPluralityWins() throws Exception {
        var serverMember1 = new SigningMemberImpl(Utils.getMember(0), ULong.MIN);
        var serverMember2 = new SigningMemberImpl(Utils.getMember(1), ULong.MIN);

        var pinged1 = new AtomicBoolean();
        var pinged2 = new AtomicBoolean();

        var local1 = new TestItService() {
            @Override
            public void close() throws IOException {
            }

            @Override
            public Member getMember() {
                return serverMember1;
            }

            @Override
            public Any ping(Any request) {
                pinged1.set(true);
                return Any.pack(com.google.protobuf.StringValue.of("A"));
            }
        };
        var local2 = new TestItService() {
            @Override
            public void close() throws IOException {
            }

            @Override
            public Member getMember() {
                return serverMember2;
            }

            @Override
            public Any ping(Any request) {
                pinged2.set(true);
                return Any.pack(com.google.protobuf.StringValue.of("A"));
            }
        };

        final var name = UUID.randomUUID().toString();
        DynamicContext<Member> context = DynamicContext.newBuilder().build();
        context.activate(serverMember1);
        context.activate(serverMember2);

        var serverBuilder = InProcessServerBuilder.forName(name);
        var cacheBuilder = ServerConnectionCache.newBuilder()
                                                .setFactory(to -> InProcessChannelBuilder.forName(name).build());
        Router router = new RouterImpl(serverMember1, serverBuilder, cacheBuilder, null);
        try {
            router.create(serverMember1, context.getId(), new ServiceImpl(local1, "A"), "A", ServerImpl::new,
                          TestItClient::new, local1);
            router.create(serverMember2, context.getId(), new ServiceImpl(local2, "B"), "B", ServerImpl::new,
                          TestItClient::new, local2);

            var comms = router.create(serverMember1, context.getId(), new ServiceImpl(local1, "Test"), "Test",
                                      ServerImpl::new, TestItClient::new, local1);

            router.start();

            var slice = new SliceIterator<TestItService>("voteTest", serverMember1,
                                                         Arrays.asList(serverMember1, serverMember2), comms);

            var future = slice.voteAsync(link -> {
                var result = link.ping(Any.getDefaultInstance());
                try {
                    return result.unpack(com.google.protobuf.StringValue.class).getValue();
                } catch (com.google.protobuf.InvalidProtocolBufferException e) {
                    return null;
                }
            }, 1, Duration.ofMillis(10), Duration.ofSeconds(5)); // Require just 1 vote for basic test

            var winner = future.get(10, TimeUnit.SECONDS);
            assertNotNull(winner);
            assertEquals("A", winner);
            assertTrue(pinged1.get() || pinged2.get(), "At least one member should have been pinged");
        } finally {
            router.close(Duration.ofSeconds(0));
        }
    }

    @Test
    public void voteAsyncQuorumNotReached() throws Exception {
        var serverMember1 = new SigningMemberImpl(Utils.getMember(0), ULong.MIN);

        // Only one member available
        var local1 = createTestService(serverMember1, "vote");

        final var name = UUID.randomUUID().toString();
        DynamicContext<Member> context = DynamicContext.newBuilder().build();
        context.activate(serverMember1);

        var serverBuilder = InProcessServerBuilder.forName(name);
        var cacheBuilder = ServerConnectionCache.newBuilder()
                                                .setFactory(to -> InProcessChannelBuilder.forName(name).build());
        Router router = new RouterImpl(serverMember1, serverBuilder, cacheBuilder, null);
        try {
            var comms = router.create(serverMember1, context.getId(), new ServiceImpl(local1, "Test"), "Test",
                                      ServerImpl::new, TestItClient::new, local1);

            router.start();

            var slice = new SliceIterator<TestItService>("voteQuorumTest", serverMember1,
                                                         Arrays.asList(serverMember1), comms);

            var future = slice.voteAsync(link -> {
                var result = link.ping(Any.getDefaultInstance());
                return result.getTypeUrl();
            }, 5, // require 5 votes - impossible with 1 member
                                         Duration.ofMillis(10), Duration.ofSeconds(2));

            // Should fail with QuorumException since we can't reach required count
            var exception = assertThrows(java.util.concurrent.ExecutionException.class,
                                         () -> future.get(10, TimeUnit.SECONDS));
            assertInstanceOf(QuorumException.class, exception.getCause());

            var quorumEx = (QuorumException) exception.getCause();
            assertEquals(5, quorumEx.getRequired());
            assertTrue(quorumEx.getAchieved() < 5);
        } finally {
            router.close(Duration.ofSeconds(0));
        }
    }

    @Test
    public void voteAsyncTimeout() throws Exception {
        var serverMember1 = new SigningMemberImpl(Utils.getMember(0), ULong.MIN);
        var serverMember2 = new SigningMemberImpl(Utils.getMember(1), ULong.MIN);

        // Members that delay their responses
        var local1 = new TestItService() {
            @Override
            public void close() throws IOException {
            }

            @Override
            public Member getMember() {
                return serverMember1;
            }

            @Override
            public Any ping(Any request) {
                try {
                    Thread.sleep(500); // Delay response
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Any.pack(com.google.protobuf.StringValue.of("delayed"));
            }
        };
        var local2 = new TestItService() {
            @Override
            public void close() throws IOException {
            }

            @Override
            public Member getMember() {
                return serverMember2;
            }

            @Override
            public Any ping(Any request) {
                try {
                    Thread.sleep(500); // Delay response
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Any.pack(com.google.protobuf.StringValue.of("delayed"));
            }
        };

        final var name = UUID.randomUUID().toString();
        DynamicContext<Member> context = DynamicContext.newBuilder().build();
        context.activate(serverMember1);
        context.activate(serverMember2);

        var serverBuilder = InProcessServerBuilder.forName(name);
        var cacheBuilder = ServerConnectionCache.newBuilder()
                                                .setFactory(to -> InProcessChannelBuilder.forName(name).build());
        Router router = new RouterImpl(serverMember1, serverBuilder, cacheBuilder, null);
        try {
            router.create(serverMember1, context.getId(), new ServiceImpl(local1, "A"), "A", ServerImpl::new,
                          TestItClient::new, local1);
            router.create(serverMember2, context.getId(), new ServiceImpl(local2, "B"), "B", ServerImpl::new,
                          TestItClient::new, local2);

            var comms = router.create(serverMember1, context.getId(), new ServiceImpl(local1, "Test"), "Test",
                                      ServerImpl::new, TestItClient::new, local1);

            router.start();

            var slice = new SliceIterator<TestItService>("voteTimeoutTest", serverMember1,
                                                         Arrays.asList(serverMember1, serverMember2), comms);

            var future = slice.voteAsync(link -> {
                var result = link.ping(Any.getDefaultInstance());
                return result.getTypeUrl();
            }, 2, Duration.ofMillis(10), Duration.ofMillis(100)); // Very short timeout

            var exception = assertThrows(java.util.concurrent.ExecutionException.class,
                                         () -> future.get(10, TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class, exception.getCause());
        } finally {
            router.close(Duration.ofSeconds(0));
        }
    }
}
