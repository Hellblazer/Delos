/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy.processing;

import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.Verifier.DefaultVerifier;
import com.hellblazer.delos.stereotomy.KERL;
import com.hellblazer.delos.stereotomy.KeyState;
import com.hellblazer.delos.stereotomy.event.AttachmentEvent;
import com.hellblazer.delos.stereotomy.event.AttachmentEvent.Attachment;
import com.hellblazer.delos.stereotomy.event.EstablishmentEvent;
import com.hellblazer.delos.stereotomy.event.InceptionEvent;
import com.hellblazer.delos.stereotomy.event.KeyEvent;
import com.hellblazer.delos.stereotomy.event.Seal;
import org.joou.ULong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * @author hal.hildebrand
 */
public class KeyEventProcessor implements Validator, KeyEventVerifier {
    private static final Logger log = LoggerFactory.getLogger(KeyEventProcessor.class);

    private final KERL kerl;
    private final BiFunction<KeyState, KeyEvent, KeyState> keyStateProcessor;

    public KeyEventProcessor(KERL kerl) {
        this(kerl, new KeyStateProcessor(kerl));
    }

    public KeyEventProcessor(KERL kerl, BiFunction<KeyState, KeyEvent, KeyState> keyStateProcessor) {
        this.kerl = kerl;
        this.keyStateProcessor = keyStateProcessor;
    }

    public Attachment process(AttachmentEvent attachmentEvent) throws AttachmentEventProcessingException {
        KeyEvent event;
        event = kerl.getKeyEvent(attachmentEvent.coordinates());
        if (event == null) {
            throw new MissingAttachmentEventException(attachmentEvent, attachmentEvent.coordinates());
        }
        var state = kerl.getKeyState(attachmentEvent.coordinates());
        if (state == null) {
            throw new MissingAttachmentEventException(attachmentEvent, attachmentEvent.coordinates());
        }
        return verify(state, event, attachmentEvent.attachments());
    }

    public KeyState process(KeyEvent event) throws KeyEventProcessingException {
        KeyState previousState = null;

        if (!(event instanceof InceptionEvent)) {
            previousState = kerl.getKeyState(event.getPrevious());
            if (previousState == null) {
                throw new MissingEventException(event, event.getPrevious());
            }
        }

        return process(previousState, event);
    }

    public KeyState process(KeyState previousState, KeyEvent event) throws KeyEventProcessingException {

        // Authenticate event signature before processing
        // NOTE: Inception events are authenticated through identifier validation, not signature authentication
        if (!(event instanceof InceptionEvent)) {
            authenticateEvent(previousState, event);
        }

        // Validate sequence number continuity
        validateSequenceNumber(previousState, event);

        validateKeyEventData(previousState, event, kerl);

        KeyState newState = keyStateProcessor.apply(previousState, event);

        return newState;
    }

    /**
     * Authenticate that the event signature is valid for the given key state.
     * For inception events, verifies against the keys in the event itself.
     * For other events, verifies against the previous/current state keys.
     *
     * The signature is verified against the event specification (the signed data),
     * not the complete event which includes the signature itself.
     *
     * @param state the key state to verify against (null for inception)
     * @param event the event to authenticate
     * @throws InvalidKeyEventException if signature is missing or invalid
     */
    private void authenticateEvent(KeyState state, KeyEvent event) throws InvalidKeyEventException {
        var signature = event.getAuthentication();
        if (signature == null) {
            log.error("Event missing signature authentication for {}", event.getCoordinates());
            throw new InvalidKeyEventException("Event missing signature authentication for " + event.getCoordinates());
        }

        // Get the signed data (the specification, not the complete event with signature)
        var signedData = getSignedData(event);

        // For inception events, verify against the keys in the event itself
        // For other events, verify against the previous/current state
        var keys = (event instanceof EstablishmentEvent ee) ? ee.getKeys() : state.getKeys();
        var threshold = (event instanceof EstablishmentEvent ee) ? ee.getSigningThreshold() : state.getSigningThreshold();

        var verifier = new DefaultVerifier(keys);
        var verified = verifier.verify(threshold, signature, signedData);

        if (!verified) {
            log.error("Event signature verification failed for {}", event.getCoordinates());
            throw new InvalidKeyEventException("Event signature verification failed for " + event.getCoordinates());
        }

        log.trace("Event signature verified for {}", event.getCoordinates());
    }

    /**
     * Get the signed data from an event. The event signature is over the specification,
     * not the complete event which includes the signature itself.
     *
     * @param event the event
     * @return the bytes that were signed
     */
    private byte[] getSignedData(KeyEvent event) {
        if (event instanceof InceptionEvent ie) {
            return ie.getInceptionStatement();
        } else if (event instanceof com.hellblazer.delos.stereotomy.event.protobuf.RotationEventImpl re) {
            return re.toRotationEvent_().getSpecification().toByteArray();
        } else if (event instanceof com.hellblazer.delos.stereotomy.event.protobuf.InteractionEventImpl ie) {
            return ie.toInteractionEvent_().getSpecification().toByteArray();
        } else {
            // Fallback for unknown event types - this should not happen
            log.warn("Unknown event type for signature verification: {}", event.getClass());
            return event.getBytes();
        }
    }

    /**
     * Validate that the event sequence number is exactly one more than the previous state.
     * Inception events are skipped as they are validated separately.
     *
     * @param previousState the previous key state (null for inception)
     * @param event the event to validate
     * @throws InvalidKeyEventException if sequence number is invalid
     */
    private void validateSequenceNumber(KeyState previousState, KeyEvent event) throws InvalidKeyEventException {
        if (event instanceof InceptionEvent) {
            // Inception events are validated in validateKeyEventData - must be 0
            return;
        }

        if (previousState == null) {
            throw new InvalidKeyEventException("Non-inception event requires previous state for sequence validation");
        }

        var expected = previousState.getSequenceNumber().add(ULong.valueOf(1));
        if (!event.getSequenceNumber().equals(expected)) {
            log.error("Invalid sequence number for {}: expected {}, got {}",
                     event.getCoordinates(), expected, event.getSequenceNumber());
            throw new InvalidKeyEventException(
                String.format("Invalid sequence number for %s: expected %s, got %s",
                             event.getCoordinates(), expected, event.getSequenceNumber())
            );
        }

        log.trace("Sequence number validated for {}: {}", event.getCoordinates(), event.getSequenceNumber());
    }

    private Attachment verify(KeyState state, KeyEvent event, Attachment attachments) {
        if (state.getWitnessThreshold() > 0 && !state.getWitnesses().isEmpty()) {
            var validEndorsements = verifyEndorsements(state, event, attachments.endorsements());
            return new Attachment() {
                @Override
                public Map<Integer, JohnHancock> endorsements() {
                    return validEndorsements;
                }

                @Override
                public List<Seal> seals() {
                    return attachments.seals();
                }
            };
        }
        return attachments;
    }
}
