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
import com.hellblazer.delos.stereotomy.event.proto.Binding;

import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Abstracts membership and gossip functionality for CHOAM. Implementations encapsulate
 * membership tracking, gossip protocol management, and context queries.
 * <p>
 * This interface provides:
 * - Lifecycle management (start/stop)
 * - Membership queries (who is active, what is the context)
 * - Gossip integration (handler/tick listener registration)
 * <p>
 * Thread Safety: Implementations must provide thread-safe lifecycle and query operations.
 * Handlers and listeners may be called from gossip protocol threads.
 *
 * @author hal.hildebrand
 */
public interface MembershipProvider {

    /**
     * Start the membership provider and gossip protocols.
     * <p>
     * This method is idempotent - calling start on an already-started provider has no effect.
     */
    void start();

    /**
     * Stop the membership provider and gossip protocols, releasing resources.
     * <p>
     * This method is idempotent - calling stop on an already-stopped provider has no effect.
     * Implementations must handle in-flight gossip operations gracefully.
     */
    void stop();

    /**
     * Get the set of currently active members in the membership view.
     * <p>
     * Thread Safety: This method may be called concurrently with membership changes.
     * The returned set is a snapshot of membership at the time of the call.
     *
     * @return immutable set of active members
     */
    Set<Member> getActiveMembership();

    /**
     * Get the current membership context (ring structure for gossip).
     * <p>
     * The DelegatedContext provides the ring-based membership structure used for
     * gossip protocols and BFT subset selection.
     *
     * @return the current membership context
     */
    DelegatedContext<Member> getContext();

    /**
     * Register a handler for gossip messages.
     * <p>
     * The handler receives the message type (String) and a list of bindings.
     * Implementations must ensure the handler is called on appropriate threads
     * (typically gossip protocol threads).
     * <p>
     * Thread Safety: The handler will be called from gossip threads and must be thread-safe.
     *
     * @param handler the message handler (messageType, bindings)
     */
    void registerHandler(BiConsumer<String, List<Binding>> handler);

    /**
     * Register a listener for gossip tick events.
     * <p>
     * The tick listener is called on each gossip round with the current tick number.
     * This is used by components that need to synchronize with gossip rounds
     * (e.g., block producers scheduling operations).
     * <p>
     * Thread Safety: The listener will be called from gossip threads and must be thread-safe.
     *
     * @param tickListener the tick listener (tick number)
     */
    void registerTickListener(Consumer<Integer> tickListener);

    /**
     * Publish a message through the gossip protocol.
     * <p>
     * Thread Safety: This method is thread-safe and can be called concurrently.
     *
     * @param message      the message to publish
     * @param notifyLocal  whether to notify local handlers
     */
    void publish(com.google.protobuf.Message message, boolean notifyLocal);
}
