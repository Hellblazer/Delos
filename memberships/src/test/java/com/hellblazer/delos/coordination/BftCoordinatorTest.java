/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.coordination;

import com.hellblazer.delos.archipelago.Link;
import com.hellblazer.delos.archipelago.RouterImpl.CommonCommunications;
import com.hellblazer.delos.context.Context;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.SigningMember;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * TDD tests for BftCoordinator - write tests first, implement to pass.
 *
 * @author hal.hildebrand
 */
class BftCoordinatorTest {

    private static final Duration FREQUENCY = Duration.ofMillis(10);
    private static final Duration TIMEOUT   = Duration.ofMillis(500);

    @Mock
    private Context<Member>                  context;
    @Mock
    private SigningMember                    member;
    @Mock
    private CommonCommunications<TestLink, ?> comm;

    private ScheduledExecutorService         scheduler;
    private BftCoordinator<TestLink>         coordinator;
    private AutoCloseable                    mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        scheduler = Executors.newScheduledThreadPool(2, Thread.ofVirtual().factory());
        coordinator = new BftCoordinator<>(context, member, comm, scheduler);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
            scheduler.awaitTermination(1, TimeUnit.SECONDS);
        }
        if (mocks != null) {
            mocks.close();
        }
    }

    @Test
    void testCollectQuorum_MajorityReached_Success() throws Exception {
        // Setup: 4 node BFT subset, majority = 3
        var identifier = DigestAlgorithm.DEFAULT.digest("test".getBytes());
        var bftSubset = createMockMembers(4);
        when(context.bftSubset(identifier)).thenReturn(bftSubset);
        when(context.majority()).thenReturn(3);

        // Mock successful responses from 3 out of 4 members
        var mockResponses = List.of("response1", "response2", "response3");
        var responseIndex = new AtomicInteger(0);

        when(comm.connect(any(Member.class))).thenAnswer(invocation -> {
            var testLink = mock(TestLink.class);
            when(testLink.getMember()).thenReturn(invocation.getArgument(0));
            return testLink;
        });

        Function<TestLink, String> requestFn = link -> {
            var idx = responseIndex.getAndIncrement();
            if (idx < 3) {
                return mockResponses.get(idx);
            }
            return null; // 4th member fails
        };

        // Execute
        CompletableFuture<QuorumResult<Set<String>>> result = coordinator.collectQuorum(
            identifier,
            "Test Quorum",
            requestFn,
            FREQUENCY,
            TIMEOUT
        );

        // Verify
        var quorumResult = result.get(1, TimeUnit.SECONDS);
        assertThat(quorumResult).isNotNull();
        assertThat(quorumResult.result()).containsExactlyInAnyOrder("response1", "response2", "response3");
        assertThat(quorumResult.responseCount()).isEqualTo(3);
        assertThat(quorumResult.requiredMajority()).isEqualTo(3);
    }

    @Test
    void testCollectQuorum_MajorityNotReached_AbortedException() {
        // Setup: 4 node BFT subset, majority = 3
        var identifier = DigestAlgorithm.DEFAULT.digest("test".getBytes());
        var bftSubset = createMockMembers(4);
        when(context.bftSubset(identifier)).thenReturn(bftSubset);
        when(context.majority()).thenReturn(3);

        // Mock only 2 successful responses (not enough for majority)
        var responseIndex = new AtomicInteger(0);

        when(comm.connect(any(Member.class))).thenAnswer(invocation -> {
            var testLink = mock(TestLink.class);
            when(testLink.getMember()).thenReturn(invocation.getArgument(0));
            return testLink;
        });

        Function<TestLink, String> requestFn = link -> {
            var idx = responseIndex.getAndIncrement();
            if (idx < 2) {
                return "response" + idx;
            }
            return null; // Last 2 members fail
        };

        // Execute
        CompletableFuture<QuorumResult<Set<String>>> result = coordinator.collectQuorum(
            identifier,
            "Test Quorum",
            requestFn,
            FREQUENCY,
            null // no overall timeout
        );

        // Verify - should complete exceptionally with ABORTED status
        assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class)
            .hasCauseInstanceOf(StatusRuntimeException.class)
            .satisfies(e -> {
                var sre = (StatusRuntimeException) e.getCause();
                assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.ABORTED);
                assertThat(sre.getStatus().getDescription()).contains("majority");
            });
    }

    @Test
    void testCollectQuorum_TimeoutExceeded_TimeoutException() {
        // Setup: 4 node BFT subset with slow responses
        var identifier = DigestAlgorithm.DEFAULT.digest("test".getBytes());
        var bftSubset = createMockMembers(4);
        when(context.bftSubset(identifier)).thenReturn(bftSubset);
        when(context.majority()).thenReturn(3);

        when(comm.connect(any(Member.class))).thenAnswer(invocation -> {
            var testLink = mock(TestLink.class);
            when(testLink.getMember()).thenReturn(invocation.getArgument(0));
            return testLink;
        });

        // Request function that delays to trigger timeout
        Function<TestLink, String> requestFn = link -> {
            try {
                Thread.sleep(200); // Slow response
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "slow-response";
        };

        // Execute with very short timeout
        CompletableFuture<QuorumResult<Set<String>>> result = coordinator.collectQuorum(
            identifier,
            "Test Quorum",
            requestFn,
            FREQUENCY,
            Duration.ofMillis(50) // Very short timeout
        );

        // Verify - should complete exceptionally with TimeoutException
        assertThatThrownBy(() -> result.get(1, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class)
            .hasCauseInstanceOf(TimeoutException.class);
    }

    @Test
    void testCollectQuorum_EmptySlice_ImmediateFailure() {
        // Setup: Empty BFT subset
        var identifier = DigestAlgorithm.DEFAULT.digest("test".getBytes());
        when(context.bftSubset(identifier)).thenReturn(new LinkedHashSet<>());
        when(context.majority()).thenReturn(0);

        Function<TestLink, String> requestFn = link -> "should-not-be-called";

        // Execute
        CompletableFuture<QuorumResult<Set<String>>> result = coordinator.collectQuorum(
            identifier,
            "Test Quorum",
            requestFn,
            FREQUENCY,
            TIMEOUT
        );

        // Verify - should fail immediately
        assertThatThrownBy(() -> result.get(100, TimeUnit.MILLISECONDS))
            .isInstanceOf(ExecutionException.class)
            .hasCauseInstanceOf(StatusRuntimeException.class)
            .satisfies(e -> {
                var sre = (StatusRuntimeException) e.getCause();
                assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.ABORTED);
            });
    }

    // Helper methods

    private SequencedSet<Member> createMockMembers(int count) {
        var members = new LinkedHashSet<Member>();
        for (int i = 0; i < count; i++) {
            var m = mock(Member.class);
            var id = DigestAlgorithm.DEFAULT.digest(("member" + i).getBytes());
            when(m.getId()).thenReturn(id);
            members.add(m);
        }
        return members;
    }

    // Test Link interface for mocking
    interface TestLink extends Link {
        // Marker interface for testing
    }
}
