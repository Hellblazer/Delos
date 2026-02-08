/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.proto.Join;
import com.hellblazer.delos.context.DelegatedContext;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.event.proto.Binding;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for MockMembershipProvider to ensure it satisfies the MembershipProvider interface contract.
 *
 * @author hal.hildebrand
 */
@DisplayName("MockMembershipProvider Contract Tests")
class MockMembershipProviderContractTest {
    private MockMembershipProvider provider;
    private Member                 member1;
    private Member                 member2;
    private Member                 member3;

    @BeforeEach
    void setUp() {
        provider = new MockMembershipProvider();

        // Create test members
        var stereotomy = new StereotomyImpl(new MemKeyStore(), new MemKERL(DigestAlgorithm.DEFAULT), new SecureRandom());

        member1 = new ControlledIdentifierMember(stereotomy.newIdentifier());
        member2 = new ControlledIdentifierMember(stereotomy.newIdentifier());
        member3 = new ControlledIdentifierMember(stereotomy.newIdentifier());
    }

    @Test
    @DisplayName("initial state is stopped with empty membership")
    void initialState_IsStoppedWithEmptyMembership() {
        assertThat(provider.isStarted()).isFalse();
        assertThat(provider.getActiveMembership()).isEmpty();
    }

    @Test
    @DisplayName("start transitions to started state")
    void start_TransitionsToStarted() {
        provider.start();

        assertThat(provider.isStarted()).isTrue();
    }

    @Test
    @DisplayName("stop transitions to stopped state")
    void stop_TransitionsToStopped() {
        provider.start();
        provider.stop();

        assertThat(provider.isStarted()).isFalse();
    }

    @Test
    @DisplayName("start and stop are idempotent")
    void startAndStop_AreIdempotent() {
        provider.start();
        provider.start();  // No-op
        assertThat(provider.isStarted()).isTrue();

        provider.stop();
        provider.stop();  // No-op
        assertThat(provider.isStarted()).isFalse();
    }

    @Test
    @DisplayName("addMember adds to active membership")
    void addMember_AddsToActiveMembership() {
        provider.addMember(member1);
        provider.addMember(member2);

        var membership = provider.getActiveMembership();

        assertThat(membership).containsExactlyInAnyOrder(member1, member2);
    }

    @Test
    @DisplayName("removeMember removes from active membership")
    void removeMember_RemovesFromActiveMembership() {
        provider.addMember(member1);
        provider.addMember(member2);
        provider.removeMember(member1);

        var membership = provider.getActiveMembership();

        assertThat(membership).containsExactly(member2);
    }

    @Test
    @DisplayName("setMembership replaces entire membership")
    void setMembership_ReplacesEntireMembership() {
        provider.addMember(member1);

        var newMembership = Set.of(member2, member3);
        provider.setMembership(newMembership);

        assertThat(provider.getActiveMembership()).containsExactlyInAnyOrder(member2, member3);
    }

    @Test
    @DisplayName("getActiveMembership returns immutable snapshot")
    void getActiveMembership_ReturnsImmutableSnapshot() {
        provider.addMember(member1);

        var membership = provider.getActiveMembership();

        assertThat(membership).containsExactly(member1);
        // Verify immutability by attempting modification (should throw)
        try {
            membership.add(member2);
            throw new AssertionError("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            // Expected
        }
    }

    @Test
    @DisplayName("getContext returns null initially")
    void getContext_ReturnsNullInitially() {
        assertThat(provider.getContext()).isNull();
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("setContext stores provided context")
    void setContext_StoresContext() {
        var mockContext = Mockito.mock(DelegatedContext.class);

        provider.setContext(mockContext);

        assertThat(provider.getContext()).isSameAs(mockContext);
    }

    @Test
    @DisplayName("registerHandler adds handler to list")
    void registerHandler_AddsHandler() {
        var handlerCalled = new AtomicInteger(0);

        provider.registerHandler((type, bindings) -> handlerCalled.incrementAndGet());

        assertThat(provider.getHandlerCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("registerTickListener adds listener to list")
    void registerTickListener_AddsListener() {
        var listenerCalled = new AtomicInteger(0);

        provider.registerTickListener(tick -> listenerCalled.incrementAndGet());

        assertThat(provider.getTickListenerCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("simulateMessage notifies all handlers")
    void simulateMessage_NotifiesAllHandlers() {
        var handler1Called = new AtomicInteger(0);
        var handler2Called = new AtomicInteger(0);

        provider.registerHandler((type, bindings) -> handler1Called.incrementAndGet());
        provider.registerHandler((type, bindings) -> handler2Called.incrementAndGet());

        provider.simulateMessage("test", List.of());

        assertThat(handler1Called.get()).isEqualTo(1);
        assertThat(handler2Called.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("simulateTick notifies all listeners and increments tick")
    void simulateTick_NotifiesAllListeners() {
        var listener1Tick = new AtomicInteger(-1);
        var listener2Tick = new AtomicInteger(-1);

        provider.registerTickListener(listener1Tick::set);
        provider.registerTickListener(listener2Tick::set);

        provider.simulateTick();
        assertThat(listener1Tick.get()).isEqualTo(1);
        assertThat(listener2Tick.get()).isEqualTo(1);
        assertThat(provider.getCurrentTick()).isEqualTo(1);

        provider.simulateTick();
        assertThat(listener1Tick.get()).isEqualTo(2);
        assertThat(listener2Tick.get()).isEqualTo(2);
        assertThat(provider.getCurrentTick()).isEqualTo(2);
    }

    @Test
    @DisplayName("publish records message for test verification")
    void publish_RecordsMessage() {
        var message = Join.getDefaultInstance();

        provider.publish(message, true);
        provider.publish(message, false);

        var published = provider.getPublishedMessages();

        assertThat(published).hasSize(2);
        assertThat(published.get(0).message).isSameAs(message);
        assertThat(published.get(0).notifyLocal).isTrue();
        assertThat(published.get(1).notifyLocal).isFalse();
    }

    @Test
    @DisplayName("clearPublishedMessages clears message history")
    void clearPublishedMessages_ClearsHistory() {
        provider.publish(Join.getDefaultInstance(), true);

        provider.clearPublishedMessages();

        assertThat(provider.getPublishedMessages()).isEmpty();
    }

    @Test
    @DisplayName("reset clears all state")
    void reset_ClearsAllState() {
        provider.start();
        provider.addMember(member1);
        provider.registerHandler((type, bindings) -> {});
        provider.registerTickListener(tick -> {});
        provider.publish(Join.getDefaultInstance(), true);
        provider.simulateTick();

        provider.reset();

        assertThat(provider.isStarted()).isFalse();
        assertThat(provider.getActiveMembership()).isEmpty();
        assertThat(provider.getHandlerCount()).isEqualTo(0);
        assertThat(provider.getTickListenerCount()).isEqualTo(0);
        assertThat(provider.getPublishedMessages()).isEmpty();
        assertThat(provider.getCurrentTick()).isEqualTo(0);
        assertThat(provider.getContext()).isNull();
    }

    @Test
    @DisplayName("thread safety: concurrent membership modifications")
    void threadSafety_ConcurrentMembershipModifications() throws InterruptedException {
        var threads = new Thread[10];

        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    provider.addMember(member1);
                    provider.addMember(member2);
                    provider.removeMember(member1);
                }
            });
            threads[i].start();
        }

        for (var thread : threads) {
            thread.join();
        }

        // Should end in consistent state with member2
        assertThat(provider.getActiveMembership()).containsExactly(member2);
    }

    @Test
    @DisplayName("thread safety: concurrent handler invocations")
    void threadSafety_ConcurrentHandlerInvocations() throws InterruptedException {
        var latch = new CountDownLatch(100);

        provider.registerHandler((type, bindings) -> latch.countDown());

        var threads = new Thread[10];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 10; j++) {
                    provider.simulateMessage("test", List.of());
                }
            });
            threads[i].start();
        }

        for (var thread : threads) {
            thread.join();
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("published message timestamps are monotonic")
    void publishedMessage_TimestampsAreMonotonic() throws InterruptedException {
        provider.publish(Join.getDefaultInstance(), true);
        Thread.sleep(10);
        provider.publish(Join.getDefaultInstance(), false);

        var messages = provider.getPublishedMessages();

        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).timestamp).isGreaterThanOrEqualTo(messages.get(0).timestamp);
    }
}
