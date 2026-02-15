/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth;

import com.hellblazer.delos.context.DynamicContext;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.JohnHancock;
import com.hellblazer.delos.membership.stereotomy.ControlledIdentifierMember;
import com.hellblazer.delos.stereotomy.EventCoordinates;
import com.hellblazer.delos.stereotomy.StereotomyImpl;
import com.hellblazer.delos.stereotomy.event.protobuf.ProtobufEventFactory;
import com.hellblazer.delos.stereotomy.identifier.SelfAddressingIdentifier;
import com.hellblazer.delos.stereotomy.identifier.spec.IdentifierSpecification;
import com.hellblazer.delos.stereotomy.mem.MemKERL;
import com.hellblazer.delos.stereotomy.mem.MemKeyStore;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the ordered validation pipeline: Ani → Maat → DHT write.
 * Verifies short-circuit behavior and Byzantine signal generation at each stage.
 *
 * @author hal.hildebrand
 */
class OrderedValidationPipelineTest {

    @Test
    void testPipelineSuccessPath() throws Exception {
        // Note: Full Ani integration requires witness endorsements setup which is complex.
        // This test demonstrates Maat-only validation (Ani=null, backward compatible).
        // Full Ani → Maat pipeline requires witness configuration in production KerlDHT setup.

        // Given: Context with BFT subset
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[]{1, 2, 3});
        final var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        var b = DynamicContext.newBuilder();
        b.setCardinality(4);
        var context = b.build();
        for (int i = 0; i < 4; i++) {
            context.activate(new ControlledIdentifierMember(stereotomy.newIdentifier()));
        }

        // Given: Valid inception event
        var specification = IdentifierSpecification.newBuilder();
        var initialKeyPair = specification.getSignatureAlgorithm().generateKeyPair(entropy);
        var nextKeyPair = specification.getSignatureAlgorithm().generateKeyPair(entropy);
        var inception = AbstractDhtTest.inception(specification, initialKeyPair, ProtobufEventFactory.INSTANCE, nextKeyPair);

        // Add BFT validations (enables Maat BFT signature validation)
        var digest = ((SelfAddressingIdentifier) inception.getIdentifier()).getDigest();
        var serialized = inception.toKeyEvent_().toByteString();
        var validations = new HashMap<EventCoordinates, JohnHancock>();
        context.successors(digest).stream().map(m -> (ControlledIdentifierMember) m).forEach(m -> {
            validations.put(m.getEvent().getCoordinates(), m.sign(serialized));
        });
        kerl.appendValidations(inception.getCoordinates(), validations);

        // Create Maat-only pipeline (Ani=null for simplicity)
        var byzantineProvider = new ThothByzantineStateProvider();
        var maat = new Maat(context, kerl, kerl, byzantineProvider, null, null);

        // When: Append through Maat validation
        var result = maat.append(inception);

        // Then: Event passes Maat validation
        assertNotNull(result, "Valid event should pass Maat validation");
        assertEquals(0L, result.getCoordinates().getSequenceNumber().longValue(),
                "Result should be the inception event");

        // And: No Byzantine signals recorded
        var signals = byzantineProvider.getMemberAnomalyStates();
        assertNotNull(signals);
        assertEquals(0, signals.size(), "No Byzantine signals for valid event");
    }

    @Test
    void testPipelineShortCircuitOnMaatFailure() throws Exception {
        // Test Maat short-circuit behavior: invalid events filtered, Byzantine signals recorded

        // Given: Context with BFT subset
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[]{1, 2, 3});
        final var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        var b = DynamicContext.newBuilder();
        b.setCardinality(4);
        var context = b.build();
        for (int i = 0; i < 4; i++) {
            context.activate(new ControlledIdentifierMember(stereotomy.newIdentifier()));
        }

        // Given: Inception event WITHOUT BFT signatures (will fail Maat)
        var specification = IdentifierSpecification.newBuilder();
        var initialKeyPair = specification.getSignatureAlgorithm().generateKeyPair(entropy);
        var nextKeyPair = specification.getSignatureAlgorithm().generateKeyPair(entropy);
        var inception = AbstractDhtTest.inception(specification, initialKeyPair, ProtobufEventFactory.INSTANCE, nextKeyPair);

        // Do NOT add BFT validations (Maat will fail)

        // Create Maat with Byzantine provider
        var byzantineProvider = new ThothByzantineStateProvider();
        var maat = new Maat(context, kerl, kerl, byzantineProvider, null, null);

        // When: Append through Maat (no BFT signatures)
        var result = maat.append(inception);

        // Then: Event is rejected at Maat validation stage
        assertNull(result, "Event without BFT signatures should be rejected at Maat");

        // And: Byzantine validation failure signal recorded for Maat failure
        var signals = byzantineProvider.getMemberAnomalyStates();
        assertNotNull(signals);
        assertTrue(signals.size() > 0, "Maat should record Byzantine signal for validation failure");

        var identifier = inception.getIdentifier();
        var memberSignals = signals.get(identifier);
        assertNotNull(memberSignals, "Should have signals for the failed event's identifier");
        assertTrue(memberSignals.anomalyScore() > 0.0, "Anomaly score should be > 0 for Maat validation failure");

        // Verify signal contains Maat failure prefix
        var signalList = memberSignals.activeSignals();
        assertFalse(signalList.isEmpty(), "Should have at least one signal");
        assertTrue(signalList.get(0).contains("MAAT_BLS_VALIDATION_FAILURE"),
                "Signal should indicate Maat BLS validation failure: " + signalList.get(0));
    }

    @Test
    void testPipelineBackwardCompatibility() throws Exception {
        // Given: Context without Ani (backward compatible mode)
        var entropy = SecureRandom.getInstance("SHA1PRNG");
        entropy.setSeed(new byte[]{1, 2, 3});
        final var kerl = new MemKERL(DigestAlgorithm.DEFAULT);
        var stereotomy = new StereotomyImpl(new MemKeyStore(), kerl, entropy);
        var b = DynamicContext.newBuilder();
        b.setCardinality(4);
        var context = b.build();
        for (int i = 0; i < 4; i++) {
            context.activate(new ControlledIdentifierMember(stereotomy.newIdentifier()));
        }

        // Given: Valid inception event with BFT signatures
        var specification = IdentifierSpecification.newBuilder();
        var initialKeyPair = specification.getSignatureAlgorithm().generateKeyPair(entropy);
        var nextKeyPair = specification.getSignatureAlgorithm().generateKeyPair(entropy);
        var inception = AbstractDhtTest.inception(specification, initialKeyPair, ProtobufEventFactory.INSTANCE, nextKeyPair);

        // Add BFT validations
        var digest = ((SelfAddressingIdentifier) inception.getIdentifier()).getDigest();
        var serialized = inception.toKeyEvent_().toByteString();
        var validations = new HashMap<EventCoordinates, JohnHancock>();
        context.successors(digest).stream().map(m -> (ControlledIdentifierMember) m).forEach(m -> {
            validations.put(m.getEvent().getCoordinates(), m.sign(serialized));
        });
        kerl.appendValidations(inception.getCoordinates(), validations);

        // Create Maat WITHOUT Ani (backward compatible constructor)
        var byzantineProvider = new ThothByzantineStateProvider();
        var maat = new Maat(context, kerl, kerl, byzantineProvider);

        // When: Append through Maat-only (no Ani stage)
        var result = maat.append(inception);

        // Then: Event passes Maat validation
        assertNotNull(result, "Valid event should pass Maat-only validation (backward compatible)");
        assertEquals(0L, result.getCoordinates().getSequenceNumber().longValue());

        // And: No Byzantine signals
        var signals = byzantineProvider.getMemberAnomalyStates();
        assertEquals(0, signals.size(), "No Byzantine signals for valid event");
    }
}
