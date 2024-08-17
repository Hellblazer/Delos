/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.membership.stereotomy;

import com.hellblazer.delos.cryptography.*;
import com.hellblazer.delos.cryptography.cert.CertificateWithPrivateKey;
import com.hellblazer.delos.membership.Member;
import com.hellblazer.delos.membership.SigningMember;
import com.hellblazer.delos.stereotomy.ControlledIdentifier;
import com.hellblazer.delos.stereotomy.KERL.EventWithAttachments;
import com.hellblazer.delos.stereotomy.event.EstablishmentEvent;
import com.hellblazer.delos.stereotomy.event.proto.KERL_;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * @author hal.hildebrand
 */
public class ControlledIdentifierMember implements SigningMember {

    private final Digest                                         id;
    private final ControlledIdentifier<SelfAddressingIdentifier> identifier;

    public ControlledIdentifierMember(ControlledIdentifier<SelfAddressingIdentifier> identifier) {
        this.identifier = identifier;
        this.id = identifier.getIdentifier().getDigest();
    }

    @Override
    public SignatureAlgorithm algorithm() {
        return identifier.algorithm();
    }

    @Override
    public int compareTo(Member o) {
        return id.compareTo(o.getId());
    }

    @Override
    // The id of a member uniquely identifies it
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!(obj instanceof Member))
            return false;
        return id.equals(((Member) obj).getId());
    }

    public CertificateWithPrivateKey getCertificateWithPrivateKey(Instant validFrom, Duration valid,
                                                                  SignatureAlgorithm signatureAlgorithm) {
        return identifier.provision(validFrom, valid, signatureAlgorithm);
    }

    public EstablishmentEvent getEvent() {
        return identifier.getLastEstablishingEvent();
    }

    @Override
    public Digest getId() {
        return id;
    }

    public ControlledIdentifier<SelfAddressingIdentifier> getIdentifier() {
        return identifier;
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    public KERL_ kerl() {
        List<EventWithAttachments> ker = identifier.getKerl();
        return kerl(ker);
    }

    @Override
    public JohnHancock sign(InputStream message) {
        Signer signer = identifier.getSigner();
        if (signer == null) {
            LoggerFactory.getLogger(ControlledIdentifierMember.class).warn("Null signer for: {}", getId());
            return algorithm().nullSignature();
        }
        return signer.sign(message);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + getId();
    }

    @Override
    public boolean verify(JohnHancock signature, InputStream message) {
        var verifier = identifier.getVerifier();
        if (verifier.isEmpty()) {
            return false;
        }
        return verifier.get().verify(signature, message);
    }

    @Override
    public boolean verify(SigningThreshold threshold, JohnHancock signature, InputStream message) {
        var verifier = identifier.getVerifier();
        if (verifier.isEmpty()) {
            return false;
        }
        return verifier.get().verify(threshold, signature, message);
    }

    private KERL_ kerl(List<EventWithAttachments> kerl) {
        return KERL_.newBuilder().addAllEvents(kerl.stream().map(ewa -> ewa.toKeyEvente()).toList()).build();
    }
}
