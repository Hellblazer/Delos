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
        provider.stop();

        verify(gossip).stop();
    }

    @Test
    void testStopIdempotent() {
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
        // Simulate concurrent start/stop calls from multiple threads
        var thread1 = new Thread(() -> provider.start());
        var thread2 = new Thread(() -> provider.start());
        var thread3 = new Thread(() -> provider.stop());

        thread1.start();
        thread2.start();
        thread1.join();
        thread2.join();

        // Give time for start to complete
        Thread.sleep(10);

        thread3.start();
        thread3.join();

        // Despite concurrent calls, start/stop should be called at most once each
        verify(gossip, atMostOnce()).start(gossipDuration);
        verify(gossip, atMostOnce()).stop();
    }
}
