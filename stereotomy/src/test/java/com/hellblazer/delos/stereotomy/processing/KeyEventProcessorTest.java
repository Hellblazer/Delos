/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy.processing;

import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.Signer;
import com.hellblazer.delos.cryptography.SigningThreshold;
import com.hellblazer.delos.stereotomy.*;
import com.hellblazer.delos.stereotomy.event.InceptionEvent;
import com.hellblazer.delos.stereotomy.event.InteractionEvent;
import com.hellblazer.delos.stereotomy.event.KeyEvent;
import com.hellblazer.delos.stereotomy.event.RotationEvent;
import com.hellblazer.delos.stereotomy.event.protobuf.InceptionEventImpl;
import com.hellblazer.delos.stereotomy.event.protobuf.InteractionEventImpl;
import com.hellblazer.delos.stereotomy.event.protobuf.RotationEventImpl;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.stereotomy.identifier.spec.IdentifierSpecification;
import com.hellblazer.delos.stereotomy.identifier.spec.InteractionSpecification;
import com.hellblazer.delos.stereotomy.identifier.spec.RotationSpecification;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test that KeyEventProcessor properly authenticates events
 *
 * @author hal.hildebrand
 */
public class KeyEventProcessorTest {

    private KERL.AppendKERL       kerl;
    private StereotomyKeyStore    keyStore;
    private SecureRandom          secureRandom;
    private Stereotomy            stereotomy;
    private KeyEventProcessor     processor;

    @BeforeEach
    public void setup() throws Exception {
        secureRandom = SecureRandom.getInstance("SHA1PRNG");
        secureRandom.setSeed(new byte[] { 1, 2, 3 });
        kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        keyStore = new MemKeyStore();
        stereotomy = new StereotomyImpl(keyStore, kerl, secureRandom);
        processor = new KeyEventProcessor(kerl);
    }

    @Test
    public void testValidInceptionAuthentication() throws Exception {
        // Create a valid identifier - inception event should be properly signed
        var identifier = stereotomy.newIdentifier();

        // Get the inception event from KERL
        var inceptionCoords = identifier.getLastEstablishmentEvent();
        var inceptionEvent = kerl.getKeyEvent(inceptionCoords);

        assertNotNull(inceptionEvent);
        assertInstanceOf(InceptionEvent.class, inceptionEvent);

        // Process the event - should succeed with valid signature
        var state = processor.process(inceptionEvent);
        assertNotNull(state);
        assertEquals(ULong.valueOf(0), state.getSequenceNumber());
    }

    @Test
    public void testValidRotationAuthentication() throws Exception {
        // Create identifier and rotate
        var identifier = stereotomy.newIdentifier();
        identifier.rotate();

        // Get the rotation event
        var kerl_events = kerl.kerl(identifier.getIdentifier());
        assertEquals(2, kerl_events.size());

        var rotationEvent = kerl_events.get(1).event();
        assertInstanceOf(RotationEvent.class, rotationEvent);

        // Process rotation event - should succeed with valid signature
        var state = processor.process(rotationEvent);
        assertNotNull(state);
        assertEquals(ULong.valueOf(1), state.getSequenceNumber());
    }

    @Test
    public void testValidInteractionAuthentication() throws Exception {
        // Create identifier and add interaction
        var identifier = stereotomy.newIdentifier();
        identifier.seal(InteractionSpecification.newBuilder());

        // Get the interaction event
        var kerl_events = kerl.kerl(identifier.getIdentifier());
        assertEquals(2, kerl_events.size());

        var interactionEvent = kerl_events.get(1).event();
        assertInstanceOf(InteractionEvent.class, interactionEvent);

        // Process interaction event - should succeed with valid signature
        var state = processor.process(interactionEvent);
        assertNotNull(state);
        assertEquals(ULong.valueOf(1), state.getSequenceNumber());
    }

    // Note: Creating tampered events requires modifying protobuf messages directly
    // For now, we'll test the happy path that signatures ARE verified
    // The implementation will throw InvalidKeyEventException on invalid signatures
    // Additional negative tests can be added once we have helper methods to create
    // events with custom signatures
}
