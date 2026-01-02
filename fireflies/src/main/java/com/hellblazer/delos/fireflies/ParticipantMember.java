/*
 * Copyright (c) 2019, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.fireflies;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.SigningThreshold;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.stereotomy.Verifiers;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Represents a participant member in the Fireflies view.
 * This class manages the member's identity, note, and accusations across multiple rings.
 *
 * @author hal.hildebrand
 */
public class ParticipantMember implements Member {

    private static final Logger log = LoggerFactory.getLogger(ParticipantMember.class);

    protected final    Digest              id;
    protected final    int                 ringCount;
    protected final    Verifiers           verifiers;
    protected final    MembershipManager   membershipManager;
    protected volatile NoteWrapper         note;
    protected volatile AccusationWrapper[] validAccusations;

    public ParticipantMember(Digest identity, int ringCount, Verifiers verifiers, MembershipManager membershipManager) {
        assert identity != null;
        this.id = identity;
        this.ringCount = ringCount;
        this.verifiers = verifiers;
        this.membershipManager = membershipManager;
        this.validAccusations = new AccusationWrapper[ringCount];
    }

    public ParticipantMember(NoteWrapper nw, int ringCount, Verifiers verifiers, MembershipManager membershipManager) {
        this(nw.getId(), ringCount, verifiers, membershipManager);
        this.note = nw;
    }

    @Override
    public int compareTo(Member o) {
        return id.compareTo(o.getId());
    }

    public String endpoint() {
        final var current = note;
        if (current == null) {
            return null;
        }
        return current.getEndpoint();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Member m) {
            return compareTo(m) == 0;
        }
        return false;
    }

    public int getAccusationCount() {
        var count = 0;
        for (var acc : validAccusations) {
            if (acc != null) {
                count++;
            }
        }
        return count;
    }

    public Iterable<? extends com.hellblazer.delos.fireflies.proto.SignedAccusation> getEncodedAccusations() {
        return getAccusations().map(AccusationWrapper::getWrapped).toList();
    }

    @Override
    public Digest getId() {
        return id;
    }

    public SelfAddressingIdentifier getIdentifier() {
        return note.getIdentifier();
    }

    public com.hellblazer.delos.fireflies.proto.SignedNote getSignedNote() {
        return note.getWrapped();
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    public boolean isDisabled(int ringNumber) {
        final var current = note;
        if (current != null) {
            return !current.getMask().get(ringNumber);
        }
        return false;
    }

    @Override
    public String toString() {
        return "Member[" + getId() + "]";
    }

    @Override
    public boolean verify(JohnHancock signature, InputStream message) {
        final var current = note;
        if (current == null) {
            return true;
        }
        return verifiers.verifierFor(getIdentifier()).map(v -> v.verify(signature, message)).orElse(false);
    }

    public boolean verify(SigningThreshold threshold, JohnHancock signature, InputStream message) {
        final var current = note;
        return verifiers.verifierFor(getIdentifier()).map(v -> v.verify(threshold, signature, message)).orElse(false);
    }

    /**
     * Add an accusation to the member
     *
     * @param accusation
     */
    void addAccusation(AccusationWrapper accusation) {
        var ringNumber = accusation.getRingNumber();
        if (accusation.getRingNumber() >= validAccusations.length) {
            return;
        }
        var n = getNote();
        if (n == null) {
            validAccusations[ringNumber] = accusation;
            return;
        }
        if (n.getEpoch() != accusation.getEpoch()) {
            log.trace("Invalid epoch discarding accusation from: {} context: {} ring {} on: {}",
                      accusation.getAccuser(), getId(), ringNumber, id);
            return;
        }
        if (n.getMask().get(ringNumber)) {
            validAccusations[ringNumber] = accusation;
            if (log.isDebugEnabled()) {
                log.debug("Member: {} is accusing: {} context: {} ring: {} on: {}", accusation.getAccuser(),
                          accusation.getAccused(), getId(), ringNumber, id);
            }
        }
    }

    /**
     * clear all accusations for the member
     */
    void clearAccusations() {
        for (var acc : validAccusations) {
            if (acc != null) {
                log.trace("Clearing accusations for: {} context: {} on: {}", acc.getAccused(), getId(), id);
                break;
            }
        }
        Arrays.fill(validAccusations, null);
    }

    AccusationWrapper getAccusation(int ring) {
        return validAccusations[ring];
    }

    Stream<AccusationWrapper> getAccusations() {
        return Arrays.stream(validAccusations).filter(Objects::nonNull);
    }

    long getEpoch() {
        var current = note;
        if (current == null) {
            return -1;
        }
        return current.getEpoch();
    }

    NoteWrapper getNote() {
        final var current = note;
        return current;
    }

    void invalidateAccusationOnRing(int index) {
        validAccusations[index] = null;
        log.trace("Invalidating accusations context: {} ring: {} on: {}", getId(), index, id);
    }

    boolean isAccused() {
        for (var acc : validAccusations) {
            if (acc != null) {
                return true;
            }
        }
        return false;
    }

    boolean isAccusedOn(int index) {
        if (index >= validAccusations.length) {
            return false;
        }
        return validAccusations[index] != null;
    }

    void reset() {
        note = null;
        validAccusations = new AccusationWrapper[ringCount];
    }

    boolean setNote(NoteWrapper next) {
        note = next;
        if (!membershipManager.isShunned(id)) {
            clearAccusations();
        }
        return true;
    }
}
