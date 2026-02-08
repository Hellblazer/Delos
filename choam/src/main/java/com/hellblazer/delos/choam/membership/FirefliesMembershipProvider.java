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
import com.hellblazer.delos.stereotomy.event.proto.Binding;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Adapter wrapping BoundedEpidemicGossip (BEG) to provide membership and gossip functionality.
 * <p>
 * NOTE: This adapter bridges between the MembershipProvider interface and BEG's actual API.
 * Some signature mismatches exist (registerHandler uses different types) which will be
 * addressed in Phase C when CHOAM is refactored to use this adapter.
 * <p>
 * Thread Safety: This class is thread-safe. Lifecycle operations use atomic state transitions
 * for idempotency. Handler and tick listener registration delegates to BEG which provides
 * its own thread safety.
 * <p>
 * State transitions: INITIAL → STARTING → STARTED → STOPPING → STOPPED
 *
 * @author hal.hildebrand
 */
public class FirefliesMembershipProvider implements MembershipProvider {
    /**
     * Lifecycle states for the membership provider.
     */
    private enum State {
        INITIAL,   // Not yet started
        STARTING,  // Start in progress
        STARTED,   // Fully started and operational
        STOPPING,  // Stop in progress
        STOPPED    // Fully stopped
    }

    private final BoundedEpidemicGossip       gossip;
    private final DelegatedContext<Member>    context;
    private final Duration                    gossipDuration;
    private final AtomicReference<State>     state = new AtomicReference<>(State.INITIAL);

    /**
     * Constructs a FirefliesMembershipProvider wrapping the given gossip protocol.
     *
     * @param gossip         the bounded epidemic gossip instance (must not be null)
     * @param context        the delegated context for membership ring structure (must not be null)
     * @param gossipDuration the gossip duration to use when starting (must not be null)
     * @throws NullPointerException if any parameter is null
     */
    public FirefliesMembershipProvider(BoundedEpidemicGossip gossip, DelegatedContext<Member> context,
                                       Duration gossipDuration) {
        this.gossip = Objects.requireNonNull(gossip, "gossip cannot be null");
        this.context = Objects.requireNonNull(context, "context cannot be null");
        this.gossipDuration = Objects.requireNonNull(gossipDuration, "gossipDuration cannot be null");
    }

    @Override
    public void start() {
        if (!state.compareAndSet(State.INITIAL, State.STARTING)) {
            return;  // Not in INITIAL state (already started or starting)
        }

        try {
            gossip.start(gossipDuration);
            state.set(State.STARTED);
        } catch (Exception e) {
            // Reset to INITIAL on failure
            state.set(State.INITIAL);
            throw e;
        }
    }

    @Override
    public void stop() {
        if (!state.compareAndSet(State.STARTED, State.STOPPING)) {
            return;  // Not in STARTED state (already stopped, stopping, or never started)
        }

        try {
            gossip.stop();
            state.set(State.STOPPED);
        } catch (Exception e) {
            // Even if stop fails, mark as stopped (can't retry stop operation)
            state.set(State.STOPPED);
            throw e;
        }
    }

    @Override
    public Set<Member> getActiveMembership() {
        // Get active members from context rings
        // Collect members from all rings
        return context.allMembers().collect(Collectors.toSet());
    }

    @Override
    public DelegatedContext<Member> getContext() {
        return context;
    }

    @Override
    public void registerHandler(BiConsumer<String, List<Binding>> handler) {
        // NOTE: Signature mismatch - BEG uses MessageHandler (Digest, List<Msg>)
        // but interface requires BiConsumer<String, List<Binding>>.
        // This adapter implementation is a placeholder that will be refined in Phase C
        // when we integrate with actual CHOAM usage patterns.
        throw new UnsupportedOperationException(
        "Handler registration requires signature adaptation - to be implemented in Phase C integration");
    }

    @Override
    public void registerTickListener(Consumer<Integer> tickListener) {
        gossip.register(tickListener);
    }
}
