/*
 * Copyright (c) 2019, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.fireflies.proto.*;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.event.proto.KeyState_;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.utils.Entropy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Represents the local node in the Fireflies membership view. This is a specialized
 * Participant that can sign messages and generate notes for view membership.
 *
 * @author hal.hildebrand
 */
public class NodeMember extends View.Participant implements SigningMember {
    private static final Logger                      log        = LoggerFactory.getLogger(NodeMember.class);
    private final        ControlledIdentifierMember wrapped;
    private final        View                        view;

    public NodeMember(View view, ControlledIdentifierMember wrapped, String endpoint) {
        super(wrapped.getId(), view.context.getRingCount(), view.verifiers, view.membershipManager);
        this.view = view;
        this.wrapped = wrapped;

        var n = Note.newBuilder()
                    .setEpoch(0)
                    .setEndpoint(endpoint)
                    .setIdentifier(wrapped.getIdentifier().getIdentifier().toIdent())
                    .setMask(ByteString.copyFrom(nextMask().toByteArray()))
                    .build();
        var signedNote = SignedNote.newBuilder()
                                   .setNote(n)
                                   .setSignature(wrapped.sign(n.toByteString()).toSig())
                                   .build();
        note = new NoteWrapper(signedNote, view.digestAlgo);
        log.info("Endpoint: {} on: {}", endpoint, wrapped.getId());
    }

    /**
     * Create a mask of length DynamicContext.majority() randomly disabled rings
     *
     * @return the mask
     */
    public static BitSet createInitialMask(DynamicContext<?> context) {
        int nbits = context.getRingCount();
        BitSet mask = new BitSet(nbits);
        List<Boolean> random = new ArrayList<>();
        for (int i = 0; i < context.majority(); i++) {
            random.add(true);
        }
        for (int i = 0; i < context.toleranceLevel(); i++) {
            random.add(false);
        }
        Entropy.secureShuffle(random);
        for (int i = 0; i < nbits; i++) {
            if (random.get(i)) {
                mask.set(i);
            }
        }
        return mask;
    }

    @Override
    public SignatureAlgorithm algorithm() {
        return wrapped.algorithm();
    }

    public SelfAddressingIdentifier getIdentifier() {
        return wrapped.getIdentifier().getIdentifier();
    }

    @Override
    public JohnHancock sign(InputStream message) {
        return wrapped.sign(message);
    }

    @Override
    public String toString() {
        return "Node[" + getId() + "]";
    }

    AccusationWrapper accuse(ParticipantMember m, int ringNumber) {
        var accusation = Accusation.newBuilder()
                                   .setEpoch(m.getEpoch())
                                   .setRingNumber(ringNumber)
                                   .setAccuser(getId().toDigeste())
                                   .setAccused(m.getId().toDigeste())
                                   .setCurrentView(view.currentView().toDigeste())
                                   .build();
        return new AccusationWrapper(SignedAccusation.newBuilder()
                                                     .setAccusation(accusation)
                                                     .setSignature(wrapped.sign(accusation.toByteString()).toSig())
                                                     .build(), view.digestAlgo);
    }

    /**
     * @return a new mask based on the previous mask and previous accusations.
     */
    BitSet nextMask() {
        final var current = note;
        if (current == null) {
            BitSet mask = createInitialMask(view.context);
            assert View.isValidMask(mask, view.context) : "Invalid mask: " + mask + " majority: " + view.context.majority()
            + " for node: " + getId();
            return mask;
        }

        BitSet mask = new BitSet(view.context.getRingCount());
        mask.flip(0, view.context.getRingCount());
        final var accusations = validAccusations;

        // disable current accusations
        for (int i = 0; i < view.context.getRingCount() && i < accusations.length; i++) {
            if (accusations[i] != null) {
                mask.set(i, false);
            }
        }
        // clear masks from previous note
        BitSet previous = BitSet.valueOf(current.getMask().toByteArray());
        for (int index = 0; index < view.context.getRingCount() && index < accusations.length; index++) {
            if (!previous.get(index) && accusations[index] == null) {
                mask.set(index, true);
            }
        }

        // Fill the rest of the mask with randomly-set index

        while (mask.cardinality() != ((view.context.getBias() - 1) * view.context.toleranceLevel()) + 1) {
            int index = Entropy.nextBitsStreamInt(view.context.getRingCount());
            if (index < accusations.length) {
                if (accusations[index] != null) {
                    continue;
                }
            }
            if (mask.cardinality() > view.context.toleranceLevel() + 1 && mask.get(index)) {
                mask.set(index, false);
            } else if (mask.cardinality() < view.context.toleranceLevel() && !mask.get(index)) {
                mask.set(index, true);
            }
        }
        assert View.isValidMask(mask, view.context) : "Invalid mask: " + mask + " t: " + view.context.toleranceLevel()
        + " for node: " + getId();
        return mask;
    }

    /**
     * Generate a new note for the member based on any previous note and previous accusations. The new note has a
     * larger epoch number the the current note.
     */
    void nextNote() {
        nextNote(view.currentView());
    }

    void nextNote(Digest nextView) {
        NoteWrapper current = note;
        long newEpoch = current == null ? 0 : note.getEpoch() + 1;
        nextNote(newEpoch, nextView);
    }

    /**
     * Generate a new note using the new epoch
     *
     * @param newEpoch
     */
    void nextNote(long newEpoch, Digest nextView) {
        final var current = note;
        var n = current.newBuilder()
                       .setIdentifier(note.getIdentifier().toIdent())
                       .setEpoch(newEpoch)
                       .setMask(ByteString.copyFrom(nextMask().toByteArray()))
                       .setCurrentView(nextView.toDigeste())
                       .build();
        var signedNote = SignedNote.newBuilder()
                                   .setNote(n)
                                   .setSignature(wrapped.sign(n.toByteString()).toSig())
                                   .build();
        note = new NoteWrapper(signedNote, view.digestAlgo);
    }

    KeyState_ noteState() {
        return wrapped.getIdentifier().toKeyState_();
    }

    @Override
    void reset() {
        final var current = note;
        super.reset();
        var n = Note.newBuilder()
                    .setEpoch(0)
                    .setCurrentView(view.currentView().toDigeste())
                    .setEndpoint(current.getEndpoint())
                    .setIdentifier(current.getIdentifier().toIdent())
                    .setMask(ByteString.copyFrom(nextMask().toByteArray()))
                    .build();
        SignedNote signedNote = SignedNote.newBuilder()
                                          .setNote(n)
                                          .setSignature(wrapped.sign(n.toByteString()).toSig())
                                          .build();
        note = new NoteWrapper(signedNote, view.digestAlgo);
    }
}
