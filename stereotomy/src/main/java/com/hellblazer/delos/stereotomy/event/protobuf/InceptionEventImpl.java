/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy.event.protobuf;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.stereotomy.event.proto.KeyEventWithAttachments.Builder;
import com.hellblazer.delos.stereotomy.event.proto.KeyEvent_;
import com.hellblazer.delos.cryptography.proto.PubKey;
import com.hellblazer.delos.stereotomy.event.InceptionEvent;
import com.hellblazer.delos.stereotomy.identifier.BasicIdentifier;
import com.hellblazer.delos.stereotomy.identifier.Identifier;

/**
 * @author hal.hildebrand
 */
public class InceptionEventImpl extends EstablishmentEventImpl implements InceptionEvent {

    final com.hellblazer.delos.stereotomy.event.proto.InceptionEvent event;

    public InceptionEventImpl(com.hellblazer.delos.stereotomy.event.proto.InceptionEvent inceptionEvent) {
        super(inceptionEvent.getSpecification().getHeader(), inceptionEvent.getCommon(),
              inceptionEvent.getSpecification().getEstablishment());
        event = inceptionEvent;

    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof InceptionEventImpl)) {
            return false;
        }
        InceptionEventImpl other = (InceptionEventImpl) obj;
        return Objects.equals(event, other.event);
    }

    @Override
    public Set<ConfigurationTrait> getConfigurationTraits() {
        return event.getSpecification()
                    .getConfigurationList()
                    .stream()
                    .map(s -> ConfigurationTrait.valueOf(s))
                    .collect(Collectors.toSet());
    }

    @Override
    public Identifier getIdentifier() {
        return Identifier.from(event.getIdentifier());
    }

    @Override
    public byte[] getInceptionStatement() {
        return event.getSpecification().toByteArray();
    }

    @Override
    public List<BasicIdentifier> getWitnesses() {
        return event.getSpecification().getWitnessesList().stream().map(s -> witness(s)).collect(Collectors.toList());
    }

    @Override
    public int hashCode() {
        return Objects.hash(event);
    }

    @Override
    public void setEventOf(Builder builder) {
        builder.setInception(event);
    }

    public com.hellblazer.delos.stereotomy.event.proto.InceptionEvent toInceptionEvent_() {
        return event;
    }

    @Override
    public KeyEvent_ toKeyEvent_() {
        return KeyEvent_.newBuilder().setInception(event).build();
    }

    @Override
    protected ByteString toByteString() {
        return event.toByteString();
    }

    private BasicIdentifier witness(PubKey pk) {
        return new BasicIdentifier(pk);
    }
}
