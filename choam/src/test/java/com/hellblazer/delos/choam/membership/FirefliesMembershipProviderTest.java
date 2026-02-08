/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.membership;

import com.hellblazer.delos.context.DelegatedContext;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.messaging.beg.BoundedEpidemicGossip;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Unit tests for FirefliesMembershipProvider adapter.
 * Validates lifecycle management, delegation, and membership queries.
 *
 * @author hal.hildebrand
 */
class FirefliesMembershipProviderTest {

    @Mock
    private BoundedEpidemicGossip gossip;

    @Mock
    private DelegatedContext<Member> context;

    @Mock
    private Member member1;

    @Mock
    private Member member2;

    private Duration gossipDuration;
    private FirefliesMembershipProvider provider;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        gossipDuration = Duration.ofSeconds(1);
        provider = new FirefliesMembershipProvider(gossip, context, gossipDuration);
    }

    @Test
    void testStart() {
        provider.start();

        verify(gossip).start(gossipDuration);
    }

    @Test
    void testStartIdempotent() {
        provider.start();
        provider.start(); // Second call should be no-op

        // Verify start called exactly once
        verify(gossip, times(1)).start(gossipDuration);
    }

    @Test
    void testStop() {
        provider.start();  // Must start before stopping
        provider.stop();

        verify(gossip).stop();
    }

    @Test
    void testStopIdempotent() {
        provider.start();  // Must start before stopping
        provider.stop();
        provider.stop(); // Second call should be no-op

        // Verify stop called exactly once
        verify(gossip, times(1)).stop();
    }

    @Test
    void testGetActiveMembership() {
        when(context.allMembers()).thenReturn(Stream.of(member1, member2));

        var members = provider.getActiveMembership();

        assertThat(members).containsExactlyInAnyOrder(member1, member2);
        verify(context).allMembers();
    }

    @Test
    void testGetActiveMembershipEmpty() {
        when(context.allMembers()).thenReturn(Stream.empty());

        var members = provider.getActiveMembership();

        assertThat(members).isEmpty();
        verify(context).allMembers();
    }

    @Test
    void testGetContext() {
        var returnedContext = provider.getContext();

        assertThat(returnedContext).isSameAs(context);
    }

    @Test
    void testRegisterHandlerThrowsUnsupported() {
        // This is a known limitation - registerHandler signature mismatch
        // to be resolved in Phase C integration
        assertThatThrownBy(() -> provider.registerHandler((type, bindings) -> {}))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("Phase C");
    }

    @Test
    void testRegisterTickListener() {
        Consumer<Integer> tickListener = tick -> {};

        provider.registerTickListener(tickListener);

        verify(gossip).register(tickListener);
    }

    @Test
    void testThreadSafety() throws InterruptedException {
        var startLatch = new CountDownLatch(2);

        // Simulate concurrent start calls from multiple threads
        var thread1 = new Thread(() -> {
            provider.start();
            startLatch.countDown();
        });
        var thread2 = new Thread(() -> {
            provider.start();
            startLatch.countDown();
        });

        thread1.start();
        thread2.start();

        // Wait for both threads to complete (with timeout)
        assertThat(startLatch.await(1, TimeUnit.SECONDS)).isTrue();

        // Despite concurrent calls, start should only be called once
        verify(gossip, times(1)).start(gossipDuration);
    }

    @Test
    void testConcurrentStartStop() throws InterruptedException {
        provider.start();

        var stopThread = new Thread(() -> provider.stop());
        stopThread.start();
        stopThread.join();

        verify(gossip, times(1)).start(gossipDuration);
        verify(gossip, times(1)).stop();
    }

    @Test
    void testNullGossip() {
        assertThatThrownBy(() -> new FirefliesMembershipProvider(null, context, gossipDuration))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("gossip cannot be null");
    }

    @Test
    void testNullContext() {
        assertThatThrownBy(() -> new FirefliesMembershipProvider(gossip, null, gossipDuration))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("context cannot be null");
    }

    @Test
    void testNullGossipDuration() {
        assertThatThrownBy(() -> new FirefliesMembershipProvider(gossip, context, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("gossipDuration cannot be null");
    }

    @Test
    void testRestartAfterStop() {
        // Start then stop
        provider.start();
        provider.stop();

        // Try to start again - should be no-op (can't restart)
        provider.start();

        // Verify start and stop called exactly once
        verify(gossip, times(1)).start(gossipDuration);
        verify(gossip, times(1)).stop();
    }

    @Test
    void testExceptionDuringStart() {
        // Simulate gossip.start() throwing exception
        doThrow(new RuntimeException("Gossip start failed")).when(gossip).start(gossipDuration);

        assertThatThrownBy(() -> provider.start())
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Gossip start failed");

        // Provider should be back in INITIAL state after exception
        verify(gossip, times(1)).start(gossipDuration);
    }
}
