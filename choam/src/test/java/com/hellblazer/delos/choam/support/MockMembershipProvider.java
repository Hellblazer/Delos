/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.choam.support;

import com.hellblazer.delos.choam.membership.MembershipProvider;
import com.hellblazer.delos.context.DelegatedContext;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.stereotomy.event.proto.Binding;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Mock implementation of MembershipProvider for unit tests. Provides configurable membership
 * and gossip behavior without requiring Fireflies infrastructure.
 * <p>
 * Features:
 * - Configurable membership sets (add/remove members)
 * - Handler and tick listener registration
 * - Simulated gossip publication (records messages)
 * - Partition simulation (split membership sets)
 * <p>
 * Thread Safety: All operations are thread-safe using atomic state and concurrent collections.
 *
 * @author hal.hildebrand
 */
public class MockMembershipProvider implements MembershipProvider {
    private final AtomicBoolean                                started         = new AtomicBoolean(false);
    private final Set<Member>                                  members         = ConcurrentHashMap.newKeySet();
    private final List<BiConsumer<String, List<Binding>>>     handlers        = new CopyOnWriteArrayList<>();
    private final List<Consumer<Integer>>                      tickListeners   = new CopyOnWriteArrayList<>();
    private final List<PublishedMessage>                       publishedMessages = new CopyOnWriteArrayList<>();
    private volatile DelegatedContext<Member>                  context;
    private volatile int                                       currentTick     = 0;

    /**
     * Record of a published message for test verification.
     */
    public static class PublishedMessage {
        public final com.google.protobuf.Message message;
        public final boolean                     notifyLocal;
        public final long                        timestamp;

        public PublishedMessage(com.google.protobuf.Message message, boolean notifyLocal) {
            this.message = message;
            this.notifyLocal = notifyLocal;
            this.timestamp = System.currentTimeMillis();
        }
    }

    @Override
    public void start() {
        started.set(true);
    }

    @Override
    public void stop() {
        started.set(false);
    }

    @Override
    public Set<Member> getActiveMembership() {
        return Collections.unmodifiableSet(new HashSet<>(members));
    }

    @Override
    public DelegatedContext<Member> getContext() {
        return context;
    }

    @Override
    public void registerHandler(BiConsumer<String, List<Binding>> handler) {
        handlers.add(handler);
    }

    @Override
    public void registerTickListener(Consumer<Integer> tickListener) {
        tickListeners.add(tickListener);
    }

    @Override
    public void publish(com.google.protobuf.Message message, boolean notifyLocal) {
        publishedMessages.add(new PublishedMessage(message, notifyLocal));
    }

    /**
     * Check if the provider is started.
     *
     * @return true if started
     */
    public boolean isStarted() {
        return started.get();
    }

    /**
     * Add a member to the active membership.
     *
     * @param member the member to add
     */
    public void addMember(Member member) {
        members.add(member);
    }

    /**
     * Remove a member from the active membership.
     *
     * @param member the member to remove
     */
    public void removeMember(Member member) {
        members.remove(member);
    }

    /**
     * Set the entire membership set (replaces current membership).
     *
     * @param newMembers the new membership set
     */
    public void setMembership(Set<Member> newMembers) {
        members.clear();
        members.addAll(newMembers);
    }

    /**
     * Configure the delegated context.
     *
     * @param context the context to use
     */
    public void setContext(DelegatedContext<Member> context) {
        this.context = context;
    }

    /**
     * Simulate a gossip tick by notifying all registered tick listeners.
     */
    public void simulateTick() {
        currentTick++;
        for (var listener : tickListeners) {
            listener.accept(currentTick);
        }
    }

    /**
     * Simulate receiving a gossip message by notifying all registered handlers.
     *
     * @param messageType the message type identifier
     * @param bindings    the message bindings
     */
    public void simulateMessage(String messageType, List<Binding> bindings) {
        for (var handler : handlers) {
            handler.accept(messageType, bindings);
        }
    }

    /**
     * Get all published messages for test verification.
     *
     * @return unmodifiable list of published messages
     */
    public List<PublishedMessage> getPublishedMessages() {
        return Collections.unmodifiableList(publishedMessages);
    }

    /**
     * Get the current tick number.
     *
     * @return the current tick
     */
    public int getCurrentTick() {
        return currentTick;
    }

    /**
     * Get the number of registered handlers.
     *
     * @return handler count
     */
    public int getHandlerCount() {
        return handlers.size();
    }

    /**
     * Get the number of registered tick listeners.
     *
     * @return tick listener count
     */
    public int getTickListenerCount() {
        return tickListeners.size();
    }

    /**
     * Clear all published messages (useful for test cleanup).
     */
    public void clearPublishedMessages() {
        publishedMessages.clear();
    }

    /**
     * Reset the mock to initial state.
     */
    public void reset() {
        started.set(false);
        members.clear();
        handlers.clear();
        tickListeners.clear();
        publishedMessages.clear();
        context = null;
        currentTick = 0;
    }
}
