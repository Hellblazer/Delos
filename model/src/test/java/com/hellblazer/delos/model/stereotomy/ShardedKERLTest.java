/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.model.stereotomy;

import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.cryptography.SignatureAlgorithm;
import com.hellblazer.delos.cryptography.SigningThreshold;
import com.hellblazer.delos.cryptography.SigningThreshold.Unweighted;
import com.hellblazer.delos.model.Domain;
import com.hellblazer.delos.state.Emulator;
import com.hellblazer.delos.stereotomy.*;
import com.hellblazer.delos.stereotomy.event.EstablishmentEvent;
import com.hellblazer.delos.stereotomy.event.KeyEvent;
import com.hellblazer.delos.stereotomy.event.Seal;
import com.hellblazer.delos.stereotomy.event.Seal.DigestSeal;
import com.hellblazer.delos.stereotomy.identifier.Identifier;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.stereotomy.identifier.spec.IdentifierSpecification;
import com.hellblazer.delos.stereotomy.identifier.spec.InteractionSpecification;
import com.hellblazer.delos.stereotomy.identifier.spec.KeyConfigurationDigester;
import com.hellblazer.delos.stereotomy.identifier.spec.RotationSpecification;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import com.hellblazer.delos.utils.Hex;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.joou.ULong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.hellblazer.delos.stereotomy.schema.tables.Validation.VALIDATION;
import static org.junit.jupiter.api.Assertions.*;

/**
 * @author hal.hildebrand
 */
public class ShardedKERLTest {
    private SecureRandom secureRandom;

    @BeforeEach
    public void before() throws Exception {
        secureRandom = SecureRandom.getInstance("SHA1PRNG");
        secureRandom.setSeed(new byte[] { 0 });
    }

    @Test
    public void delegated() throws Exception {
        Duration timeout = Duration.ofSeconds(1000);
        Digest fixedBase = DigestAlgorithm.DEFAULT.getOrigin().prefix(0L);
        Emulator emmy = new Emulator(fixedBase);
        emmy.start(Domain.boostrapMigration());

        ShardedKERL kerl = new ShardedKERL(emmy.newConnector(), emmy.getMutator(), timeout, DigestAlgorithm.DEFAULT);

        var ks = new MemKeyStore();
        Stereotomy controller = new StereotomyImpl(ks, kerl, secureRandom);

        ControlledIdentifier<? extends Identifier> base = controller.newIdentifier();

        var opti2 = base.newIdentifier(IdentifierSpecification.newBuilder());
        ControlledIdentifier<? extends Identifier> identifier = opti2;

        // identifier
        assertInstanceOf(SelfAddressingIdentifier.class, identifier.getIdentifier());
        var sap = (SelfAddressingIdentifier) identifier.getIdentifier();
        assertEquals(DigestAlgorithm.DEFAULT, sap.getDigest().getAlgorithm());
        // Updated hash value for deterministic Emulator with fixed base
        assertEquals("7afeaeec412e4f1b4c6686931e00da3f585225f1fc1bf444d0479351814c13a9",
                     Hex.hex(sap.getDigest().getBytes()));

        assertEquals(1, ((Unweighted) identifier.getSigningThreshold()).getThreshold());

        // keys
        assertEquals(1, identifier.getKeys().size());
        assertNotNull(identifier.getKeys().get(0));

        EstablishmentEvent lastEstablishmentEvent = (EstablishmentEvent) kerl.getKeyEvent(
        identifier.getLastEstablishmentEvent());
        assertEquals(identifier.getKeys().get(0), lastEstablishmentEvent.getKeys().get(0));

        var keyCoordinates = KeyCoordinates.of(lastEstablishmentEvent, 0);
        var keyStoreKeyPair = ks.getKey(keyCoordinates);
        assertTrue(keyStoreKeyPair.isPresent());
        assertEquals(keyStoreKeyPair.get().getPublic(), identifier.getKeys().get(0));

        // nextKeys
        assertTrue(identifier.getNextKeyConfigurationDigest().isPresent());
        var keyStoreNextKeyPair = ks.getNextKey(keyCoordinates);
        assertTrue(keyStoreNextKeyPair.isPresent());
        var expectedNextKeys = KeyConfigurationDigester.digest(SigningThreshold.unweighted(1),
                                                               List.of(keyStoreNextKeyPair.get().getPublic()),
                                                               identifier.getNextKeyConfigurationDigest()
                                                                         .get()
                                                                         .getAlgorithm());
        assertEquals(expectedNextKeys, identifier.getNextKeyConfigurationDigest().get());

        // witnesses
        assertEquals(0, identifier.getWitnessThreshold());
        assertEquals(0, identifier.getWitnesses().size());

        // config
        assertEquals(0, identifier.configurationTraits().size());

        // lastEstablishmentEvent
        assertEquals(identifier.getIdentifier(), lastEstablishmentEvent.getIdentifier());
        assertEquals(ULong.valueOf(0), lastEstablishmentEvent.getSequenceNumber());
        assertEquals(lastEstablishmentEvent.hash(DigestAlgorithm.DEFAULT), identifier.getDigest());

        // lastEvent
        assertNull(kerl.getKeyEvent(identifier.getLastEvent()));

        // delegation
        assertTrue(identifier.getDelegatingIdentifier().isPresent());
        assertTrue(identifier.isDelegated());

        var digest = DigestAlgorithm.BLAKE3_256.digest("digest seal".getBytes());
        var event = EventCoordinates.of(kerl.getKeyEvent(identifier.getLastEstablishmentEvent()));
        var seals = List.of(DigestSeal.construct(digest), DigestSeal.construct(digest), Seal.construct(event));

        identifier.rotate();
        identifier.seal(InteractionSpecification.newBuilder());
        identifier.rotate(RotationSpecification.newBuilder().addAllSeals(seals));
        identifier.seal(InteractionSpecification.newBuilder().addAllSeals(seals));
    }

    @Test
    public void appendValidationsPersists() throws Exception {
        Duration timeout = Duration.ofSeconds(1000);
        Emulator emmy = new Emulator();
        emmy.start(Domain.boostrapMigration());

        ShardedKERL kerl = new ShardedKERL(emmy.newConnector(), emmy.getMutator(), timeout, DigestAlgorithm.DEFAULT);

        var ks = new MemKeyStore();
        Stereotomy controller = new StereotomyImpl(ks, kerl, secureRandom);
        ControlledIdentifier<? extends Identifier> identifier = controller.newIdentifier();

        // Get inception event coordinates
        EstablishmentEvent inceptionEvent = (EstablishmentEvent) kerl.getKeyEvent(
        identifier.getLastEstablishmentEvent());
        EventCoordinates coordinates = EventCoordinates.of(inceptionEvent);

        // Build a minimal validation: a single null-signature keyed by the same coordinates
        JohnHancock sig = JohnHancock.nullSignature(SignatureAlgorithm.DEFAULT);
        Map<EventCoordinates, JohnHancock> validations = Map.of(coordinates, sig);

        // Before: no validations
        try (var conn = emmy.newConnector()) {
            var dsl = DSL.using(conn, SQLDialect.H2);
            int before = dsl.selectCount().from(VALIDATION).fetchOne(0, int.class);
            assertEquals(0, before, "No validations should exist before appendValidations");
        }

        // Act: call appendValidations — must not throw and must not be a null stub
        kerl.appendValidations(coordinates, validations);

        // After: validation record persisted
        try (var conn = emmy.newConnector()) {
            var dsl = DSL.using(conn, SQLDialect.H2);
            int after = dsl.selectCount().from(VALIDATION).fetchOne(0, int.class);
            assertEquals(1, after, "appendValidations must persist validation records");
        }
    }

    @Test
    public void appendValidationsEmptyIsNoOp() throws Exception {
        Duration timeout = Duration.ofSeconds(1000);
        Emulator emmy = new Emulator();
        emmy.start(Domain.boostrapMigration());

        ShardedKERL kerl = new ShardedKERL(emmy.newConnector(), emmy.getMutator(), timeout, DigestAlgorithm.DEFAULT);

        var ks = new MemKeyStore();
        Stereotomy controller = new StereotomyImpl(ks, kerl, secureRandom);
        ControlledIdentifier<? extends Identifier> identifier = controller.newIdentifier();

        EstablishmentEvent inceptionEvent = (EstablishmentEvent) kerl.getKeyEvent(
        identifier.getLastEstablishmentEvent());
        EventCoordinates coordinates = EventCoordinates.of(inceptionEvent);

        // Empty map: should complete without error (UniKERL.appendValidations returns early)
        assertDoesNotThrow(() -> kerl.appendValidations(coordinates, Map.of()),
                           "appendValidations with empty map must not throw");

        try (var conn = emmy.newConnector()) {
            var dsl = DSL.using(conn, SQLDialect.H2);
            int count = dsl.selectCount().from(VALIDATION).fetchOne(0, int.class);
            assertEquals(0, count, "Empty validations map must not insert any records");
        }
    }

    @Test
    public void direct() throws Exception {
        Duration timeout = Duration.ofSeconds(1);
        Emulator emmy = new Emulator();
        emmy.start(Domain.boostrapMigration());

        ShardedKERL kerl = new ShardedKERL(emmy.newConnector(), emmy.getMutator(), timeout, DigestAlgorithm.DEFAULT);

        Stereotomy controller = new StereotomyImpl(new MemKeyStore(), kerl, secureRandom);

        var i = controller.newIdentifier();

        var digest = DigestAlgorithm.BLAKE3_256.digest("digest seal".getBytes());
        var event = EventCoordinates.of(kerl.getKeyEvent(i.getLastEstablishmentEvent()));
        var seals = List.of(DigestSeal.construct(digest), DigestSeal.construct(digest), Seal.construct(event));

        i.rotate();
        i.seal(InteractionSpecification.newBuilder());
        i.rotate(RotationSpecification.newBuilder().addAllSeals(seals));
        i.seal(InteractionSpecification.newBuilder().addAllSeals(seals));
        i.rotate();
        i.rotate();
        var opti = kerl.kerl(i.getIdentifier());
        assertNotNull(opti);
        assertNotNull(opti);
        var iKerl = opti;
        assertEquals(7, iKerl.size());
        assertEquals(KeyEvent.INCEPTION_TYPE, iKerl.get(0).event().getIlk());
        assertEquals(KeyEvent.ROTATION_TYPE, iKerl.get(1).event().getIlk());
        assertEquals(KeyEvent.INTERACTION_TYPE, iKerl.get(2).event().getIlk());
        assertEquals(KeyEvent.ROTATION_TYPE, iKerl.get(3).event().getIlk());
        assertEquals(KeyEvent.INTERACTION_TYPE, iKerl.get(4).event().getIlk());
        assertEquals(KeyEvent.ROTATION_TYPE, iKerl.get(5).event().getIlk());
        assertEquals(KeyEvent.ROTATION_TYPE, iKerl.get(6).event().getIlk());

    }
}
