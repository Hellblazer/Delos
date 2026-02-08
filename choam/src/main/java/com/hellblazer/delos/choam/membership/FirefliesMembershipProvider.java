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
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * Thread Safety: This class is thread-safe. Lifecycle operations use atomic CAS for
 * idempotency. Handler and tick listener registration delegates to BEG which provides
 * its own thread safety.
 *
 * @author hal.hildebrand
 */
public class FirefliesMembershipProvider implements MembershipProvider {
    private final BoundedEpidemicGossip gossip;
    private final DelegatedContext<Member> context;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private Duration gossipDuration;

    /**
     * Constructs a FirefliesMembershipProvider wrapping the given gossip protocol.
     *
     * @param gossip the bounded epidemic gossip instance
     * @param context the delegated context for membership ring structure
     * @param gossipDuration the gossip duration to use when starting
     */
    public FirefliesMembershipProvider(BoundedEpidemicGossip gossip, DelegatedContext<Member> context,
                                       Duration gossipDuration) {
        this.gossip = gossip;
        this.context = context;
        this.gossipDuration = gossipDuration;
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;  // Already started
        }

        gossip.start(gossipDuration);
    }

    @Override
    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;  // Already stopped
        }

        gossip.stop();
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
