/*
 * Copyright (c) 2022, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy.event;

import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.KeyState;
import com.hellblazer.delos.stereotomy.event.proto.KeyStateWithEndorsementsAndValidations_;
import com.hellblazer.delos.stereotomy.event.proto.Validation_;
import com.hellblazer.delos.stereotomy.event.protobuf.KeyStateImpl;

import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * @author hal.hildebrand
 */
public record KeyStateWithEndorsementsAndValidations(KeyState state, TreeMap<Integer, JohnHancock> endorsements,
                                                     Map<EventCoordinates, JohnHancock> validations) {

    public static KeyStateWithEndorsementsAndValidations create(KeyState state, Map<Integer, JohnHancock> endorsements,
                                                                Map<EventCoordinates, JohnHancock> validations) {
        return new KeyStateWithEndorsementsAndValidations(state, new TreeMap<>(endorsements), validations);

    }

    public static KeyStateWithEndorsementsAndValidations from(KeyStateWithEndorsementsAndValidations_ ks) {
        return new KeyStateWithEndorsementsAndValidations(new KeyStateImpl(ks.getState()), new TreeMap<>(
        ks.getEndorsementsMap()
          .entrySet()
          .stream()
          .collect(Collectors.toMap(Map.Entry::getKey, e -> JohnHancock.from(e.getValue())))), ks.getValidationsList()
                                                                                                 .stream()
                                                                                                 .collect(
                                                                                                 Collectors.toMap(
                                                                                                 e -> EventCoordinates.from(
                                                                                                 e.getValidator()),
                                                                                                 e -> JohnHancock.from(
                                                                                                 e.getSignature()))));
    }

    public KeyStateWithEndorsementsAndValidations_ toKS() {

        return KeyStateWithEndorsementsAndValidations_.newBuilder()
                                                      .setState(state.toKeyState_())
                                                      .addAllValidations(validations.entrySet()
                                                                                    .stream()
                                                                                    .map(e -> Validation_.newBuilder()
                                                                                                         .setValidator(
                                                                                                         e.getKey()
                                                                                                          .toEventCoords())
                                                                                                         .setSignature(
                                                                                                         e.getValue()
                                                                                                          .toSig())
                                                                                                         .build())
                                                                                    .toList())
                                                      .putAllEndorsements(endorsements.entrySet()
                                                                                      .stream()
                                                                                      .collect(Collectors.toMap(
                                                                                      Map.Entry::getKey,
                                                                                      e -> e.getValue().toSig())))
                                                      .build();
    }

}
