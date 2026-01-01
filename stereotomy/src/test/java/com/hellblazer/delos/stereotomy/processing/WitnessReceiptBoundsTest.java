/*
 * Copyright (c) 2021, salesforce.com, inc.
 * All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 * For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
 */
package com.hellblazer.delos.stereotomy.processing;

import com.hellblazer.delos.cryptography.*;
import com.hellblazer.delos.stereotomy.*;
import com.hellblazer.delos.stereotomy.event.KeyEvent;
import com.hellblazer.delos.stereotomy.identifier.BasicIdentifier;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.spec.IdentifierSpecification;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test witness receipt bounds checking
 *
 * @author hal.hildebrand
 */
public class WitnessReceiptBoundsTest {

    private Stereotomy stereotomy;
    private ControlledIdentifier<Identifier> identifier;
    private List<BasicIdentifier> witnesses;
    private List<Signer> witnessSigners;
    private KERL.AppendKERL kerl;

    @BeforeEach
    public void setup() throws Exception {
        var secureRandom = SecureRandom.getInstance("SHA1PRNG");
        secureRandom.setSeed(new byte[] { 1, 2, 3 });
        kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var keyStore = new MemKeyStore();
        stereotomy = new StereotomyImpl(keyStore, kerl, secureRandom);

        // Create 3 witness identities
        witnessSigners = new ArrayList<>();
        witnesses = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            var keyPair = SignatureAlgorithm.ED_25519.generateKeyPair();
            witnessSigners.add(new Signer.SignerImpl(keyPair.getPrivate(), ULong.valueOf(0)));
            witnesses.add(new BasicIdentifier(keyPair.getPublic()));
        }

        // Create identifier with 3 witnesses and threshold of 2
        var spec = IdentifierSpecification.newBuilder()
                                          .setWitnessThreshold(2);
        for (var witness : witnesses) {
            spec.setWitness(witness);
        }

        identifier = stereotomy.newIdentifier(spec);
    }

    @Test
    public void testValidWitnessReceipts() throws Exception {
        // Get the inception event
        var inceptionEvent = kerl.getKeyEvent(identifier.getLastEstablishmentEvent());
        var state = kerl.getKeyState(identifier.getLastEstablishmentEvent());

        // Create valid receipts from witnesses 0 and 1 (meeting threshold of 2)
        var receipts = new HashMap<Integer, JohnHancock>();
        for (int i = 0; i < 2; i++) {
            var signature = witnessSigners.get(i).sign(inceptionEvent.getBytes());
            receipts.put(i, signature);
        }

        // Verify - should succeed
        var verifier = new KeyEventVerifier() {};
        var validReceipts = verifier.verifyEndorsements(state, inceptionEvent, receipts);

        assertEquals(2, validReceipts.size());
        assertTrue(validReceipts.containsKey(0));
        assertTrue(validReceipts.containsKey(1));
    }

    @Test
    public void testNegativeWitnessIndex() throws Exception {
        // Get the inception event
        var inceptionEvent = kerl.getKeyEvent(identifier.getLastEstablishmentEvent());
        var state = kerl.getKeyState(identifier.getLastEstablishmentEvent());

        // Create a receipt with negative index
        var receipts = new HashMap<Integer, JohnHancock>();
        var signature = witnessSigners.get(0).sign(inceptionEvent.getBytes());
        receipts.put(-1, signature);

        // Should throw InvalidWitnessReceiptException
        var verifier = new KeyEventVerifier() {};
        var exception = assertThrows(InvalidWitnessReceiptException.class, () -> {
            verifier.verifyEndorsements(state, inceptionEvent, receipts);
        });

        assertTrue(exception.getMessage().contains("Invalid witness receipt index -1"));
    }

    @Test
    public void testWitnessIndexTooHigh() throws Exception {
        // Get the inception event
        var inceptionEvent = kerl.getKeyEvent(identifier.getLastEstablishmentEvent());
        var state = kerl.getKeyState(identifier.getLastEstablishmentEvent());

        // Create a receipt with index >= witness count (3 witnesses, so index 3 is invalid)
        var receipts = new HashMap<Integer, JohnHancock>();
        var signature = witnessSigners.get(0).sign(inceptionEvent.getBytes());
        receipts.put(3, signature);

        // Should throw InvalidWitnessReceiptException
        var verifier = new KeyEventVerifier() {};
        var exception = assertThrows(InvalidWitnessReceiptException.class, () -> {
            verifier.verifyEndorsements(state, inceptionEvent, receipts);
        });

        assertTrue(exception.getMessage().contains("Invalid witness receipt index 3"));
        assertTrue(exception.getMessage().contains("witness count: 3"));
    }

    @Test
    public void testWitnessIndexAtBoundary() throws Exception {
        // Get the inception event
        var inceptionEvent = kerl.getKeyEvent(identifier.getLastEstablishmentEvent());
        var state = kerl.getKeyState(identifier.getLastEstablishmentEvent());

        // Test index way beyond witness count boundary
        var receipts = new HashMap<Integer, JohnHancock>();
        var signature = witnessSigners.get(0).sign(inceptionEvent.getBytes());
        receipts.put(10, signature); // Way beyond bounds

        // Should throw InvalidWitnessReceiptException
        var verifier = new KeyEventVerifier() {};
        assertThrows(InvalidWitnessReceiptException.class, () -> {
            verifier.verifyEndorsements(state, inceptionEvent, receipts);
        });
    }

    @Test
    public void testMixedValidAndInvalidIndices() throws Exception {
        // Get the inception event
        var inceptionEvent = kerl.getKeyEvent(identifier.getLastEstablishmentEvent());
        var state = kerl.getKeyState(identifier.getLastEstablishmentEvent());

        // Mix of valid and invalid indices - first invalid should cause exception
        var receipts = new HashMap<Integer, JohnHancock>();

        // Valid receipt from witness 0
        var sig0 = witnessSigners.get(0).sign(inceptionEvent.getBytes());
        receipts.put(0, sig0);

        // Valid receipt from witness 1
        var sig1 = witnessSigners.get(1).sign(inceptionEvent.getBytes());
        receipts.put(1, sig1);

        // Invalid receipt with out-of-bounds index
        var sigInvalid = witnessSigners.get(0).sign(inceptionEvent.getBytes());
        receipts.put(5, sigInvalid);

        // Should throw InvalidWitnessReceiptException
        var verifier = new KeyEventVerifier() {};
        assertThrows(InvalidWitnessReceiptException.class, () -> {
            verifier.verifyEndorsements(state, inceptionEvent, receipts);
        });
    }

    @Test
    public void testAllWitnessesValid() throws Exception {
        // Get the inception event
        var inceptionEvent = kerl.getKeyEvent(identifier.getLastEstablishmentEvent());
        var state = kerl.getKeyState(identifier.getLastEstablishmentEvent());

        // All 3 witnesses provide valid receipts
        var receipts = new HashMap<Integer, JohnHancock>();
        for (int i = 0; i < 3; i++) {
            var signature = witnessSigners.get(i).sign(inceptionEvent.getBytes());
            receipts.put(i, signature);
        }

        // Verify - should succeed with all 3
        var verifier = new KeyEventVerifier() {};
        var validReceipts = verifier.verifyEndorsements(state, inceptionEvent, receipts);

        assertEquals(3, validReceipts.size());
    }

    @Test
    public void testInsufficientValidReceipts() throws Exception {
        // Get the inception event
        var inceptionEvent = kerl.getKeyEvent(identifier.getLastEstablishmentEvent());
        var state = kerl.getKeyState(identifier.getLastEstablishmentEvent());

        // Only 1 valid receipt when threshold is 2
        var receipts = new HashMap<Integer, JohnHancock>();
        var signature = witnessSigners.get(0).sign(inceptionEvent.getBytes());
        receipts.put(0, signature);

        // Should throw UnmetWitnessThresholdException
        var verifier = new KeyEventVerifier() {};
        assertThrows(UnmetWitnessThresholdException.class, () -> {
            verifier.verifyEndorsements(state, inceptionEvent, receipts);
        });
    }

    @Test
    public void testZeroWitnesses() throws Exception {
        // Create identifier with no witnesses
        var spec = IdentifierSpecification.newBuilder();
        var noWitnessIdentifier = stereotomy.newIdentifier(spec);

        var inceptionEvent = kerl.getKeyEvent(noWitnessIdentifier.getLastEstablishmentEvent());
        var state = kerl.getKeyState(noWitnessIdentifier.getLastEstablishmentEvent());

        // Any receipt with any index should be invalid when there are no witnesses
        var receipts = new HashMap<Integer, JohnHancock>();
        var signature = witnessSigners.get(0).sign(inceptionEvent.getBytes());
        receipts.put(0, signature);

        var verifier = new KeyEventVerifier() {};
        assertThrows(InvalidWitnessReceiptException.class, () -> {
            verifier.verifyEndorsements(state, inceptionEvent, receipts);
        });
    }
}
