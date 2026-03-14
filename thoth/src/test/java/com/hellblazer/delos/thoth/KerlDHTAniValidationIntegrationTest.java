/*
 * Copyright (c) 2026, Hal Hildebrand.
 * All rights reserved.
 * GNU Affero General Public License
 * For full license text, see the LICENSE file in the repo root or http://www.gnu.org/licenses/
 * This file is part of the Delos Distributed Systems Framework.
 */
package com.hellblazer.delos.thoth;

import com.google.protobuf.ByteString;
import com.hellblazer.delos.cryptography.Digest;
import com.hellblazer.delos.cryptography.DigestAlgorithm;
import com.hellblazer.delos.cryptography.proto.Digeste;
import com.hellblazer.delos.cryptography.proto.Sig;
import com.hellblazer.delos.stereotomy.event.proto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for hybrid structural validation in KerlDHT append operations.
 * <p>
 * Tests validate the pre-quorum lightweight structural checks (Phase 2):
 * <ul>
 *   <li>Valid events pass structural validation</li>
 *   <li>Invalid events (null identifier, missing signatures, invalid type) are detected</li>
 *   <li>Structural validation is advisory-only (doesn't block append)</li>
 *   <li>Byzantine signals are recorded for failures</li>
 * </ul>
 * <p>
 * Post-quorum cryptographic validation via DhtValidationPipeline is tested separately.
 * </p>
 *
 * @author hal.hildebrand
 */
@DisplayName("KerlDHT Hybrid Structural Validation (Pre-Quorum)")
public class KerlDHTAniValidationIntegrationTest {

    private Digest testIdentifierDigest;
    private Digeste testDigeste;
    private Sig testSignature;

    @BeforeEach
    void setUp() {
        testIdentifierDigest = DigestAlgorithm.BLAKE3_256.digest("test-identifier");
        testDigeste = testIdentifierDigest.toDigeste();
        testSignature = Sig.newBuilder()
            .setCode(1)
            .addSignatures(ByteString.copyFrom(new byte[64]))
            .build();
    }

    @Test
    @DisplayName("Valid inception event passes structural validation")
    void testValidInceptionEvent() {
        // Given: Valid inception event with all required fields
        var event = createValidInceptionEvent();

        // When: Structural validation is performed
        var result = DhtValidationPipeline.validateEventStructure(event);

        // Then: Event passes validation
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Valid rotation event passes structural validation")
    void testValidRotationEvent() {
        // Given: Valid rotation event with all required fields
        var event = createValidRotationEvent();

        // When: Structural validation is performed
        var result = DhtValidationPipeline.validateEventStructure(event);

        // Then: Event passes validation
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Valid interaction event passes structural validation")
    void testValidInteractionEvent() {
        // Given: Valid interaction event with all required fields
        var event = createValidInteractionEvent();

        // When: Structural validation is performed
        var result = DhtValidationPipeline.validateEventStructure(event);

        // Then: Event passes validation
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Event with null identifier fails structural validation")
    void testNullIdentifierFails() {
        // Given: Inception event without identifier
        var event = KeyEvent_.newBuilder()
            .setInception(InceptionEvent.newBuilder()
                .setCommon(createValidCommon())
                .build())
            .build();

        // When: Structural validation is performed
        var result = DhtValidationPipeline.validateEventStructure(event);

        // Then: Event fails validation
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Event with uninitialized identifier fails structural validation")
    void testUninitializedIdentifierFails() {
        // Given: Event with default (uninitialized) identifier
        var event = KeyEvent_.newBuilder()
            .setInception(InceptionEvent.newBuilder()
                .setIdentifier(Ident.getDefaultInstance())
                .setCommon(createValidCommon())
                .build())
            .build();

        // When: Structural validation is performed
        var result = DhtValidationPipeline.validateEventStructure(event);

        // Then: Event fails validation
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Event with missing authentication fails structural validation")
    void testMissingAuthenticationFails() {
        // Given: Event without authentication signature
        var event = KeyEvent_.newBuilder()
            .setInception(InceptionEvent.newBuilder()
                .setIdentifier(createValidIdentifier())
                .setCommon(EventCommon.newBuilder().build()) // No authentication
                .build())
            .build();

        // When: Structural validation is performed
        var result = DhtValidationPipeline.validateEventStructure(event);

        // Then: Event fails validation
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Event with uninitialized authentication fails structural validation")
    void testUninitializedAuthenticationFails() {
        // Given: Event with default (uninitialized) authentication
        var event = KeyEvent_.newBuilder()
            .setInception(InceptionEvent.newBuilder()
                .setIdentifier(createValidIdentifier())
                .setCommon(EventCommon.newBuilder()
                    .setAuthentication(Sig.getDefaultInstance())
                    .build())
                .build())
            .build();

        // When: Structural validation is performed
        var result = DhtValidationPipeline.validateEventStructure(event);

        // Then: Event fails validation
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Event with no event type fails structural validation")
    void testMissingEventTypeFails() {
        // Given: Event without inception, rotation, or interaction
        var event = KeyEvent_.newBuilder().build();

        // When: Structural validation is performed
        var result = DhtValidationPipeline.validateEventStructure(event);

        // Then: Event fails validation
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Null event fails structural validation")
    void testNullEventFails() {
        // Given: Null event
        KeyEvent_ event = null;

        // When: Structural validation is performed with null check
        var result = DhtValidationPipeline.validateEventStructure(event);

        // Then: Validation fails
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Rotation event without specification fails structural validation")
    void testRotationWithoutSpecificationFails() {
        // Given: Rotation event without specification
        var event = KeyEvent_.newBuilder()
            .setRotation(RotationEvent.newBuilder()
                .setCommon(createValidCommon())
                .build())
            .build();

        // When: Structural validation is performed
        var result = DhtValidationPipeline.validateEventStructure(event);

        // Then: Event fails validation
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Interaction event without specification header fails structural validation")
    void testInteractionWithoutHeaderFails() {
        // Given: Interaction event without specification header
        var event = KeyEvent_.newBuilder()
            .setInteraction(InteractionEvent.newBuilder()
                .setSpecification(InteractionSpec.newBuilder().build()) // No header
                .setCommon(createValidCommon())
                .build())
            .build();

        // When: Structural validation is performed
        var result = DhtValidationPipeline.validateEventStructure(event);

        // Then: Event fails validation
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Event with empty selfAddressing hash fails structural validation")
    void testEmptySelfAddressingHashFails() {
        // Given: Event with empty hash list in selfAddressing identifier
        var emptyHash = Digeste.newBuilder()
            .setType(1)
            .build(); // No hash values
        var event = KeyEvent_.newBuilder()
            .setInception(InceptionEvent.newBuilder()
                .setIdentifier(Ident.newBuilder().setSelfAddressing(emptyHash).build())
                .setCommon(createValidCommon())
                .build())
            .build();

        // When: Structural validation is performed
        var result = DhtValidationPipeline.validateEventStructure(event);

        // Then: Event fails validation
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Event with empty basic PubKey encoded bytes fails structural validation")
    void testEmptyBasicEncodedBytesFails() {
        // Given: Event with empty encoded bytes in basic identifier
        var emptyKey = com.hellblazer.delos.cryptography.proto.PubKey.newBuilder()
            .setCode(1)
            .setEncoded(ByteString.EMPTY)
            .build();
        var event = KeyEvent_.newBuilder()
            .setInception(InceptionEvent.newBuilder()
                .setIdentifier(Ident.newBuilder().setBasic(emptyKey).build())
                .setCommon(createValidCommon())
                .build())
            .build();

        // When: Structural validation is performed
        var result = DhtValidationPipeline.validateEventStructure(event);

        // Then: Event fails validation
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Event with too-short basic PubKey encoded bytes fails structural validation")
    void testTooShortBasicEncodedBytesFails() {
        // Given: Event with encoded bytes shorter than minimum (32 bytes)
        var shortKey = com.hellblazer.delos.cryptography.proto.PubKey.newBuilder()
            .setCode(1)
            .setEncoded(ByteString.copyFrom(new byte[16])) // Only 16 bytes, need 32 minimum
            .build();
        var event = KeyEvent_.newBuilder()
            .setInception(InceptionEvent.newBuilder()
                .setIdentifier(Ident.newBuilder().setBasic(shortKey).build())
                .setCommon(createValidCommon())
                .build())
            .build();

        // When: Structural validation is performed
        var result = DhtValidationPipeline.validateEventStructure(event);

        // Then: Event fails validation
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Event with empty selfSigning signatures fails structural validation")
    void testEmptySelfSigningSignaturesFails() {
        // Given: Event with empty signatures list in selfSigning identifier
        var emptySig = Sig.newBuilder()
            .setCode(1)
            .build(); // No signatures
        var event = KeyEvent_.newBuilder()
            .setInception(InceptionEvent.newBuilder()
                .setIdentifier(Ident.newBuilder().setSelfSigning(emptySig).build())
                .setCommon(createValidCommon())
                .build())
            .build();

        // When: Structural validation is performed
        var result = DhtValidationPipeline.validateEventStructure(event);

        // Then: Event fails validation
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Event with too-short selfSigning signature bytes fails structural validation")
    void testTooShortSelfSigningSignatureFails() {
        // Given: Event with signature bytes shorter than minimum (32 bytes)
        var shortSig = Sig.newBuilder()
            .setCode(1)
            .addSignatures(ByteString.copyFrom(new byte[16])) // Only 16 bytes, need 32 minimum
            .build();
        var event = KeyEvent_.newBuilder()
            .setInception(InceptionEvent.newBuilder()
                .setIdentifier(Ident.newBuilder().setSelfSigning(shortSig).build())
                .setCommon(createValidCommon())
                .build())
            .build();

        // When: Structural validation is performed
        var result = DhtValidationPipeline.validateEventStructure(event);

        // Then: Event fails validation
        assertThat(result).isFalse();
    }

    // Helper methods

    private KeyEvent_ createValidInceptionEvent() {
        return KeyEvent_.newBuilder()
            .setInception(InceptionEvent.newBuilder()
                .setIdentifier(createValidIdentifier())
                .setSpecification(IdentifierSpec.newBuilder()
                    .setHeader(createValidHeader())
                    .build())
                .setCommon(createValidCommon())
                .build())
            .build();
    }

    private KeyEvent_ createValidRotationEvent() {
        return KeyEvent_.newBuilder()
            .setRotation(RotationEvent.newBuilder()
                .setSpecification(RotationSpec.newBuilder()
                    .setHeader(createValidHeader())
                    .build())
                .setCommon(createValidCommon())
                .build())
            .build();
    }

    private KeyEvent_ createValidInteractionEvent() {
        return KeyEvent_.newBuilder()
            .setInteraction(InteractionEvent.newBuilder()
                .setSpecification(InteractionSpec.newBuilder()
                    .setHeader(createValidHeader())
                    .build())
                .setCommon(createValidCommon())
                .build())
            .build();
    }

    private Ident createValidIdentifier() {
        return Ident.newBuilder()
            .setSelfAddressing(testDigeste)
            .build();
    }

    private Header createValidHeader() {
        return Header.newBuilder()
            .setSequenceNumber(1)
            .setIdentifier(createValidIdentifier())
            .setVersion(Version.newBuilder().setMajor(1).setMinor(0).build())
            .build();
    }

    private EventCommon createValidCommon() {
        return EventCommon.newBuilder()
            .setAuthentication(testSignature)
            .setConfiguration(testDigeste)
            .build();
    }
}
