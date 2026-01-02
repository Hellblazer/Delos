/*
 * Copyright (c) 2019, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies;

import com.codahale.metrics.Timer;
import com.hellblazer.delos.archipelago.Router.ServiceRouting;
import com.hellblazer.delos.bloomFilters.BloomFilter;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.fireflies.View.Participant;
import com.hellblazer.delos.fireflies.comm.entrance.EntranceService;
import com.hellblazer.delos.fireflies.comm.gossip.FFService;
import com.hellblazer.delos.fireflies.proto.*;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service implementation for View that handles gRPC service methods for gossip and entrance protocols.
 * Extracted from View inner class to top-level class.
 *
 * @author hal.hildebrand
 */
public class ViewService implements EntranceService, FFService, ServiceRouting {
    private static final Logger log = LoggerFactory.getLogger(ViewService.class);

    private final View view;

    public ViewService(View view) {
        this.view = view;
    }

    public void enjoin(Join join, Digest from) {
        view.viewManagement.enjoin(join, from);
    }

    /**
     * Asynchronously add a member to the next view
     */
    @Override
    public void join(Join join, Digest from, StreamObserver<Gateway> responseObserver, Timer.Context timer) {
        if (!view.enterOperation()) {
            responseObserver.onError(
            new StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription("Not started")));
            return;
        }
        try {
            view.viewManagement.join(join, from, responseObserver, timer);
        } finally {
            view.exitOperation();
        }
    }

    public void ping(Ping ping, Digest from) {
        final var ring = ping.getRing();
        if (!view.context.validRing(ring)) {
            log.debug("invalid Ping ring: {} current: {} from: {} on: {}", ring, view.currentView(), from,
                      view.node.getId());
            throw new StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("Invalid ring"));
        }
        Participant member = view.context.getActiveMember(from);
        Participant successor = view.context.successor(ring, member, m -> view.context.isActive(m.getId()));
        if (successor == null || !successor.equals(view.node)) {
            log.debug("Not predecessor, invalid ping from: {} on ring: {} on: {}", from, ring, view.node.getId());
            throw new StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription("Not predecessor"));
        }
        // perfectly fine ping
    }

    /**
     * The first message in the anti-entropy protocol. Process any digests from the inbound gossip digest. Respond
     * with the Gossip that represents the digests newer or not known in this view, as well as updates from this
     * node based on out-of-date information in the supplied digests.
     *
     * @param request - the Gossip from our partner
     * @return Teh response for Moar gossip - updates this node has which the sender is out of touch with, and
     * digests from the sender that this node would like updated.
     */
    @Override
    public Gossip rumors(SayWhat request, Digest from) {
        if (!view.introduced.get()) {
            //                log.trace("Not introduced; ring: {} from: {}, on: {}", request.getRing(), from, node.getId());
            throw new StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription("Not introduced"));
        }
        return view.stable(() -> {
            final var ring = request.getRing();
            if (!view.context.validRing(ring)) {
                //                    log.debug("invalid gossip ring: {} from: {} on: {}", ring, from, node.getId());
                throw new StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription("invalid ring"));
            }
            view.validate(from, request);

            Participant member = view.context.getActiveMember(from);
            if (member == null) {
                view.add(new NoteWrapper(request.getNote(), view.digestAlgo));
                member = view.context.getActiveMember(from);
                if (member == null) {
                    log.debug("Not active member: {} on: {}", from, view.node.getId());
                    throw new StatusRuntimeException(Status.PERMISSION_DENIED.withDescription("Not active member"));
                }
            }

            Participant successor = view.context.successor(ring, member, m -> view.context.isActive(m.getId()));
            if (successor == null) {
                log.debug("No active successor on ring: {} from: {} on: {}", ring, from, view.node.getId());
                throw new StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription("No active successor"));
            }

            Gossip g;
            var builder = Gossip.newBuilder();
            final var digests = request.getGossip();
            if (!successor.equals(view.node)) {
                builder.setRedirect(successor.getNote().getWrapped());
                log.debug("Redirected: {} to: {} on: {}", member.getId(), successor.getId(), view.node.getId());
                return builder.build();
            }
            g = builder.setNotes(
                       view.processNotes(from, BloomFilter.from(digests.getNoteBff()), view.params.fpr()))
                       .setAccusations(view.processAccusations(BloomFilter.from(digests.getAccusationBff()),
                                                               view.params.fpr()))
                       .setObservations(view.processObservations(BloomFilter.from(digests.getObservationBff()),
                                                                 view.params.fpr()))
                       .setJoins(view.viewManagement.processJoins(BloomFilter.from(digests.getJoinBiff()),
                                                                  view.params.fpr()))
                       .build();
            if (g.getNotes().getUpdatesCount() + g.getAccusations().getUpdatesCount() + g.getObservations()
                                                                                         .getUpdatesCount()
            + g.getJoins().getUpdatesCount() != 0) {
                log.trace("Gossip for: {} notes: {} accusations: {} joins: {} observations: {} on: {}", from,
                          g.getNotes().getUpdatesCount(), g.getAccusations().getUpdatesCount(),
                          g.getJoins().getUpdatesCount(), g.getObservations().getUpdatesCount(), view.node.getId());
            }
            return g;
        });
    }

    @Override
    public Redirect seed(Registration registration, Digest from) {
        if (!view.enterOperation()) {
            throw new StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription("Not started"));
        }
        try {
            return view.viewManagement.seed(registration, from);
        } finally {
            view.exitOperation();
        }
    }

    /**
     * The third and final message in the anti-entropy protocol. Process the inbound update from another member.
     *
     * @param request - update state
     * @param from
     */
    @Override
    public void update(State request, Digest from) {
        if (!view.introduced.get()) {
            log.trace("Currently still being introduced, send unknown to: {}  on: {}", from, view.node.getId());
            return;
        }
        view.stable(() -> {
            view.validate(from, request);
            final var ring = request.getRing();
            if (!view.context.validRing(ring)) {
                log.debug("invalid ring: {} current: {} from: {} on: {}", ring, view.currentView(), from,
                          view.node.getId());
                throw new StatusRuntimeException(Status.INVALID_ARGUMENT.withDescription("Invalid ring"));
            }
            Participant member = view.context.getActiveMember(from);
            Participant successor = view.context.successor(ring, member, m -> view.context.isActive(m.getId()));
            if (successor == null) {
                log.debug("No successor, invalid update from: {} on ring: {} on: {}", from, ring, view.node.getId());
                throw new StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription("No successor"));
            }
            if (!successor.equals(view.node)) {
                return;
            }
            final var update = request.getUpdate();
            if (!update.equals(Update.getDefaultInstance())) {
                view.processUpdates(update.getNotesList(), update.getAccusationsList(), update.getObservationsList(),
                                    update.getJoinsList());
            }
        });
    }
}
