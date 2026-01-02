/*
 * Copyright (c) 2024, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.bloomFilters.BloomFilter;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.fireflies.View.Participant;
import com.hellblazer.delos.fireflies.proto.NoteGossip;
import com.hellblazer.delos.membership.ReservoirSampler;
import com.hellblazer.delos.stereotomy.Verifiers;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.utils.BbBackedInputStream;
import com.hellblazer.delos.utils.Entropy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.BitSet;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.Stream;

/**
 * Implementation of member tracking and lifecycle management.
 *
 * @author hal.hildebrand
 */
class MembershipManagerImpl implements MembershipManager {

    private static final Logger log = LoggerFactory.getLogger(MembershipManagerImpl.class);

    private final ViewContext                      viewContext;
    private final AccusationTracker                accusationTracker;
    private final ViewManagement                   viewManagement;
    private final Verifiers                        verifiers;
    private final Set<Digest>                      shunned;
    private final ParticipantFactory               participantFactory;

    @FunctionalInterface
    interface ParticipantFactory {
        Participant create(NoteWrapper note);
    }

    MembershipManagerImpl(ViewContext viewContext, AccusationTracker accusationTracker,
                          ViewManagement viewManagement, Verifiers verifiers,
                          ParticipantFactory participantFactory) {
        this.viewContext = viewContext;
        this.accusationTracker = accusationTracker;
        this.viewManagement = viewManagement;
        this.verifiers = verifiers;
        this.participantFactory = participantFactory;
        this.shunned = new ConcurrentSkipListSet<>();
    }

    @Override
    public boolean addToView(NoteWrapper note) {
        var newMember = false;
        NoteWrapper current = null;

        Participant m = viewContext.getContext().getMember(note.getId());
        if (m == null) {
            newMember = true;
            if (!verify(note.getIdentifier(), note.getSignature(), note.getWrapped().getNote().toByteString())) {
                log.trace("invalid participant note from: {} on: {}", note.getId(), viewContext.getNode().getId());
                if (viewContext.getMetrics() != null) {
                    viewContext.getMetrics().filteredNotes().mark();
                }
                return false;
            }
            m = participantFactory.create(note);
            viewContext.getContext().add(m);
        } else {
            current = m.getNote();
            if (!newMember && current != null) {
                long nextEpoch = note.getEpoch();
                long currentEpoch = current.getEpoch();
                if (nextEpoch <= currentEpoch) {
                    if (viewContext.getMetrics() != null) {
                        viewContext.getMetrics().filteredNotes().mark();
                    }
                    return false;
                }
            }
        }

        if (viewContext.getMetrics() != null) {
            viewContext.getMetrics().notes().mark();
        }

        var member = m;
        return viewContext.stable(() -> {
            if (!member.verify(note.getSignature(), note.getWrapped().getNote().toByteString())) {
                log.trace("Note signature invalid: {} on: {}", note.getId(), viewContext.getNode().getId());
                if (viewContext.getMetrics() != null) {
                    viewContext.getMetrics().filteredNotes().mark();
                }
                return false;
            }
            var accused = member.isAccused();
            accusationTracker.stopRebuttalTimer(member);
            member.setNote(note);
            recover(member);
            if (accused) {
                accusationTracker.checkInvalidations(member);
            }
            if (!viewManagement.joined() && viewContext.getContext().size() == viewManagement.cardinality()) {
                assert viewContext.getContext().size() == viewManagement.cardinality();
                viewManagement.join();
            } else {
                // This assertion needs to accommodate invalid diadem cardinality during view installation, as the diadem
                // is from the previous view until all joining member have... joined.
                assert viewContext.getContext().size() <= Math.max(viewManagement.cardinality(),
                                                                    viewContext.getContext().cardinality()) : "total: "
                + viewContext.getContext().size() + " card: " + viewManagement.cardinality();
            }
            return true;
        });
    }

    @Override
    public boolean addToCurrentView(NoteWrapper note) {
        if (!viewContext.currentView().equals(note.currentView())) {
            log.trace("Ignoring note in invalid view: {} current: {} from {} on: {}", note.currentView(),
                      viewContext.currentView(), note.getId(), viewContext.getNode().getId());
            if (viewContext.getMetrics() != null) {
                viewContext.getMetrics().filteredNotes().mark();
            }
            return false;
        }
        if (shunned.contains(note.getId())) {
            if (viewContext.getMetrics() != null) {
                viewContext.getMetrics().shunnedGossip().mark();
            }
            log.trace("Ignoring note from shunned member: {} on: {}", note.getId(), viewContext.getNode().getId());
            return false;
        }

        return addToView(note);
    }

    @Override
    public void remove(Digest id) {
        log.info("Permanently removing {} member {} from context: {} view: {} on: {}",
                 viewContext.getContext().isActive(id) ? "active" : "failed", id, viewContext.getContext().getId(),
                 viewContext.currentView(), viewContext.getNode().getId());
        viewContext.getContext().remove(id);
        shunned.remove(id);
        if (viewContext.getMetrics() != null) {
            viewContext.getMetrics().leaves().mark();
        }
    }

    @Override
    public void recover(Participant member) {
        if (shunned.contains(member.getId())) {
            log.debug("Not recovering shunned: {} on: {}", member.getId(), viewContext.getNode().getId());
            return;
        }
        if (viewContext.getContext().activate(member)) {
            log.trace("Recovering: {} cardinality: {} count: {} on: {}", member.getId(),
                      viewManagement.cardinality(), viewContext.getContext().size(), viewContext.getNode().getId());
        }
    }

    @Override
    public boolean isShunned(Digest id) {
        return shunned.contains(id);
    }

    @Override
    public void shun(Digest id) {
        shunned.add(id);
    }

    @Override
    public Stream<Digest> streamShunned() {
        return shunned.stream();
    }

    @Override
    public NoteGossip processNotes(Digest from, BloomFilter<Digest> bff, double fpr) {
        NoteGossip.Builder builder = processNotes(bff);
        builder.setBff(getNotesBff(Entropy.nextSecureLong(), fpr).toBff());
        if (builder.getUpdatesCount() != 0) {
            log.trace("process notes produced updates: {} on: {}", builder.getUpdatesCount(),
                      viewContext.getNode().getId());
        }
        return builder.build();
    }

    @Override
    public BloomFilter<Digest> getNotesBff(long seed, double p) {
        var n = Math.max(viewContext.getParams().minimumBiffCardinality(), viewContext.getContext().cardinality());
        BloomFilter<Digest> bff = new BloomFilter.DigestBloomFilter(seed, n, 1.0 / (double) n);
        viewContext.getContext()
                   .allMembers()
                   .map(m -> m.getNote())
                   .filter(e -> e != null)
                   .forEach(note -> bff.add(note.getHash()));
        return bff;
    }

    @Override
    public boolean isValidMask(BitSet mask) {
        if (mask.cardinality() == viewContext.getContext().majority()) {
            if (mask.length() <= viewContext.getContext().getRingCount()) {
                return true;
            }
        }
        return false;
    }

    // Private helper methods

    private NoteGossip.Builder processNotes(BloomFilter<Digest> bff) {
        NoteGossip.Builder builder = NoteGossip.newBuilder();

        // Add all updates that this view has that aren't reflected in the inbound bff
        final var current = viewContext.currentView();
        viewContext.getContext()
                   .active()
                   .filter(m -> m.getNote() != null)
                   .filter(m -> current.equals(m.getNote().currentView()))
                   .filter(m -> !shunned.contains(m.getId()))
                   .filter(m -> !bff.contains(m.getNote().getHash()))
                   .collect(new ReservoirSampler<>(viewContext.getParams().maximumTxfr()))
                   .stream()
                   .filter(sn -> sn != null)
                   .map(Participant::getNote)
                   .forEach(n -> builder.addUpdates(n.getWrapped()));
        return builder;
    }

    private boolean verify(SelfAddressingIdentifier id, JohnHancock signature, ByteString byteString) {
        return verify(id, signature, BbBackedInputStream.aggregate(byteString));
    }

    private boolean verify(SelfAddressingIdentifier id, JohnHancock signature, InputStream message) {
        return verifiers.verifierFor(id).map(value -> value.verify(signature, message)).orElse(false);
    }
}
