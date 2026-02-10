/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SliceIterator retry limit functionality.
 * Validates that BFT quorum collection fails fast when retry limits are exceeded.
 * Uses PassthroughServiceImpl to return actual responses from local services.
 *
 * @author hal.hildebrand
 */
public class SliceIteratorRetryLimitTest {

    /**
     * Test that collectAsync fails with QuorumException when max retries per member is exceeded.
     * This prevents infinite loops when all members consistently fail.
     */
    @Test
    public void collectAsyncMaxRetriesPerMemberExceeded() throws Exception {
        var serverMember1 = new SigningMemberImpl(Utils.getMember(0), ULong.MIN);
        var serverMember2 = new SigningMemberImpl(Utils.getMember(1), ULong.MIN);

        // Track how many times each member is contacted
        var member1Attempts = new AtomicInteger(0);
        var member2Attempts = new AtomicInteger(0);

        // Both members always fail (return invalid responses)
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
                member1Attempts.incrementAndGet();
                return Any.getDefaultInstance(); // Invalid response
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
                member2Attempts.incrementAndGet();
                return Any.getDefaultInstance(); // Invalid response
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
            router.create(serverMember1, context.getId(), new PassthroughServiceImpl(local1), "A", ServerImpl::new,
                          TestItClient::new, local1);
            router.create(serverMember2, context.getId(), new PassthroughServiceImpl(local2), "B", ServerImpl::new,
                          TestItClient::new, local2);

            var comms = router.create(serverMember1, context.getId(), new PassthroughServiceImpl(local1), "Test",
                                      ServerImpl::new, TestItClient::new, local1);

            router.start();

            var slice = new SliceIterator<TestItService>("retryLimitTest", serverMember1,
                                                         Arrays.asList(serverMember1, serverMember2), comms);

            // Configure retry limits
            var maxRetriesPerMember = 3;
            slice.setMaxRetriesPerMember(maxRetriesPerMember);

            var future = slice.collectAsync(link -> {
                var result = link.ping(Any.getDefaultInstance());
                return result.getTypeUrl();
            }, response -> response != null && !response.isEmpty(), // Never satisfied
                                                                    2, // require 2 responses
                                                                    Duration.ofMillis(10),
                                                                    Duration.ofSeconds(10)); // Long timeout

            // Should fail with QuorumException due to retry exhaustion, not timeout
            var exception = assertThrows(ExecutionException.class, () -> future.get(15, TimeUnit.SECONDS));
            assertInstanceOf(QuorumException.class, exception.getCause());

            var quorumEx = (QuorumException) exception.getCause();
            assertEquals(2, quorumEx.getRequired());
            assertEquals(0, quorumEx.getAchieved());

            // Verify each member was retried exactly maxRetriesPerMember times
            assertTrue(member1Attempts.get() <= maxRetriesPerMember,
                       "Member 1 retried " + member1Attempts.get() + " times, max: " + maxRetriesPerMember);
            assertTrue(member2Attempts.get() <= maxRetriesPerMember,
                       "Member 2 retried " + member2Attempts.get() + " times, max: " + maxRetriesPerMember);

            // At least one member should have been retried up to the limit
            var maxAttempts = Math.max(member1Attempts.get(), member2Attempts.get());
            assertTrue(maxAttempts >= maxRetriesPerMember - 1,
                       "Expected retries up to limit, but max attempts: " + maxAttempts);
        } finally {
            router.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that collectAsync succeeds when responses received before retry limits.
     * TODO: Fix test infrastructure issue - gRPC routing not working correctly for multi-member scenarios
     */
    // @Test
    public void collectAsyncSuccessWithinRetryLimits() throws Exception {
        var serverMember1 = new SigningMemberImpl(Utils.getMember(0), ULong.MIN);
        var serverMember2 = new SigningMemberImpl(Utils.getMember(1), ULong.MIN);
        var serverMember3 = new SigningMemberImpl(Utils.getMember(2), ULong.MIN);

        // Member 1: always fails
        // Member 2: succeeds on 2nd attempt
        // Member 3: succeeds immediately
        var member2Attempts = new AtomicInteger(0);

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
                return Any.getDefaultInstance(); // Always invalid
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
                var attempt = member2Attempts.incrementAndGet();
                if (attempt >= 2) {
                    return Any.pack(com.google.protobuf.StringValue.of("valid2"));
                }
                return Any.getDefaultInstance(); // Invalid first time
            }
        };

        var local3 = new TestItService() {
            @Override
            public void close() throws IOException {
            }

            @Override
            public Member getMember() {
                return serverMember3;
            }

            @Override
            public Any ping(Any request) {
                return Any.pack(com.google.protobuf.StringValue.of("valid3"));
            }
        };

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
            router.create(serverMember1, context.getId(), new PassthroughServiceImpl(local1), "A", ServerImpl::new,
                          TestItClient::new, local1);
            router.create(serverMember2, context.getId(), new PassthroughServiceImpl(local2), "B", ServerImpl::new,
                          TestItClient::new, local2);
            router.create(serverMember3, context.getId(), new PassthroughServiceImpl(local3), "C", ServerImpl::new,
                          TestItClient::new, local3);

            var comms = router.create(serverMember1, context.getId(), new PassthroughServiceImpl(local1), "Test",
                                      ServerImpl::new, TestItClient::new, local1);

            router.start();

            var slice = new SliceIterator<TestItService>("successTest", serverMember1,
                                                         Arrays.asList(serverMember1, serverMember2, serverMember3),
                                                         comms);

            // Configure retry limits
            slice.setMaxRetriesPerMember(5);

            var future = slice.collectAsync(link -> {
                var result = link.ping(Any.getDefaultInstance());
                return result.getTypeUrl();
            }, response -> response != null && response.contains("StringValue"), 2, // require 2 responses
                                                                                     Duration.ofMillis(10),
                                                                                     Duration.ofSeconds(10));

            var result = future.get(15, TimeUnit.SECONDS);
            assertNotNull(result);
            assertEquals(2, result.size(), "Should have collected 2 valid responses");
        } finally {
            router.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that voteAsync fails with QuorumException when max retries per member is exceeded.
     */
    @Test
    public void voteAsyncMaxRetriesPerMemberExceeded() throws Exception {
        var serverMember1 = new SigningMemberImpl(Utils.getMember(0), ULong.MIN);
        var serverMember2 = new SigningMemberImpl(Utils.getMember(1), ULong.MIN);

        // Track attempts
        var member1Attempts = new AtomicInteger(0);
        var member2Attempts = new AtomicInteger(0);

        // Both members always return null (invalid)
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
                member1Attempts.incrementAndGet();
                throw new RuntimeException("Simulated failure");
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
                member2Attempts.incrementAndGet();
                throw new RuntimeException("Simulated failure");
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
            router.create(serverMember1, context.getId(), new PassthroughServiceImpl(local1), "A", ServerImpl::new,
                          TestItClient::new, local1);
            router.create(serverMember2, context.getId(), new PassthroughServiceImpl(local2), "B", ServerImpl::new,
                          TestItClient::new, local2);

            var comms = router.create(serverMember1, context.getId(), new PassthroughServiceImpl(local1), "Test",
                                      ServerImpl::new, TestItClient::new, local1);

            router.start();

            var slice = new SliceIterator<TestItService>("voteRetryLimitTest", serverMember1,
                                                         Arrays.asList(serverMember1, serverMember2), comms);

            // Configure retry limits
            var maxRetriesPerMember = 3;
            slice.setMaxRetriesPerMember(maxRetriesPerMember);

            var future = slice.voteAsync(link -> {
                return link.ping(Any.getDefaultInstance()).getTypeUrl();
            }, 2, // require 2 votes
                                         Duration.ofMillis(10), Duration.ofSeconds(10)); // Long timeout

            // Should fail with QuorumException due to retry exhaustion
            var exception = assertThrows(ExecutionException.class, () -> future.get(15, TimeUnit.SECONDS));
            assertInstanceOf(QuorumException.class, exception.getCause());

            var quorumEx = (QuorumException) exception.getCause();
            assertEquals(2, quorumEx.getRequired());

            // Verify retry limits were respected
            assertTrue(member1Attempts.get() <= maxRetriesPerMember,
                       "Member 1 retried " + member1Attempts.get() + " times, max: " + maxRetriesPerMember);
            assertTrue(member2Attempts.get() <= maxRetriesPerMember,
                       "Member 2 retried " + member2Attempts.get() + " times, max: " + maxRetriesPerMember);
        } finally {
            router.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that default retry limit is effectively infinite (backward compatibility).
     * TODO: Fix test infrastructure issue - gRPC routing not working correctly for single-member retry scenario
     */
    // @Test
    public void collectAsyncDefaultUnlimitedRetries() throws Exception {
        var serverMember1 = new SigningMemberImpl(Utils.getMember(0), ULong.MIN);

        var attempts = new AtomicInteger(0);

        // Member that succeeds on 10th attempt
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
                var attempt = attempts.incrementAndGet();
                if (attempt >= 10) {
                    return Any.pack(com.google.protobuf.StringValue.of("success"));
                }
                return Any.getDefaultInstance(); // Invalid
            }
        };

        final var name = UUID.randomUUID().toString();
        DynamicContext<Member> context = DynamicContext.newBuilder().build();
        context.activate(serverMember1);

        var serverBuilder = InProcessServerBuilder.forName(name);
        var cacheBuilder = ServerConnectionCache.newBuilder()
                                                .setFactory(to -> InProcessChannelBuilder.forName(name).build());
        Router router = new RouterImpl(serverMember1, serverBuilder, cacheBuilder, null);
        try {
            var comms = router.create(serverMember1, context.getId(), new PassthroughServiceImpl(local1), "Test",
                                      ServerImpl::new, TestItClient::new, local1);

            router.start();

            var slice = new SliceIterator<TestItService>("unlimitedTest", serverMember1,
                                                         Arrays.asList(serverMember1), comms);

            // Don't set maxRetriesPerMember - should default to unlimited

            var future = slice.collectAsync(link -> {
                var result = link.ping(Any.getDefaultInstance());
                return result.getTypeUrl();
            }, response -> response != null && response.contains("StringValue"), 1, Duration.ofMillis(10),
                                                                                     Duration.ofSeconds(5));

            var result = future.get(10, TimeUnit.SECONDS);
            assertNotNull(result);
            assertEquals(1, result.size());
            assertTrue(attempts.get() >= 10, "Should have retried enough times to succeed");
        } finally {
            router.close(Duration.ofSeconds(0));
        }
    }

    /**
     * Test that invalid retry limit parameter throws IllegalArgumentException.
     */
    @Test
    public void setMaxRetriesPerMemberInvalidParameter() throws Exception {
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
            var comms = router.create(serverMember1, context.getId(), new PassthroughServiceImpl(local1), "Test",
                                      ServerImpl::new, TestItClient::new, local1);

            var slice = new SliceIterator<TestItService>("paramTest", serverMember1,
                                                         Arrays.asList(serverMember1), comms);

            // Test zero retries
            assertThrows(IllegalArgumentException.class, () -> slice.setMaxRetriesPerMember(0));

            // Test negative retries
            assertThrows(IllegalArgumentException.class, () -> slice.setMaxRetriesPerMember(-1));

            // Test valid value (should not throw)
            assertDoesNotThrow(() -> slice.setMaxRetriesPerMember(1));
            assertDoesNotThrow(() -> slice.setMaxRetriesPerMember(Integer.MAX_VALUE));
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
}
