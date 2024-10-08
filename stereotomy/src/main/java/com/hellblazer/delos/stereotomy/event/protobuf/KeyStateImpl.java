/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy.event.protobuf;

import static com.hellblazer.delos.cryptography.QualifiedBase64.bs;
import static com.hellblazer.delos.cryptography.QualifiedBase64.digest;
import static com.hellblazer.delos.cryptography.QualifiedBase64.publicKey;
import static com.hellblazer.delos.stereotomy.event.protobuf.ProtobufEventFactory.toSigningThreshold;
import static com.hellblazer.delos.stereotomy.identifier.QualifiedBase64Identifier.identifier;

import java.security.PublicKey;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.protobuf.InvalidProtocolBufferException;
import com.hellblazer.delos.stereotomy.event.proto.KeyState_;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.SigningThreshold;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.KeyState;
import com.hellblazer.delos.stereotomy.event.DelegatedInceptionEvent;
import com.hellblazer.delos.stereotomy.event.EstablishmentEvent;
import com.hellblazer.delos.stereotomy.event.InceptionEvent;
import com.hellblazer.delos.stereotomy.event.InceptionEvent.ConfigurationTrait;
import com.hellblazer.delos.stereotomy.event.KeyEvent;
import com.hellblazer.delos.stereotomy.identifier.BasicIdentifier;
import com.hellblazer.delos.stereotomy.identifier.Identifier;

/**
 * @author hal.hildebrand
 */
public class KeyStateImpl implements KeyState {

    private final KeyState_ state;

    public KeyStateImpl(byte[] content) throws InvalidProtocolBufferException {
        this.state = KeyState_.parseFrom(content);
    }

    public KeyStateImpl(KeyState_ state) {
        this.state = state;
    }

    public static KeyState initialState(InceptionEvent event, DigestAlgorithm digestAlgo) {
        var delegatingPrefix =
        event instanceof DelegatedInceptionEvent ? ((DelegatedInceptionEvent) event).getDelegatingPrefix() : null;

        return newKeyState(event.getIdentifier(), event.getSigningThreshold(), event.getKeys(),
                           event.getNextKeysDigest().orElse(null), event.getWitnessThreshold(), event.getWitnesses(),
                           event.getConfigurationTraits(), event, event, delegatingPrefix,
                           digestAlgo.digest(event.getBytes()));
    }

    public static KeyState newKeyState(Identifier identifier, SigningThreshold signingThreshold, List<PublicKey> keys,
                                       Digest nextKeyConfiguration, int witnessThreshold,
                                       List<BasicIdentifier> witnesses, Set<ConfigurationTrait> configurationTraits,
                                       KeyEvent event, EstablishmentEvent lastEstablishmentEvent,
                                       Identifier delegatingPrefix, Digest digest) {
        final var builder = KeyState_.newBuilder();
        return new KeyStateImpl(builder.addAllKeys(keys.stream().map(pk -> bs(pk)).collect(Collectors.toList()))
                                       .setNextKeyConfigurationDigest(
                                       nextKeyConfiguration == null ? Digest.NONE.toDigeste()
                                                                    : nextKeyConfiguration.toDigeste())
                                       .setSigningThreshold(toSigningThreshold(signingThreshold))
                                       .addAllWitnesses(
                                       witnesses.stream().map(e -> e.toIdent()).collect(Collectors.toList()))
                                       .setWitnessThreshold(witnessThreshold)
                                       .setDigest(digest.toDigeste())
                                       .addAllConfigurationTraits(
                                       configurationTraits.stream().map(e -> e.name()).collect(Collectors.toList()))
                                       .setCoordinates(event.getCoordinates().toEventCoords())
                                       .setDelegatingIdentifier(
                                       delegatingPrefix == null ? Identifier.NONE_IDENT : delegatingPrefix.toIdent())

                                       .setLastEstablishmentEvent(
                                       lastEstablishmentEvent.getCoordinates().toEventCoords())
                                       .setLastEvent(event.getPrevious().toEventCoords())

                                       .build());
    }

    @Override
    public Set<ConfigurationTrait> configurationTraits() {
        return state.getConfigurationTraitsList()
                    .stream()
                    .map(s -> ConfigurationTrait.valueOf(s))
                    .collect(Collectors.toSet());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof KeyStateImpl)) {
            return false;
        }
        KeyStateImpl other = (KeyStateImpl) obj;
        return Objects.equals(state, other.state);
    }

    @Override
    public byte[] getBytes() {
        return state.toByteString().toByteArray();
    }

    @Override
    public EventCoordinates getCoordinates() {
        return EventCoordinates.from(state.getCoordinates());
    }

    @Override
    public Optional<Identifier> getDelegatingIdentifier() {
        return Optional.of(identifier(state.getDelegatingIdentifier()));
    }

    @Override
    public Digest getDigest() {
        return digest(state.getDigest());
    }

    @Override
    public List<PublicKey> getKeys() {
        return state.getKeysList().stream().map(s -> publicKey(s)).collect(Collectors.toList());
    }

    @Override
    public EventCoordinates getLastEstablishmentEvent() {
        return EventCoordinates.from(state.getLastEstablishmentEvent());
    }

    @Override
    public EventCoordinates getLastEvent() {
        return EventCoordinates.from(state.getLastEvent());
    }

    @Override
    public Optional<Digest> getNextKeyConfigurationDigest() {
        return state.hasNextKeyConfigurationDigest() ? Optional.of(digest(state.getNextKeyConfigurationDigest()))
                                                     : Optional.empty();
    }

    @Override
    public SigningThreshold getSigningThreshold() {
        return ProtobufEventFactory.toSigningThreshold(state.getSigningThreshold());
    }

    @Override
    public int getWitnessThreshold() {
        return state.getWitnessThreshold();
    }

    @Override
    public List<BasicIdentifier> getWitnesses() {
        return state.getWitnessesList()
                    .stream()
                    .map(s -> identifier(s))
                    .filter(i -> i instanceof BasicIdentifier)
                    .filter(i -> i != null)
                    .map(i -> (BasicIdentifier) i)
                    .collect(Collectors.toList());
    }

    @Override
    public int hashCode() {
        return Objects.hash(state);
    }

    @Override
    public KeyState_ toKeyState_() {
        return state;
    }

    @Override
    public String toString() {
        return "KeyStateImpl\n" + state + "\n";
    }

}
