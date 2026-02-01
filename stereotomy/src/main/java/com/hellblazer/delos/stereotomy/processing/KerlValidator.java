/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.stereotomy.processing;

import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.stereotomy.KERL;
import com.hellblazer.delos.stereotomy.KeyState;
import com.hellblazer.delos.stereotomy.event.EstablishmentEvent;
import com.hellblazer.delos.stereotomy.event.InceptionEvent;
import com.hellblazer.delos.stereotomy.event.KeyEvent;
import com.hellblazer.delos.stereotomy.event.proto.KERL_;
import com.hellblazer.delos.stereotomy.event.protobuf.ProtobufEventFactory;
import com.hellblazer.delos.utils.BbBackedInputStream;
import org.joou.ULong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.PublicKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unified KERL validation service that consolidates chain and event validation logic.
 * Provides a single source of truth for KERL validation across Gorgoneion, Thoth, and other components.
 *
 * @author hal.hildebrand
 */
public class KerlValidator {
    private static final Logger log = LoggerFactory.getLogger(KerlValidator.class);

    private final KeyEventProcessor processor;

    public KerlValidator(KERL kerl) {
        this.processor = new KeyEventProcessor(kerl);
    }

    public KerlValidator(KeyEventProcessor processor) {
        this.processor = processor;
    }

    /**
     * Validate a complete KERL chain.
     *
     * @param kerl the KERL protobuf containing the event chain
     * @return the final KeyState after validating all events
     * @throws KerlValidationException if validation fails
     */
    public KeyState validateChain(KERL_ kerl) throws KerlValidationException {
        // Step 1: Check KERL is not empty
        if (kerl.getEventsCount() == 0) {
            throw new KerlValidationException("Empty KERL");
        }

        // Step 2: Deserialize all events
        List<KeyEvent> events = deserializeEvents(kerl);

        // Step 3: Validate first event is InceptionEvent
        if (!(events.getFirst() instanceof InceptionEvent)) {
            throw new KerlValidationException("KERL must start with InceptionEvent");
        }

        // Step 4: Process each event sequentially
        KeyState currentState = null;
        for (int i = 0; i < events.size(); i++) {
            KeyEvent event = events.get(i);
            try {
                currentState = processor.process(event);

                // Validate sequence number progression
                if (!currentState.getSequenceNumber().equals(ULong.valueOf(i))) {
                    throw new KerlValidationException(
                        "Invalid sequence number at index " + i + ": expected " + i + " got "
                        + currentState.getSequenceNumber());
                }

                log.debug("Validated event {} in KERL chain: {}", i, event.getIlk());

            } catch (InvalidKeyEventException e) {
                log.warn("Invalid event at index {} in KERL: {}", i, e.getMessage());
                throw new KerlValidationException("Invalid event: " + e.getMessage(), e);

            } catch (MissingEventException e) {
                log.warn("Missing previous event for event {}: {}", i, e.getMessage());
                throw new KerlValidationException("Incomplete KERL chain: " + e.getMessage(), e);

            } catch (KerlValidationException e) {
                throw e;

            } catch (Exception e) {
                log.error("Unexpected error validating event {} in KERL", i, e);
                throw new KerlValidationException("Error validating KERL chain", e);
            }
        }

        // Step 5: Validate final event is EstablishmentEvent
        if (!(events.getLast() instanceof EstablishmentEvent)) {
            throw new KerlValidationException("KERL must end with EstablishmentEvent");
        }

        log.debug("Validated complete KERL chain with {} events", events.size());
        return currentState;
    }

    /**
     * Validate a single key event against the previous state.
     *
     * @param previousState the previous key state (null for inception)
     * @param event the event to validate
     * @return the new key state after validation
     * @throws KerlValidationException if validation fails
     */
    public KeyState validateEvent(KeyState previousState, KeyEvent event) throws KerlValidationException {
        try {
            return processor.process(previousState, event);
        } catch (KeyEventProcessingException e) {
            throw new KerlValidationException("Event validation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Validate witness endorsements for an event.
     *
     * @param state the key state containing witness configuration
     * @param event the event being endorsed
     * @param endorsements the witness endorsements (index -> signature)
     * @return true if endorsements meet the witness threshold
     */
    public boolean validateWitnessEndorsements(KeyState state, KeyEvent event,
                                                Map<Integer, JohnHancock> endorsements) {
        if (state.getWitnesses().isEmpty()) {
            return true; // No witnesses required
        }

        if (endorsements == null || endorsements.isEmpty()) {
            log.warn("No endorsements provided for event: {} with {} witnesses",
                    state.getCoordinates(), state.getWitnesses().size());
            return state.getWitnessThreshold() == 0;
        }

        // Build witness public key map
        SignatureAlgorithm algo = null;
        var witnesses = new HashMap<Integer, PublicKey>();
        for (int i = 0; i < state.getWitnesses().size(); i++) {
            PublicKey publicKey = state.getWitnesses().get(i).getPublicKey();
            witnesses.put(i, publicKey);
            if (algo == null) {
                algo = SignatureAlgorithm.lookup(publicKey);
            }
        }

        // Build signature array from endorsements
        byte[][] signatures = new byte[state.getWitnesses().size()][];
        int validCount = 0;
        for (var entry : endorsements.entrySet()) {
            int idx = entry.getKey();
            if (idx >= 0 && idx < signatures.length) {
                signatures[idx] = entry.getValue().getBytes()[0];
                validCount++;
            } else {
                log.warn("Endorsement index {} out of bounds (0-{}) for witnesses",
                        idx, signatures.length - 1);
            }
        }

        if (validCount == 0) {
            log.warn("No valid endorsements found for event: {}", state.getCoordinates());
            return state.getWitnessThreshold() == 0;
        }

        // Verify endorsements
        var aggregateSignature = new JohnHancock(algo, signatures, state.getSequenceNumber());
        var eventStream = BbBackedInputStream.aggregate(event.toKeyEvent_().toByteString());
        boolean verified = aggregateSignature.verify(state.getSigningThreshold(), witnesses, eventStream);

        log.trace("Witness endorsement validation: {} for: {}", verified, state.getCoordinates());
        return verified;
    }

    /**
     * Deserialize events from a KERL protobuf.
     */
    private List<KeyEvent> deserializeEvents(KERL_ kerl) throws KerlValidationException {
        List<KeyEvent> events = new ArrayList<>();
        for (int i = 0; i < kerl.getEventsCount(); i++) {
            try {
                var eventWithAttach = ProtobufEventFactory.from(kerl.getEvents(i));
                var event = eventWithAttach.event();
                if (event == null) {
                    throw new KerlValidationException("Event " + i + " failed to deserialize");
                }
                events.add(event);
            } catch (KerlValidationException e) {
                throw e;
            } catch (Exception e) {
                log.warn("Failed to deserialize event {} from KERL: {}", i, e.getMessage());
                throw new KerlValidationException("Invalid event at index " + i, e);
            }
        }
        return events;
    }
}
